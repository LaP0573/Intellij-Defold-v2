# 04 — Deep Dives on the High-Value Features

The three features that most determine whether the IntelliJ plugin feels "fully featured":
**Hot Reload**, **Defold URL autocomplete**, and **build/bundle/deploy + annotations**.

---

<a name="hot-reload"></a>
## 1. Hot Reload

### State of play across the three

| Extension | Mechanism | Status |
|---|---|---|
| **Defold Kit** (vscode-defold) | — | **None.** No reload command at all. |
| **Defold Buddy** | Delegates to the **editor**: `POST /command/hot-reload` | **Functional & robust** (needs editor running, which it detects/launches) |
| **Intellij-Defold** | Talks **directly to the engine**: `POST /post/@resource/reload` with a protobuf body | **Already implemented, but partial/fragile** |

**So: IntelliJ already has hot reload.** It is the most ambitious of the three — it bypasses the editor and
reimplements the reload flow against the running engine. The machinery is well-built and well-unit-tested; the
problem is *reach* and an *unproven engine contract*.

### How IntelliJ's works (`hotreload/HotReloadService.kt`)
1. Get console; `ensureReachableEngines()` — abort if none.
2. Snapshot current build artifacts (MD5 ETags), rebuild via Bob, refresh artifacts.
3. `findChangedArtifacts(old)` — diff by ETag, filtered to hot-reloadable extensions (`script`, `lua`, `gui_script`, `go`).
4. Build a protobuf `Resource.Reload { repeated string resources = 1 }` payload (hand-rolled varint encoding).
5. `POST` it to each reachable engine: `http://<addr>:<port>/post/@resource/reload`, `application/x-protobuf`.

Engine address/port come from `EngineDiscoveryService`, which **scrapes the stdout of engines the plugin
launched** for three regexes: `Engine service started on port (\d+)`, `Log server started on port (\d+)`,
`Started on address (...)`.

### Why it's fragile (and what to fix)

1. **🔴 Untested engine route.** `RELOAD_ENDPOINT = "/post/@resource/reload"`. Buddy's abandoned engine-direct
   prototype (`wip/testCommand.ts`) POSTed `Resource.Reload` to the **bare `/post`** endpoint instead. The route is
   the one externally-observable contract that must be exactly right, and **no test exercises a real engine** —
   `HotReloadServiceTest` mocks `sendResourceReload` entirely. *Action: verify against a running engine; add an
   integration test that POSTs to an actual/faked engine endpoint instead of mocking the send.*
2. **🟡 Discovery only covers self-launched, debug-mode engines.** `DM_SERVICE_PORT` is injected **only in the debug
   branch** of `EngineRunner` (`if (enableDebugScript)`), so a plain **Run** produces no service port →
   `currentEndpoints()` is empty → `hasReachableEngine()` is false → the action is **disabled**. Hot reload is
   effectively debug-run-only. It also can't reach engines started from the editor or outside the IDE. And when
   `ProjectRunner` **delegates the run to the editor**, there's no plugin-owned process at all. *Actions: set
   `DM_SERVICE_PORT` for normal runs too; add SSDP/UPnP discovery (port Buddy's `findRunningDefoldGame.ts`); and add
   an editor-delegation fallback (`POST /command/hot-reload`) reusing the existing `EditorHttpClient`.*
3. **🟡 Brittle discovery.** Ports/address come only from exact stdout regexes — any log-wording change silently
   disables reload, with no fallback (no `editor.port`-style file is used by the reload path).
4. **🟡 ETag diff can suppress legit reloads.** `findChangedArtifacts` requires the path to already exist in the old
   snapshot, so newly-compiled outputs won't reload; the size+mtime-keyed MD5 cache can desync.

### Recommended target design (IntelliJ)
Keep the engine-direct path as the fast default, but make it robust:
- **Endpoint:** confirm `/post/@resource/reload` vs `/post`; add an integration test.
- **Discovery (layered):** (a) self-launched engine stdout (today); (b) **SSDP/UPnP** for any running game; (c)
  **editor delegation** (`/command/hot-reload`) as the universal fallback. Enable the action if *any* layer finds a target.
- **Always set `DM_SERVICE_PORT`** for both run and debug so non-debug runs are reloadable.
- Relax the "must exist in old snapshot" rule so new resources reload.

---

<a name="url-autocomplete"></a>
## 2. Defold URL autocomplete

The feature you most want, present only in **Buddy** and **absent in IntelliJ**. Below: how Buddy does it, what
IntelliJ has to build on, and a concrete port plan.

### How Buddy does it (reference implementation)

**Index** (`defold-file-indexer.ts` + `defold-index.ts`):
- Scans **only `**/*.{go,collection}`** (not `.gui`/`.script`/`.atlas`).
- Parses the textproto **line-by-line** (no real parser): for each `id: "..."` line it looks at the **previous**
  line to classify it (`instances`/`embedded_instances` → instance; `components`/`embedded_components` →
  component) and the **next** line for `collection:`/`prototype:`/`component:`/`type:`. De-escapes embedded
  (inline) instances/components (`\"`, trailing `\n"`).
- Builds **URLs**: instance → `/<id>`; component in a `.go` → `#<id>`; component in an embedded instance → `/<parentId>#<id>`.
- After loading, `resolveReferences` **recursively expands** references: an instance with `collection:` inlines
  that collection's instances with the parent URL prefixed; an instance with `prototype:` inlines that `.go`'s
  components prefixed. Result = full tree of addressable URLs.

**Completion** (`url-completion-provider.ts`):
- `registerCompletionItemProvider('lua', {...}, '"', ':')` — language `lua`, trigger chars `"` and `:`.
- Fires only in `.script`/`.lua`; bails on `require "`; activates when the line prefix ends with `"`, `"#`, or
  matches `"\w+:$` (a `socket:` form). **Context-light** — it does *not* inspect which function you're calling.
- `.lua` file → **all** instance URLs across all collections. `.script` file → only URLs reachable from the
  go/collection the script is attached to (matched by the script's path appearing as a `component:` value).
- Items: label = URL, detail = Defold type, description = source filename; `filterText` = url/id.

**Refresh:** built once on activation + a manual "Index" command. A file watcher exists but is **commented out** —
stale suggestions until re-indexed. A `ReferenceProvider` (go-to-definition) is registered but returns `undefined`
(stub). Both are weaknesses to *improve on* in the port.

### What IntelliJ has to build on
- **No URL/resource autocomplete exists.** Closest primitives: `AtlasImagePathReferenceContributor` (a
  `PsiReferenceContributor` for `image:` paths in `.atlas` — navigation/rename, the *reverse* direction) and
  `MobDebugCompletionContributor` (the only `CompletionContributor`, for debugger locals — the exact mechanism to reuse).
- **Critical enabler:** `DefoldProjectActivity` associates `.script`/`.gui_script`/`.render_script`/`.editor_script`
  with the **Lua** file type, so they are real Lua PSI files and a `language="Lua"` completion contributor fires in
  them automatically. EmmyLua2 PSI (`LuaLiteralExpr`, `LuaCallExpr`, `LuaIndexExpr`, …) is already available and
  used by the debugger.

### Port plan
1. **Index** — a project `@Service(PROJECT)` (`DefoldUrlIndexService`) holding a precomputed snapshot.
   - Scan `.go`/`.collection` under the Defold content root (reuse `DEFOLD_DEFAULT_EXCLUDES`), read raw text via
     `VfsUtilCore.loadText` (**don't** rely on textproto PSI — the association is best-effort).
   - Port `extractDefoldInstances`/`parseId`/`identifyType`/`maybeParse`/`resolveReferences` **1:1** to Kotlin
     (data classes mirroring `IDefoldFile`/`IDefoldInstance`/`IDefoldComponent`). Keys = `"/relative/path"` from the
     content root (so they match `component:`/`prototype:`/`collection:` values).
   - *(Optional scale: a `FileBasedIndex` per file for incremental parse; reference-resolution stays in the service.)*
2. **Refresh — improve on Buddy:** build on `DefoldProjectActivity.execute()`; subscribe to `VFS_CHANGES`
   (`BulkFileListener`, the pattern already used in `setupIdeaDirListener`) on `*.go`/`*.collection`, **debounced**
   (~300-500ms via `DefoldCoroutineService`) → `rebuild()`. Add an optional "Reindex Defold resources" action.
   Return an empty index until the first build so completion never blocks.
3. **Contributor** — `DefoldUrlCompletionContributor` registered `<completion.contributor language="Lua">`. Inside
   `addCompletions`:
   - Read off `parameters.originalFile` (completion runs on a copy — the `MobDebugCompletionContributor` lesson).
   - Gate: Lua file + Defold script/`.lua` extension; climb to a string `LuaLiteralExpr` (don't fire outside
     strings); bail if the enclosing `LuaCallExpr` callee is `require`.
   - Sub-context from the inner-string prefix: contains `#` → components; matches `\w+:$` → socket form; else
     instances. *(Optional improvement over Buddy: inspect the enclosing call — `msg.url`/`go.get`/`factory.create`/
     `gui.get_node` — to bias ordering. `MobDebugXDebuggerEvaluator` shows the callee-resolution idiom.)*
   - Subset: `.lua` → all instances; `.script`/`.gui_script` → attached go/collection only.
   - Emit `LookupElementBuilder.create(url).withTypeText(type).withTailText(filename).withIcon(...)`; install a
     **prefix matcher derived from the inner-string text** (else `/`- and `#`-containing strings get filtered out).
     Use an insert handler that replaces the inner-string range.
4. **Trickiness — coexisting with EmmyLua2:** both contributors run; stay purely additive and tightly gated; you
   may need `order="first"` if EmmyLua2 stops the chain; handle the `originalFile`-vs-`position` copy; guard
   `DumbService` during indexing.
5. **Tests:** follow `AtlasImageReferenceContributorTest`/`MobDebugCompletionContributorTest` with
   `BasePlatformTestCase` — seed `.go`/`.collection` fixtures, `completeBasic()`, assert
   `lookupElementStrings` contains `/enemy`, `/enemy#sprite`, `#self_component`, …; plus pure parser/resolver unit tests.

**Suggested new code:**
```
…/defold/resources/DefoldIndex.kt              // model + queries (port of defold-index.ts)
…/defold/resources/DefoldFileIndexer.kt        // line parser + reference resolution
…/defold/resources/DefoldUrlIndexService.kt    // @Service(PROJECT): scan + cache + VFS refresh
…/defold/completion/DefoldUrlCompletionContributor.kt
…/defold/completion/LuaStringContext.kt        // PSI helpers
(follow-up) …/defold/resources/DefoldUrlReferenceContributor.kt  // Ctrl-click nav (atlas pattern)
```
**Improvements over Buddy to bake in:** live VFS-debounced re-index (Buddy's watcher is off); real PSI
string-context detection (vs line `endsWith`); optional per-API targeting; optional go-to-definition reference.
**Extend beyond Buddy (optional):** parse `.gui` for gui node ids and `.gui_script` host resolution.

---

<a name="build-bundle-deploy"></a>
## 3. Build / Bundle / Deploy & Annotations

### Build & run
- **IntelliJ** owns the loop: Bob `build` → `EngineExtractor` (prebuilt `build/<platform>/dmengine` or `jar -xf`
  from the editor jar) → `EngineRunner` launches it → stdout scraped for ports. *Strongest of the three.*
- **Kit** builds with Bob, stages `dmengine`, but the *spawn* is owned by VSCode's debug subsystem + the
  local-lua-debugger via a generated `launch.json`.
- **Buddy** never builds/runs itself — it POSTs `/command/build` to the editor.

### Bundling / deploy — IntelliJ's biggest functional gap
- **IntelliJ: none.** Bob is only ever invoked with `build`/`distclean`/`resolve`. No `--platform`, no signing, no
  deploy. The `LaunchConfigs` map only knows the *host* `dmengine` for local running.
- **Kit: complete.** `bob ... --archive --platform <p> --architectures <a> --bundle-output bundle/<t>` for **6
  targets** (iOS `arm64-ios`; Android `armv7,arm64` aab/apk; Win `x86_64-win32`; macOS x64+arm64; Linux
  `x86_64-linux`; HTML5 `js-web,wasm-web`). Options: Release/Debug, texture compression, build report, debug
  symbols, live update. Signing: iOS `--mobileprovisioning`/`--identity`, Android keystore/alias/pass. Deploy:
  `ios-deploy -b` / `adb install`. Build server via `--build-server`. **This is the model to port.**
- **Buddy: editor `rebundle` only** (no platform/arch/signing control; reuses the editor's last dialog settings).

**Port note:** IntelliJ already parses the editor config for `javaBin`/`editorJar`, so adding bundling is mostly
(a) extending `ProjectBuilder`/`RunRequest` to construct `bundle` commands with `--platform/--architectures/
--bundle-output` + signing flags, and (b) a bundle action + platform-picker UI (and optional `ios-deploy`/`adb`
deploy step). It's additive, not architectural.

### Library resolution & annotations
| | IntelliJ-Defold | Defold Kit | Defold Buddy |
|---|---|---|---|
| Resolve deps | Bob `resolve` | Bob `resolve` (+`--email`/`--auth`) | editor `/command/fetch-libraries` |
| Add dep from GitHub UI | 🔴 | 🔴 | ✅ (write `dependencies#N`) + Asset Portal |
| Core API annotations | ✅ (astrochili) | ✅ (configurable) | ✅ (mikatuo) |
| **Per-dependency lib annotations** | 🔴 | ✅ (+auto-sync on lib change) | ✅ (+asset patching) |
| Teal | 🔴 | 🔴 | ✅ |

**Port targets:** per-dependency annotation extraction (hash dep URLs → match `.internal/lib` zips → read each
lib's `game.project` `[library].include_dirs` → extract `.lua` → register as library roots) is well-specified in
both VSCode extensions and directly portable. The "add dependency from GitHub" flow + Asset Portal are Buddy-only
and high-UX-value.

### Editor command API & game discovery (portable building blocks)
- **Editor port discovery:** IntelliJ reads `.internal/editor.port`; Buddy does saved-port → editor-log scrape →
  prompt. Buddy's `EditorCommand` enum (~30 commands) is a useful catalog.
- **Running-game discovery:** Buddy's SSDP/UPnP + Remotery WebSocket log streaming (`findRunningDefoldGame.ts`,
  `DataViewReader` decoding binary `LOGM` frames) is the gold standard and would let IntelliJ stream logs / reach
  games it didn't launch. IntelliJ today only scrapes its own engine's stdout.
