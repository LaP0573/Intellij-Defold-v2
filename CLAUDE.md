# CLAUDE.md

Guidance for AI agents working in this repo. For coding/testing conventions see **AGENTS.md**; for the
contributor/PR flow see **CONTRIBUTING.md**. This file covers the non-obvious, high-leverage things.

## What this is

An **IntelliJ IDEA plugin for Defold game development** (Kotlin), forked from `aridclown/Intellij-Defold`.
Plugin id `com.aridclown.IntelliJ-Defold`, package `com.aridclown.intellij.defold`, IntelliJ Platform **2025.2**
(`sinceBuild=252`), JVM toolchain **21**. Scope = **code + build + debug in the IDE**; scene/asset authoring stays
in the official Defold editor (explicitly out of scope). Lua language intelligence is **delegated** to the
required plugin deps **EmmyLua2** (`com.cppcxy.Intellij-EmmyLua`) + **LSP4IJ** — do **not** reimplement Lua
completion/highlighting/navigation here.

The active goal: fix the (now-fixed) project-open crash and port the features the VSCode extensions have. See
**`docs/`** (`05-roadmap.md`, `02-comparison-and-gaps.md`, `04-deep-dives.md`). Work is tracked in **Orchestra/Score**
(project `019e79fb-06a1-7000-a194-37d176a790c5`), not GitHub issues — `orchestra score view --project <id>`.

## Build / test / run

| Command | Purpose |
|---|---|
| `./gradlew check` | **The gate.** Runs `test` + `integrationTest` + spotless/ktlint. Every change must keep this green. |
| `./gradlew test` | Fast unit suite (`src/test`). |
| `./gradlew integrationTest` | IntelliJ Platform functional tests (`src/integrationTest`); `forkEvery = 1`. |
| `./gradlew spotlessApply` | Auto-format (ktlint 1.8.0). Run before committing or `check` fails on style. |
| `./gradlew runIde` | Launch a sandbox IDE to try changes by hand. |
| `./gradlew buildPlugin` | Produce the installable plugin zip. |

**Toolchain reality (important for CI / sandboxes / conduct):** the build needs **JDK 21**. `settings.gradle.kts`
includes the **Foojay resolver**, so Gradle auto-provisions Adoptium JDK 21 even if only a newer JDK is installed —
but the first build **downloads JDK 21 + the IntelliJ platform + EmmyLua2/LSP4IJ**, so it needs **network** and runs
several minutes (≈4–5 min warm, longer cold). Don't assume a build failure is your code until you've ruled out
toolchain/network.

## CRITICAL: threading (EDT vs. coroutines)

This is the single most common way to break the plugin (it caused the project-open crash — see
`docs/03-bug-project-open-crash.md`).

- The platform invokes `suspend` / coroutine entry points — `ProjectOpenProcessor.openProjectAsync`,
  `ProjectActivity.execute`, run/debug program runners, the project `CoroutineScope` (`project.launch { }`) — on a
  **background dispatcher (`Dispatchers.Default`), NOT the EDT**.
- From those contexts, **never** call EDT-only / blocking bridges: `runWithModalProgressBlocking`,
  `Application.invokeAndWait`, modal `Messages.show*`, `ProgressManager.runProcessWithProgressSynchronously`,
  blocking `ProjectManagerEx.openProject`. They assert `@RequiresEdt` and throw "Access is allowed from Event
  Dispatch Thread (EDT) only".
- Use the suspend equivalents instead: `withModalProgress` / `withBackgroundProgress`, the suspend
  `ProjectManagerEx.openProjectAsync`, `edtWriteAction { }`, `readAction { }`,
  `withContext(Dispatchers.EDT) { writeIntentReadAction { } }`.
- Conversely, `AnAction.actionPerformed` runs **on the EDT** — push heavy/IO/network work onto a background
  coroutine (the existing `DefoldCoroutineService` / `project.launch { }`).
- Do **no blocking network or file IO on the EDT**, and **no blocking IO inside a `readAction`/`runReadAction`**.
- Prefer `java.nio.file` (`Path`, `Files`) and IntelliJ VFS APIs over `java.io` (also in AGENTS.md).

## Architecture map (`src/main/kotlin/com/aridclown/intellij/defold/`)

`src/main/resources/META-INF/plugin.xml` is the source of truth for everything registered (actions, services,
contributors, run configs, file types). Key areas:

- **Project lifecycle:** `DefoldProjectOpenProcessor` (open/import), `DefoldProjectActivity` (startup: file-type
  associations, module/excludes, annotations, dep resolution), `DefoldProjectService` (`isDefoldProject`, roots,
  console helpers — the gate most actions check).
- **Defold install / config:** `DefoldEditorConfig`, `DefoldPathResolver`, `DefoldConstants` (locate editor, parse
  its INI for `javaBin`/`editorJar`/per-OS `dmengine`). Settings in `settings/`.
- **Build & run:** `ProjectBuilder` (Bob: `build`/`distclean`/`resolve`), `ProjectRunner`, `EngineRunner`,
  `EngineExtractor` (extract `dmengine` from the editor jar), `EngineDiscoveryService` (scrapes spawned-engine
  stdout for ports), `process/`. Bundling/deploy do **not** exist yet (roadmap M3).
- **Debugger (`debugger/`):** a complete native **MobDebug** implementation (TCP server, protocol, breakpoints,
  stepping, eval via sandboxed LuaJ, watches, coroutine-aware stacks, Defold-typed value rendering, paging, value
  editing). The plugin **manages mobdebug bootstrap itself** — users must NOT embed `mobdebug.start()`.
- **Hot reload (`hotreload/HotReloadService`):** engine-direct protobuf `Resource.Reload` to the engine's HTTP
  service. Works only for self-launched debug-mode engines today; hardening is roadmap M2 (`docs/04-deep-dives.md`).
- **Annotations:** `AnnotationsDownloader` / `DefoldAnnotationsManager` (core Defold API only; per-dependency lib
  annotations are a gap), `LuarcConfigurationManager` (`.luarc.json`).
- **Editor integration:** `EditorHttpClient` (`.internal/editor.port` → `/command/*`), `DefoldEditorLauncher`.
- **Editing extras:** `atlas/` (PSI reference for `image:` paths — the closest existing primitive to the planned
  Defold-URL autocomplete), `templates/`, `util/DefoldFileIconProvider`, `logging/` (console severity styling +
  clickable `file:line`).

## Porting features (M2–M4)

The reference implementations are the two upstream VSCode extensions — **not in this repo** (and not in the conduct
sandbox), so read the committed analysis instead of expecting sibling dirs:
- `docs/04-deep-dives.md` — hot reload, **Defold URL autocomplete**, bundling/deploy, annotations (with concrete
  IntelliJ port plans + API choices).
- Upstream refs (browse online if needed): `github.com/astrochili/vscode-defold` (Kit — bundling/deploy),
  `github.com/mikatuo/vscode-defold-buddy` (Buddy — URL autocomplete, scaffolding, editor API).

When adding a feature: register it in `plugin.xml`, add unit tests in `src/test` and/or functional tests in
`src/integrationTest` (behavior-named, AssertJ, see AGENTS.md), and ensure `./gradlew check` stays green. New Lua
completion goes through a `CompletionContributor` for `language="Lua"` (Defold scripts are associated to the Lua
file type, so it fires in `.script`/`.gui_script` too); keep it additive and gated so it coexists with EmmyLua2.
