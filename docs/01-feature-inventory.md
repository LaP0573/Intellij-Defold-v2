# 01 — Exhaustive Feature Inventory

Three extensions, every feature found by reading the source (manifests + implementation). Status legend:
**✅ working** · **🟡 partial** · **🔴 broken** · **❔ unknown**.

---

## A. Intellij-Defold — "Defold Support" (Kotlin, IntelliJ 2025.2)

**Tech:** Kotlin 2.2.21 / JVM 21; IntelliJ Platform Gradle plugin 2.10.5; `sinceBuild=252`. Required plugin
dependencies: **EmmyLua2** (`com.cppcxy.Intellij-EmmyLua`), **LSP4IJ** (`com.redhat.devtools.lsp4ij`),
OpenGL-Plugin, INI4Idea. Runtime libs: ini4j (parse `game.project`/editor config), luaj-jse (reconstruct Lua
values in the debugger), okhttp (editor command API, hot reload, annotation downloads). Cross-platform
(Win/macOS x64+arm64/Linux). Well tested: JUnit5 + AssertJ + MockK unit tests **and** an IntelliJ Platform
`integrationTest` suite.

**Positioning:** turns IntelliJ into a Defold *coding + debugging* environment. It explicitly does **not** try to
replace the official editor for scene/asset authoring. Lua language smarts (completion, highlighting, navigation,
lint) are delegated to EmmyLua2 + LSP4IJ — the plugin does not implement them.

### Project setup
| Feature | Status | Notes |
|---|---|---|
| Defold project open / import (`ProjectOpenProcessor`, detects `game.project`) | 🔴 **broken** | Crashes on open — see [03](03-bug-project-open-crash.md). Logic is otherwise correct. |
| Project detection service (`isDefoldProject`, root, version, console helpers) | ✅ | Central gate for nearly every action. |
| Startup bootstrap (`DefoldProjectActivity`): disable IDE build actions, force quick-doc-on-completion, register file-type associations, set source root + excludes, attach annotations, resolve deps | ✅ | Associates `.script/.gui_script/.render_script/.editor_script` → **Lua**; `.collection/.go/.gui` → textproto; `.atlas` → custom; `.fp/.vp` → GLSL, etc. |
| Defold editor config discovery/parsing (`DefoldEditorConfig`) | ✅ | Parses editor INI; resolves `javaBin`/`editorJar`/per-platform `dmengine`. Tested. |
| Install-path resolution + prompt (`DefoldPathResolver`) | ✅ | Dialog → settings if not found. Tested. |
| Settings configurable (single setting: Defold install dir) | ✅ | Under Settings │ Languages │ Defold. |

### Build & run
| Feature | Status | Notes |
|---|---|---|
| Build (Bob `build`, Alt+B) | ✅ | Runs `java -cp <editorJar> com.dynamo.bob.Bob --variant=debug build`. |
| Clean build (Bob `distclean build`, Alt+Shift+B) | ✅ | With confirmation dialog. |
| Run game (build → extract `dmengine` → launch) | ✅ | Owns the full loop; output in Run tool window. |
| Engine extraction from editor jar (`EngineExtractor`) | ✅ | Uses prebuilt `build/<platform>/dmengine` or `jar -xf` from editor jar; chmod +x. Tested. |
| Engine discovery / port tracking (`EngineDiscoveryService`) | ✅ | Scrapes spawned engine **stdout** for log/service ports + address. Only tracks engines it launched. |
| Run/build delegation to a running editor over HTTP (`EditorHttpClient`) | ✅ | Opt-in; reads `.internal/editor.port`; POSTs `/command/build|rebuild|debugger-start`. Tested. |
| `game.project` debug-init-script injection/cleanup | ✅ | Injects `bootstrap.debug_init_script` for debug runs; cleans up after. |
| Dependency resolution (Bob `resolve`) | ✅ | No add/browse UI; resolves existing `game.project` deps only. |
| Process execution + lifecycle (`DeferredProcessHandler`, etc.) | ✅ | Correct Run-toolwindow stop/terminate behavior. Tested. |

### Debugging (the crown jewel — a complete native MobDebug debugger)
| Feature | Status |
|---|---|
| MobDebug run configuration type + factory + settings editor (host/port/roots/env/delegate) | ✅ |
| Run-configuration auto-producer (from project root) | ✅ |
| Program runners for Run and Debug executors | ✅ |
| MobDebug TCP server (line + length-prefixed framing, single-client, dup-detection) | ✅ |
| Protocol layer (run/step/over/out/suspend/exit, SETB/DELB, BASEDIR, OUTPUT, STACK, EXEC; per-command timeouts; status handlers) | ✅ |
| Debug session orchestration (`XDebugProcess`: BASEDIR negotiation, breakpoint re-send on reconnect, stop on session end) | ✅ |
| Line breakpoints on Lua (uses EmmyLua2's breakpoint type) | ✅ |
| Conditional + log (non-suspending) breakpoints | ✅ |
| Stepping (over/into/out) + pause + resume | ✅ |
| Run to cursor (temporary breakpoints) | ✅ |
| Expression evaluation (EXEC) with sandboxed **LuaJ** value reconstruction | ✅ |
| Hover / quick evaluation (EmmyLua2 PSI to find evaluable expr at caret) | ✅ |
| Watches + Evaluate dialog with current-frame-local completion | ✅ |
| Call stack incl. **coroutine-aware** stacks (serpent dump parsing) | ✅ |
| Variable inspection with **Defold-typed rendering** (hash, url, vmath vec/quat/matrix, message tables, script instances) | ✅ |
| Variable paging (locals @200, table/vararg children @100) | ✅ |
| Variable value editing (vector.x, table[key], url.socket, hash…) | ✅ |
| Variable source navigation (jump to declaration via PSI) | ✅ |
| Local↔remote path mapping/resolution | ✅ |
| Lua dump safety guards | ✅ |

*(All of the above carry unit and/or integration tests.)*

### Hot reload
| Feature | Status | Notes |
|---|---|---|
| Hot reload of `script`/`lua`/`gui_script`/`go` resources (`HotReloadService`, Alt+R) | 🟡 **partial/fragile** | Builds, MD5-ETag-diffs `build/default`, sends a hand-built protobuf `Resource.Reload` to the engine's `POST /post/@resource/reload`. Works only for engines the plugin launched **in debug mode**; route untested end-to-end. Details in [04](04-deep-dives.md). |

### Lua editing & annotations
| Feature | Status | Notes |
|---|---|---|
| Lua completion/highlighting/navigation/lint | ✅ (delegated) | Provided by EmmyLua2 + LSP4IJ, not by this plugin. |
| `.luarc.json` management for LuaLS (`LuarcConfigurationManager`) | ✅ | |
| Defold **core API** annotations download/cache/reload (`AnnotationsDownloader`) | ✅ | From `astrochili/defold-annotations` by Defold version; "Reload Annotations" action. **No per-dependency lib annotations; no Teal.** |

### Editor integration & UX
| Feature | Status | Notes |
|---|---|---|
| Atlas `image:` path references — go-to + rename/move (`AtlasImagePathReferenceContributor`) | ✅ | The only Defold-aware path navigation today; the closest existing primitive to URL completion. |
| File icons for Defold resource types | ✅ | Full icon set under `resources/icons/editor/`. |
| Script/file templates + "New Defold Script" action (Script/GUI/Render/Lua Module/Editor Script) | ✅ | |
| Open in Defold editor (Alt+O; per-OS launch) | ✅ | |
| Disable conflicting IDE build/compile actions inside Defold projects | ✅ | |
| Console: log severity styling (INFO/WARN/ERROR/DEBUG/RESOURCE) | ✅ | |
| Console: clickable `file:line` hyperlinks | ✅ | |
| Notifications (balloon group "Defold") | ✅ | |
| Project window icon (`.idea/icon.png`) | ✅ | |
| Project-scoped coroutine scope service | ✅ | Used to push work off the EDT. |

**Documented IntelliJ gaps:** no bundling/deploy (Bob only ever called with `build`/`distclean`/`resolve`); no
project scaffolding/new-project wizard; no scene/visual authoring; **no Defold URL/hash autocomplete**; no
app-manifest generation; no code-gen scaffolding (game object/gui/module/hashes); no asset-portal/dependency
browser; no native-extension support; no `game.project` settings form; Lua editing fully outsourced to EmmyLua2.

---

## B. vscode-defold — "Defold Kit" (TypeScript, by astrochili)

**Tech:** TypeScript/esbuild; VSCode API ^1.79. Deps: axios, adm-zip, ini, json5. Drives the Defold-bundled
**`bob.jar`** via the editor's own JDK (`child_process.exec`). Debugging delegated to
`tomblind.local-lua-debugger-vscode`; Lua features to `sumneko.lua`. Mobile deploy shells out to `ios-deploy`/`adb`.

**Positioning:** develop a Defold game from VSCode **without the Defold editor** — bootstrap, annotate, build,
**bundle to all 6 platforms**, launch/debug, and deploy to devices. Contributes only commands, snippets, settings
(no grammar, no own debugger, no problem matchers).

### Project setup & editor integration
| Feature | Status | Notes |
|---|---|---|
| 4-step setup wizard (editor path → recommended extensions → settings → annotations) | ✅ | Remembers picks; suggests on activation. |
| Defold editor path resolution + config parse (java/jar/editor jar) | ✅ | Reuses editor's JDK; no separate Java needed. |
| Install recommended extensions (sumneko.lua, local-lua-debugger, textproto, GLSL ×2) | ✅ | |
| Apply recommended workspace settings (~40 file associations + sumneko Lua config + glsllint) | ✅ | Useful reference list of Defold file types. |
| Add `astronachos.defold` to `.vscode/extensions.json` recommendations | ✅ | |
| Open Defold editor (per-OS) | ✅ | |

### Annotations (best-in-class for dependencies)
| Feature | Status | Notes |
|---|---|---|
| Defold **core API** annotations sync (version-matched; repo configurable astrochili/mikatuo) | ✅ | Into `defold_api`; registered in `Lua.workspace.library`. |
| Auto-sync core annotations at startup when editor version changes | ✅ | |
| **Per-dependency** annotations sync (hash dep URLs → match `.internal/lib` zips → extract lib `.lua` via `include_dirs`) | ✅ | IntelliJ lacks this. |
| Auto-sync dependency annotations via FileSystemWatcher (5s debounce) | ✅ | |
| Clean annotations | ✅ | |

### Build / run / bundle / deploy (best-in-class)
| Feature | Status | Notes |
|---|---|---|
| `bob.jar` runner (stream to Output channel) | ✅ | No problem matcher — plain text output. |
| Resolve dependencies (`resolve`, `--email`/`--auth`) | ✅ | |
| Clean build (`distclean`) | ✅ | Builds into `build/defoldkit`. |
| Build-for-launch (hidden; `build --variant debug`; resolves if needed) | ✅ | Used as the debug `preLaunchTask`. |
| Launch engine prep (stage `dmengine`, host platform) | ✅ | |
| Run/launch (only via Run-and-Debug `lua-local` config; F5) | ✅ | No standalone run command (removed in 2.0.5). |
| Debug with breakpoints (delegated to `local-lua-debugger`) | ✅ | Not mobdebug, not a DAP; local-only, no on-device. |
| **Bundle to 6 platforms** (iOS/Android/Win/macOS/Linux/HTML5) | ✅ | `--archive --platform --architectures --bundle-output`. |
| Bundle options (Release/Debug, texture compression, build report, debug symbols, live update) | ✅ | |
| iOS signing (`--mobileprovisioning`/`--identity`, debug/release) | ✅ | |
| Android signing (keystore/pass/alias, `--bundle-format aab,apk`) | ✅ | |
| **Deploy to device** (iOS `ios-deploy -b`, Android `adb install`) | ✅ | |
| Custom build server (`--build-server`, Extender) | ✅ | |
| Task provider (5 tasks) | ✅ | No problem matchers / taskDefinitions schema. |

### UX
| Feature | Status |
|---|---|
| Lua snippets (annotated script template + lifecycle fns) | ✅ |
| 16 settings under `defoldKit.*` | ✅ |
| Settings/launch-config migrations | ✅ |
| LogOutputChannel + console mirror | ✅ |
| Memento-persisted UI state | ✅ |

**Documented Kit gaps:** no standalone run flow (only Run-and-Debug); no own debugger / no DAP (delegated,
local-only); **no problem matchers**; no on-device/remote debugging; deploy is mobile-only (needs `ios-deploy`/`adb`
on PATH); no HTML5 live-serve; native-extension `.script_api` not converted to Lua annotations; relies on the user
having the Defold editor installed (for bob/JDK); **no scaffolding / new-project wizard / scene editing**; **no hot
reload**; potential `.internal/lib` vs `.internal/libs` path inconsistency.

---

## C. vscode-defold-buddy — "Defold Buddy" (TypeScript, by mikatuo)

**Tech:** TypeScript/esbuild; VSCode API ^1.74. Deps: axios, **ws** (Remotery log stream), **node-ssdp** (UPnP game
discovery), adm-zip, rxjs, protobufjs (only in unused WIP). Webview "Asset Portal" is a separate **Svelte 3** app.
`extensionDependencies`: sumneko.lua + vscode-teal — does not implement language intelligence itself.

**Positioning:** a productivity *companion* to the running Defold editor — reduce alt-tabbing. Drives the editor's
localhost HTTP command API, streams game logs, **autocompletes Defold URLs**, scaffolds objects, manages deps,
embeds an Asset Portal.

### Project setup & annotations
| Feature | Status | Notes |
|---|---|---|
| Initialize project (download LSP annotations + configure sumneko/teal) | ✅ | From `mikatuo/defold-lsp-annotations`; pick version + **Lua or Teal**. |
| Lua workspace settings config | ✅ | Writes `.vscode/settings.json`, `.defignore`, `.gitignore` entries. |
| **Teal** project init (`tlconfig.lua`, types, add `extension-teal`) | ✅ | Only extension with Teal support. |
| Extract/unzip **per-dependency** annotations from `.internal/lib` → `.defold/assets` | ✅ | Auto-runs + file watcher; special-cases druid; skips `extension-teal`. |
| State migrations | ✅ | |

### Autocomplete (its signature feature — see [04](04-deep-dives.md))
| Feature | Status | Notes |
|---|---|---|
| **Defold URL / `#component` / `/instance` autocomplete** in `.script`/`.lua` | ✅ | CompletionItemProvider for `lua`, triggers `"` and `:`; `.lua`→all instances, `.script`→attached go/collection only. |
| Defold file indexer (`.go`/`.collection` line-based parser; recursive URL composition) | 🟡 | Brittle order-based heuristic; only `.go`/`.collection`; no live re-index (watcher commented out). |
| "Index game files" command + on-activation index | ✅ | Manual re-index is the stale-suggestion workaround. |

### Editor integration (its other strength)
| Feature | Status | Notes |
|---|---|---|
| Editor HTTP command client + **port discovery** (saved port → editor log scrape → prompt) | ✅ | `EditorCommand` enum has ~30 commands. Directly portable to IntelliJ. |
| Running-game discovery via **SSDP/UPnP** + Remotery **WebSocket** log streaming | ✅ | `DataViewReader` decodes binary `LOGM` frames. IntelliJ only scrapes its own engine's stdout. |
| `[Editor] Project > Build` (POST `/command/build`, then stream logs) | ✅ | Requires editor running. |
| `[Editor] Project > Rebundle` (POST `/command/rebundle`) | ✅ | Uses editor's last bundle settings; no config. |
| `[Editor] Project > Fetch Libraries` (POST `/command/fetch-libraries`) | ✅ | |
| `[Editor] Debug > Start / Attach` (POST `/command/debugger-start`) | 🟡 | Triggers the **editor's** debugger; no breakpoints/stepping in VSCode. |
| **Hot reload on save** (POST `/command/hot-reload`; TS→Lua aware) | ✅ | Fully delegated to the editor. |
| "Defold Buddy" output channel + console TextMate grammar | ✅ | |
| Asset Portal webview (Svelte; browse defold.com/assets; "Add to project") | ✅ | ~208 bundled asset JSONs + GitHub fallback. |
| URL ReferenceProvider (go-to-definition) | 🔴 stub | Always returns undefined (explicit TODO). |
| Direct-engine protobuf reload (`wip/testCommand.ts`) | 🔴 unused | Abandoned prototype of the engine-direct approach IntelliJ uses. |

### Code generation / dependencies
| Feature | Status | Notes |
|---|---|---|
| Create Game Object (scaffold `.go` + `.script` + `.factory`, embedded sprite/collisionobject) | ✅ | Generated script has `---@class self` annotations. |
| Create Gui (scaffold `.gui` + `.gui_script`) | ✅ | |
| Create Lua Module (compact / preprocessor templates) | 🟡 | Template settings read but **not declared** in `package.json` → effectively dead config. |
| Generate app manifest (reduce bundle size; 11 platforms) | ✅ | Port of britzl/manifestation; mirrors editor's generator. |
| Generate hashes Lua module (alpha) | 🟡 | Referenced collections/prototypes not fully expanded. |
| Add dependency from GitHub (browse releases → write `dependencies#N` → fetch) | ✅ | Unauthenticated GitHub API (rate-limit risk). |

**Documented Buddy gaps:** no language intelligence of its own; **no go-to-definition / URL validation**; fragile
`.go`/`.collection`-only parser (no `.gui`/factory/proxy); no live re-index; **no real debugger** (only "tell the
editor to debug"); editor control is fire-and-forget HTTP (no build-error parsing); **build/bundle only works if the
editor is running** (no bob/CLI path); editor launch is OS-incomplete (Linux unsupported); **no declared settings**;
no keybindings; GitHub-only dependency mgmt (can't remove deps); Asset Portal "copy files" stubbed; essentially no
tests.
