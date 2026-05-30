# 06 — Proposed GitHub Issues

Ready-to-file issues for the fork, mirroring the findings. Each has a suggested title, labels, priority, body, and
acceptance criteria. Priorities: **P0** (blocker) → **P3** (nice-to-have). File these against your fork of
`Intellij-Defold`.

> Tip: you can bulk-create these with `gh issue create --title "…" --body "…" --label "…"` once the fork's remote
> is set. The titles below double as good branch names (`fix/…`, `feat/…`).

---

## P0 — Blocker

### #1 — Project open crashes with "Access is allowed from EDT only"
**Labels:** `bug`, `P0`, `crash` · **Area:** project open

Opening any Defold project on IntelliJ 2025.2 fails with *Start Failed / Internal error*:
`runWithModalProgressBlocking` is called from `DefoldProjectOpenProcessor.openProjectAsync`, which the platform
runs on a **background coroutine dispatcher**, but that function is `@RequiresEdt`. Unconditional — every open.

**Fix:** in `openProjectAsync`, drop the blocking modal wrapper, compute `OpenProjectTask` inline, and call the
**suspend** `ProjectManagerEx.openProjectAsync(...)` (not blocking `openProject`). Keep the blocking path only in
`doOpenProject`. **Also** update `DefoldProjectOpenProcessorIntegrationTest` to stub `coEvery { manager.openProjectAsync(...) }`.
Full details + corrected code: `docs/03-bug-project-open-crash.md`.

**Acceptance:** a real Defold project opens on 2025.2; `test` + `integrationTest` green; build/run/debug verified by hand.

---

## P1 — High value

### #2 — Defold URL / `#component` / `/instance` autocomplete in Lua scripts
**Labels:** `feature`, `P1`, `completion` · **Area:** editing

Port Buddy's signature feature. Index `.go`/`.collection` into a project service; register a
`CompletionContributor` for `language="Lua"` that suggests addressable Defold URLs inside string literals in
`.script`/`.lua` files. Improve on the reference: live VFS-debounced re-index, real PSI string-context detection,
optional per-API targeting. Design: `docs/04-deep-dives.md#url-autocomplete`.

**Acceptance:** in a `.script`, typing inside a string suggests `/instance`, `#component`, `/instance#component`
reachable from the script's host go/collection; in a `.lua`, all instances; index refreshes on file change; unit
+ `BasePlatformTestCase` tests cover parser and completion.

### #3 — Hot reload: verify engine route + add an end-to-end test
**Labels:** `bug`, `P1`, `hot-reload`, `testing` · **Area:** hot reload

`HotReloadService` POSTs to `/post/@resource/reload`, but every networked dependency is mocked, so the route is
unverified (Buddy's prototype used bare `/post`). Confirm the correct engine endpoint against a running engine and
add an integration test that POSTs the protobuf `Resource.Reload` payload to a real/faked engine instead of mocking
`sendResourceReload`.

**Acceptance:** a test exercises the actual HTTP send; reload confirmed working against a live engine.

### #4 — Hot reload works for normal (non-debug) runs and editor-launched games
**Labels:** `feature`, `P1`, `hot-reload` · **Area:** hot reload

Today `DM_SERVICE_PORT` is set only in the debug branch, so plain **Run** can't hot-reload, and only
plugin-launched engines are discoverable. Set `DM_SERVICE_PORT` for normal runs; add SSDP/UPnP discovery (port
`findRunningDefoldGame.ts`); add an editor-delegation fallback (`POST /command/hot-reload` via the existing
`EditorHttpClient`) so a delegated/editor-run game can still reload. Details: `docs/04-deep-dives.md#hot-reload`.

**Acceptance:** Alt+R reloads after a plain Run; reloads a game launched from the editor; action enabled whenever
any discovery layer finds a target.

---

## P2 — Close the big functional gaps

### #5 — Cross-platform bundling (iOS/Android/Win/macOS/Linux/HTML5)
**Labels:** `feature`, `P2`, `build` · **Area:** build/bundle

Add Bob `bundle` support: extend `ProjectBuilder`/`RunRequest`/`DefoldCommandBuilder` to emit
`--archive --platform <p> --architectures <a> --bundle-output …`, plus options (Release/debug, texture
compression, build report, debug symbols, live update) and a platform/options picker UI. Model: Kit's `bob.ts` +
`config.bundleTargets`. The editor-config plumbing (`javaBin`/`editorJar`) already exists.

**Acceptance:** bundle for desktop + at least one mobile/web target from the IDE; output path revealed on success.

### #6 — Mobile/web signing config (iOS provisioning/identity, Android keystore)
**Labels:** `feature`, `P2`, `build`, `signing` · **Area:** build/bundle · *depends on #5*

iOS `--mobileprovisioning`/`--identity` (debug/release); Android `--keystore`/`--keystore-pass`/`--keystore-alias`,
`--bundle-format aab,apk`; `--build-server` (Extender); `--email`/`--auth` for private deps. Settings UI mirroring
Kit's `defoldKit.bundle.*`.

**Acceptance:** a signed iOS/Android bundle can be produced with credentials supplied via settings.

### #7 — Optional deploy-to-device (`ios-deploy` / `adb`)
**Labels:** `feature`, `P2`, `deploy` · **Area:** build/bundle · *depends on #5*

Find the built `.ipa`/`.apk` and run `ios-deploy -b` / `adb install`. Gracefully report when the tools aren't on PATH.

**Acceptance:** install a freshly bundled mobile build onto a connected device.

### #8 — Per-dependency Lua annotations
**Labels:** `feature`, `P2`, `annotations` · **Area:** annotations

Extract each dependency's `.lua` from its `.internal/lib` zip (hash dep URLs → match archives → read
`[library].include_dirs` from the lib's `game.project` → extract `.lua` → register as Lua library roots), with
auto-sync on lib change. Both VSCode extensions implement this; IntelliJ ships core-API-only.

**Acceptance:** symbols from a library dependency autocomplete after `resolve`; updates when libs change.

---

## P3 — Productivity & differentiators

### #9 — Scaffolding: create game object / gui / lua module
**Labels:** `feature`, `P3`, `codegen` · Port Buddy's create-* commands (with the `---@class self` annotated
script template). **Acceptance:** right-click a folder → create a `.go`+`.script`+`.factory` / `.gui`+`.gui_script` / module.

### #10 — Generate app-manifest (bundle-size reduction)
**Labels:** `feature`, `P3`, `codegen` · Port Buddy's manifest generator (exclude Physics2D/3D, Record, Profiler,
…; write `generated.appmanifest` + set `[native_extension].app_manifest`). **Acceptance:** generates a valid manifest and wires it into `game.project`.

### #11 — Generate hashes Lua module
**Labels:** `feature`, `P3`, `codegen` · Emit a Lua module of `hash("<url>")` constants from the URL index (fully
expand referenced collections/prototypes — Buddy's alpha doesn't). **Acceptance:** generated module covers nested references.

### #12 — Add-dependency-from-GitHub + Asset Portal browser
**Labels:** `feature`, `P3`, `dependencies`, `ux` · Pick a GitHub release → write `dependencies#N` into
`game.project` → resolve. Optional JCEF/Swing Asset Portal over `defold/asset-portal` data. **Acceptance:** add a
library from a GitHub URL without hand-editing `game.project`.

### #13 — Bob build output → Problems panel
**Labels:** `feature`, `P3`, `build`, `dx` · A problem matcher/annotator that parses Bob compile errors into
clickable diagnostics. *None of the three extensions has this — genuine differentiator.* **Acceptance:** a Lua/build
error surfaces in the Problems view with file:line navigation.

### #14 — Robust running-game discovery + live log streaming
**Labels:** `feature`, `P3`, `logging` · Port Buddy's SSDP/UPnP discovery + Remotery WebSocket (`DataViewReader`
binary `LOGM` decode) so logs stream from games the IDE didn't launch. **Acceptance:** stream console output from an
editor-launched game.

### #15 — Teal support
**Labels:** `feature`, `P3`, `teal` · Teal annotations + project config (Buddy-only today). **Acceptance:** a Teal
Defold project gets type info and builds.

### #16 — Threading-hazard cleanup pass
**Labels:** `tech-debt`, `P3` · Address the lower-severity latents in `docs/03-bug-project-open-crash.md`
(`EditorHttpClient` blocking calls; `DefoldPathResolver`/`DefoldProjectActivity` EDT-modal-dialog-on-startup;
`runReadAction`-wrapping-IO in `ProjectRunner`/`SourceNavigation`). **Acceptance:** no blocking IO on the EDT; dialogs via suspend APIs.

### #17 — Project scaffolding / new-project wizard
**Labels:** `feature`, `P3`, `project-setup` · A "New Defold Project" flow (generate `game.project` + starter
template), à la Kit's setup wizard. **Acceptance:** create a runnable empty project from the IDE.

---

### Milestone suggestion
- **M1 "It works"** → #1 (+ optionally #16's Tier-1 items)
- **M2 "Feels like an IDE"** → #2, #3, #4
- **M3 "Ship a game"** → #5, #6, #7, #8
- **M4 "Productivity"** → #9–#15, #17
