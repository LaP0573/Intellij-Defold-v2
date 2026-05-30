# 02 — Comparison, Overlap & Gaps

How the three extensions overlap, where they differ, and exactly what the IntelliJ plugin is missing.

## Capability matrix

Legend: **✅** full · **🟡** partial/weaker · **🔴** none · *(delegated)* = relies on an external tool/editor.

| Capability | Intellij-Defold | Defold Kit (vscode-defold) | Defold Buddy (vscode-defold-buddy) |
|---|:---:|:---:|:---:|
| **Open existing project** | 🔴 *(crashes — fix in [03](03-bug-project-open-crash.md))* | ✅ (folder = workspace) | ✅ (folder = workspace) |
| New-project wizard / scaffolding | 🔴 | ✅ setup wizard | 🟡 init (annotations only) |
| Detect Defold project | ✅ `game.project` | ✅ | ✅ |
| **Build** | ✅ Bob direct | ✅ Bob direct | 🟡 editor `/command/build` *(needs editor)* |
| **Clean build** | ✅ `distclean` | ✅ `distclean` | 🔴 |
| **Resolve dependencies** | ✅ Bob `resolve` | ✅ Bob `resolve` (+auth) | 🟡 editor `/command/fetch-libraries` |
| **Run game** | ✅ owns loop (build→extract→launch) | ✅ via lua-local launch cfg | 🟡 editor `/command/build` *(editor runs it)* |
| **Bundle (cross-platform)** | 🔴 | ✅ iOS/Android/Win/macOS/Linux/HTML5 | 🟡 editor `rebundle` (last settings only) |
| **Mobile/web export + signing** | 🔴 | ✅ iOS provisioning/identity, Android keystore | 🔴 |
| **Deploy to device** | 🔴 | ✅ `ios-deploy` / `adb` | 🔴 |
| Custom build server (Extender) | 🔴 | ✅ `--build-server` | 🔴 |
| **Real debugger (breakpoints, stepping, vars)** | ✅ **native MobDebug** | 🟡 *(delegated to local-lua-debugger)* | 🔴 *(only "editor, start your debugger")* |
| Conditional/log breakpoints, run-to-cursor, watches, coroutine stacks, value editing | ✅ | 🔴 | 🔴 |
| On-device / remote debugging | 🔴 | 🔴 (local-only) | 🔴 |
| **Hot reload** | 🟡 engine-direct (debug-run only; fragile) | 🔴 | ✅ *(delegated to editor `/command/hot-reload`)* |
| **Defold URL / `#`/`/` autocomplete** | 🔴 | 🔴 | ✅ (its signature feature) |
| Go-to-definition for URLs / URL validation | 🔴 | 🔴 | 🔴 (stub) |
| Lua completion/highlight/lint | ✅ *(EmmyLua2+LSP4IJ)* | ✅ *(sumneko.lua)* | ✅ *(sumneko.lua)* |
| **Core API annotations** | ✅ (astrochili repo) | ✅ (configurable repo) | ✅ (mikatuo repo) |
| **Per-dependency lib annotations** | 🔴 | ✅ (+ auto-sync) | ✅ (+ asset patching) |
| **Teal support** | 🔴 | 🔴 | ✅ |
| Scaffolding (game object / gui / module) | 🔴 | 🔴 | ✅ |
| App-manifest generation | 🔴 | 🔴 | ✅ |
| Hashes module generation | 🔴 | 🔴 | 🟡 alpha |
| Add-dependency-from-GitHub / Asset Portal browser | 🔴 | 🔴 | ✅ |
| Running-game discovery | 🟡 own-engine stdout scrape | 🔴 | ✅ SSDP/UPnP + Remotery WS |
| Editor HTTP command API use | 🟡 build/rebuild/debugger-start | 🔴 (launch app only) | ✅ ~30 commands |
| Live engine log streaming from any game | 🔴 (own engine stdout only) | 🔴 | ✅ Remotery WebSocket |
| Console `file:line` hyperlinks + severity coloring | ✅ | 🟡 (plain text) | 🟡 (TextMate coloring) |
| File icons for Defold resources | ✅ | 🔴 | 🔴 |
| File/script templates | ✅ | ✅ (snippets) | ✅ (scaffolds) |
| Disable conflicting IDE build actions | ✅ | n/a | n/a |
| Problem matcher / build-error → Problems panel | 🔴 | 🔴 | 🔴 |

## The three overlap heavily on the "plumbing", differ on the "product"

**Shared foundations (all three independently re-implement these):**
- Locate the Defold editor install and parse its `config` INI to find the bundled JDK + `editor.jar` (which contains Bob and the per-platform `dmengine`). *Kit and IntelliJ have nearly identical INI-parsing logic.*
- Download Lua **API annotations** from a community GitHub repo, version-matched, and point the Lua language server at them.
- Know the editor exposes `http://localhost:<port>/command/<name>` and that `dmengine` can be extracted from the editor jar's `libexec/<arch>/`.

**Where they diverge — each made a different core bet:**
- **Intellij-Defold** bet on being a *true IDE*: own the run loop, build a real debugger, integrate hot reload directly with the engine. It is editor-independent and the most self-sufficient, but it stopped at coding+debugging (no bundling, no scaffolding, no URL autocomplete).
- **Defold Kit** bet on *headless completeness*: do everything through `bob.jar` so you never need the editor — including the full **bundle/sign/deploy** matrix. It has no debugger or language server of its own (delegates both).
- **Defold Buddy** bet on being a *companion to the running editor*: delegate building/bundling/reloading/debugging to the editor over HTTP, and add the conveniences the editor lacks in a text workflow — **URL autocomplete**, scaffolding, dependency/asset browsing, log streaming.

## What IntelliJ is missing, ranked by value for an IDE-grade Defold experience

### Tier 1 — high impact, explicitly requested / clearly differentiating
1. **Defold URL autocomplete** (from Buddy). The single most-wanted missing feature; greenfield in IntelliJ but a clean port. → [04](04-deep-dives.md#url-autocomplete), [issue](06-proposed-issues.md).
2. **Cross-platform bundling + mobile/web export + signing** (from Kit). The largest functional gap; the editor-config plumbing for `javaBin`/`editorJar` already exists, so it's mostly new Bob command construction + a platform-picker UI. → [04](04-deep-dives.md#build-bundle-deploy).
3. **Harden hot reload** so it actually works for normal runs and editor-launched games, and verify the engine route. Today it's debug-run-only and untested end-to-end. → [04](04-deep-dives.md#hot-reload).

### Tier 2 — strong quality-of-life, moderate effort
4. **Per-dependency Lua annotations** (extract lib `.lua` from `.internal/lib` zips) — both VSCode extensions have it; IntelliJ ships core-API-only.
5. **Scaffolding commands**: create game object / gui / lua module; generate app-manifest; generate hashes module (from Buddy).
6. **Add-dependency-from-GitHub** workflow + (optionally) an Asset Portal browser (from Buddy).
7. **Robust running-game discovery** (SSDP/UPnP + editor-log/port fallback) and **live log streaming** for games not launched by the IDE (from Buddy).

### Tier 3 — nice to have / longer horizon
8. **Teal support** (from Buddy) — niche but unique.
9. **Build-error → Problems panel** (a Bob output problem matcher / annotator) — *none of the three has this*; a genuine differentiator if added.
10. **Project scaffolding / new-project wizard** (from Kit's setup wizard).
11. `game.project` settings form / validation; native-extension support.

> Note on **scene/visual authoring** (collection/`.go`/`.gui`/atlas/tilemap editors): *none* of the three attempt
> it, and it is an enormous undertaking. The realistic posture — shared by all three — is "code + build + debug in
> the IDE, author scenes in the official Defold editor." Keep that scope.
