# 03 — The "Cannot Open Project" Crash: Root Cause & Fix

## Symptom

Opening any Defold project (or `game.project`) immediately fails with **Start Failed / Internal error**:

```
com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments:
  Access is allowed from Event Dispatch Thread (EDT) only;
  If you access or modify model on EDT consider wrapping your code in WriteIntentReadAction or ReadAction
Current thread: Thread[#23,DefaultDispatcher-worker-4,5,main] (EventQueue.isDispatchThread()=false)
  at ...ThreadingAssertions.assertEventDispatchThread(ThreadingAssertions.java:89)
  at ...LaterInvocator.getCurrentModalityState(LaterInvocator.java:323)
  at ...PlatformTaskSupport.runWithModalProgressBlockingInternal(PlatformTaskSupport.kt:393)
  at ...TasksKt.runWithModalProgressBlocking(tasks.kt:205)
  at com.aridclown.intellij.defold.DefoldProjectOpenProcessor.openProject(DefoldProjectOpenProcessor.kt:52)
  at com.aridclown.intellij.defold.DefoldProjectOpenProcessor.openProjectAsync(DefoldProjectOpenProcessor.kt:39)
  at com.intellij.ide.impl.ProjectUtil.chooseProcessorAndOpenAsync(ProjectUtil.kt:327)
  at com.intellij.ide.impl.ProjectUtil.openOrImportAsync(ProjectUtil.kt:254)
  ... (kotlinx.coroutines DefaultDispatcher worker)
```

## Root cause

`DefoldProjectOpenProcessor` overrides **two** entry points and routes both through one shared private helper:

```kotlin
// DefoldProjectOpenProcessor.kt (current)
override fun doOpenProject(...) = openProject(...)              // line 33  — legacy, EDT
override suspend fun openProjectAsync(...) = openProject(...)   // line 39  — modern, coroutine

private fun openProject(...) : Project? {
    ...
    val openOptions = runWithModalProgressBlocking(             // line 52  ← crash
        owner = guess(), title = "Opening Defold project", cancellation = nonCancellable()
    ) { ... build OpenProjectTask ... }
    return ProjectManagerEx.getInstanceEx().openProject(projectDir, openOptions)  // blocking
}
```

- Modern IntelliJ (2025.2 / build 252) opens projects through an **async, coroutine** path:
  `ProjectUtil.openOrImportAsync → chooseProcessorAndOpenAsync → openProjectAsync(...)`. That call is made on a
  **background dispatcher** (`Dispatchers.Default`, i.e. `DefaultDispatcher-worker-N`) with **no** `withContext(Dispatchers.EDT)`.
  *(Verified: `ProjectUtil.kt` L320-323 — `processor.openProjectAsync(...)` is invoked directly on the background coroutine.)*
- `runWithModalProgressBlocking` is annotated **`@RequiresEdt` + `@RequiresBlockingContext`**. Internally it calls
  `LaterInvocator.getCurrentModalityState()` → `ThreadingAssertions.assertEventDispatchThread()`. On a coroutine
  worker thread that assertion throws → the dialog.

**Why now?** The deprecated synchronous `doOpenProject` historically ran on the EDT, where the blocking modal call
is legal. The platform now routes through the suspend `openProjectAsync` on a background thread, where the same
call is illegal. The crash is **unconditional** — every open.

In one line: *the code calls an EDT-only blocking function from a background coroutine thread.* There is also a
**second latent defect**: even without the assertion, `openProjectAsync` calls the **blocking**
`ProjectManagerEx.openProject(...)`, which would block the dispatcher; it should call the **suspend**
`ProjectManagerEx.openProjectAsync(...)`.

## The fix (verified against `intellij-community` branch 252)

The body inside the modal block is trivial — one `Path.exists()` check plus assembling an `OpenProjectTask`. There
is nothing to show progress for, and `openProjectAsync` already shows its own UI. The platform's **own**
`PlatformProjectOpenProcessor.openProjectAsync` builds its options **inline, off-EDT, with no modal wrapper**, and
calls the suspend `openProjectAsync` directly. Do the same.

**Recommended `DefoldProjectOpenProcessor.kt`:**

```kotlin
package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.DefoldConstants.GAME_PROJECT_FILE
import com.aridclown.intellij.defold.util.letIfNot
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.Project.DIRECTORY_STORE_FOLDER
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.platform.ide.progress.ModalTaskOwner.guess
import com.intellij.platform.ide.progress.TaskCancellation.Companion.nonCancellable
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.projectImport.ProjectOpenProcessor
import java.nio.file.Path
import kotlin.io.path.exists

class DefoldProjectOpenProcessor : ProjectOpenProcessor() {
    override val name: String = "Defold"

    override fun canOpenProject(file: VirtualFile): Boolean =
        if (file.isDirectory) file.findChild(GAME_PROJECT_FILE) != null
        else file.name.equals(GAME_PROJECT_FILE, ignoreCase = false)

    /** Modern path. Invoked on a background coroutine dispatcher (NOT the EDT). */
    override suspend fun openProjectAsync(
        virtualFile: VirtualFile,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean
    ): Project? {
        val projectDir = resolveProjectDir(virtualFile) ?: return null
        val openOptions = buildOpenOptions(projectDir, projectToClose, forceOpenInNewFrame)
        // suspend variant — does NOT block the dispatcher, manages its own threading internally
        return ProjectManagerEx.getInstanceEx().openProjectAsync(projectDir, openOptions)
    }

    /** Legacy path; in 252 only ever reached as the EDT fallback when openProjectAsync is unimplemented. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun doOpenProject(
        virtualFile: VirtualFile,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean
    ): Project? {
        val projectDir = resolveProjectDir(virtualFile) ?: return null
        // doOpenProject runs on the EDT, so the blocking modal bridge is legal here.
        val openOptions = runWithModalProgressBlocking(
            owner = guess(),
            title = "Opening Defold project",
            cancellation = nonCancellable()
        ) {
            buildOpenOptions(projectDir, projectToClose, forceOpenInNewFrame)
        }
        return ProjectManagerEx.getInstanceEx().openProject(projectDir, openOptions)
    }

    /** Thread-agnostic: resolve the directory that contains game.project. */
    private fun resolveProjectDir(virtualFile: VirtualFile): Path? = when {
        virtualFile.isDirectory -> virtualFile
        virtualFile.isFile && virtualFile.name.equals(GAME_PROJECT_FILE, ignoreCase = false) -> virtualFile.parent
        else -> null
    }?.toNioPath()

    /** Thread-agnostic: one filesystem check + option assembly (the old modal-block body). */
    private fun buildOpenOptions(
        projectDir: Path,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean
    ): OpenProjectTask {
        val isExistingProject = projectDir.resolve(DIRECTORY_STORE_FOLDER).exists()
        return OpenProjectTask.build()
            .withForceOpenInNewFrame(forceOpenInNewFrame)
            .withProjectToClose(projectToClose)
            .letIfNot(isExistingProject, OpenProjectTask::asNewProject)
    }
}
```

Key points (all confirmed against branch 252 source):
- `openProjectAsync` no longer touches `runWithModalProgressBlocking` → the EDT assertion can't fire.
- It calls the **suspend** `ProjectManagerEx.openProjectAsync(projectStoreBaseDir: Path, options): Project?`
  (exists in 252, `ProjectManagerEx.kt` L77) — not the blocking `openProject`.
- `openProjectAsync` does **not** require the caller to hold a read action or be on the EDT; it manages its own
  threading (configurators take `writeIntentReadAction` internally where needed).
- Dropping `withModalProgress` entirely is deliberate: it matches `PlatformProjectOpenProcessor`, avoids an
  empty modality enter/exit, and — importantly — **avoids breaking the integration tests** (which run the async
  path under `runBlocking` on the EDT, where entering real modality is hang-prone).

### Required test change

The fix breaks `DefoldProjectOpenProcessorIntegrationTest` as written: its mock stubs only the **blocking**
`manager.openProject(...)`, but the async path now calls the **suspend** `openProjectAsync(...)`, which is unstubbed
on the strict `mockk<ProjectManagerEx>()` → `MockKException`, failing all four `openProjectAsync` tests. Update
`withMockedProjectManager`:

```kotlin
import io.mockk.coEvery
// ...
coEvery { manager.openProjectAsync(any<Path>(), capture(options)) } answers {
    paths.add(firstArg<Path>()); openResult
}
// keep the existing blocking openProject stub only if doOpenProject is also exercised
```

### Edge cases (all handled)
`.idea` present/absent (→ `asNewProject` only when absent); `game.project` file vs project directory; `projectToClose`
/ `forceOpenInNewFrame` threaded through both paths; already-open project deduped by `openProjectAsync`.

### Corrections to be aware of
- `doOpenProject` is **`abstract`, not `@Deprecated`** in 252 (`ProjectOpenProcessor.kt` L80). The
  `@Suppress("OVERRIDE_DEPRECATION")` is a harmless no-op; keep or drop it. In 252 `doOpenProject` is only ever
  reached as an EDT fallback (`unimplementedOpenAsync`), so once `openProjectAsync` is overridden it is effectively
  dead for normal opens — keeping `runWithModalProgressBlocking` there is legal but inconsequential.

---

## Threading-hazard audit (rest of `src/main/kotlin`)

The crash above is the only *unconditional* one. The scan turned up several lower-severity latents worth a pass
during the fork (none block the crash fix; ranked by risk):

| Location | Risk | Note |
|---|---|---|
| `DefoldProjectOpenProcessor.kt:52` | 🔴 **crash** | Fixed above. **Only** `runWithModalProgressBlocking` occurrence in the codebase. |
| `EditorHttpClient.kt:18,40` | 🟡 medium | Synchronous OkHttp (5s timeout) in `sendCommand`/`connect`. If any caller is on the EDT, UI freezes up to 5s. Audit call sites (`DefoldEditorLauncher`/actions) run on a background dispatcher. |
| `DefoldPathResolver.kt:40,59` | 🟡 medium | `getApplication().invokeAndWait { showSettingsDialog(...) }` + blocking `Messages.showOkCancelDialog` during project startup. Self-invoke today (already on EDT via `DefoldProjectActivity`), but fragile; prefer `withContext(Dispatchers.EDT)` + suspend dialogs. |
| `DefoldProjectActivity.kt:62` | 🟡 medium | `withContext(Dispatchers.Main) { ensureEditorConfig(...) }` runs blocking modal dialogs on the EDT during a `ProjectActivity`. Prefer `Dispatchers.EDT` + suspend dialogs. |
| `ProjectRunner.kt:146` | 🟢 low/med | `runReadAction { gameProjectFile.inputStream...load }` does file IO **inside** a read action (holds the lock during IO). Read bytes outside the lock. |
| `SourceNavigation.kt:25` | 🟢 low/med | `runReadAction { ... FileEditorManager.getSelectedEditor }` — editor access is EDT-affined; verify caller thread. |
| `DefoldProjectService.kt:78` | 🟢 low | `app.invokeAndWait` guarded by `isDispatchThread`; safe today, could deadlock if ever reached holding a read lock. |
| `MobDebugProcess.kt:398,486,500` | 🟢 low | `invokeLater {}` UI updates; add a disposed/`ModalityState` check. |
| `AnnotationsDownloader.kt:25,63,69` | ✅ ok | Blocking HTTP + zip, but correctly wrapped in `withBackgroundProgress` (off-EDT). |

**Not found (good):** no `runBlocking` anywhere; writes go through suspend `edtWriteAction` (correct); no
`Thread.sleep` on EDT.

**Suggested order for the fork:** (1) ship the open-processor fix + test update (resolves the crash); (2) audit
`EditorHttpClient` call sites and the `DefoldPathResolver`/`DefoldProjectActivity` EDT-modal-dialog-during-startup
pattern; (3) the `runReadAction`-wrapping-IO spots as cleanups.

---

*Sources (all `intellij-community` branch 252): `platform/progress/shared/src/tasks.kt`,
`platform/ide-core-impl/src/com/intellij/openapi/project/ex/ProjectManagerEx.kt`,
`.../com/intellij/ide/impl/OpenProjectTask.kt`, `platform/platform-api/.../ProjectOpenProcessor.kt`,
`platform/platform-impl/.../ProjectUtil.kt`, `.../PlatformProjectOpenProcessor.kt`.*
