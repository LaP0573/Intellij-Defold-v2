# 05 — Roadmap: Fork, Fix, Extend

## Recommendation: **fork `Intellij-Defold`** — do not start from scratch

You invited push-back on this; here's the honest assessment, and it lands firmly on **fork**.

### Why forking is the right call
1. **The crash is trivial relative to the plugin's value.** It's a single EDT/coroutine API-contract slip (~10
   lines + a test mock — [03](03-bug-project-open-crash.md)). It is *not* evidence of rot. The rest of the codebase
   is coherent, idiomatic, and **unusually well-tested** (a full unit suite *plus* an IntelliJ Platform
   `integrationTest` suite).
2. **It already contains the hardest thing to build: a real debugger.** The `debugger/` package is a complete,
   from-scratch MobDebug implementation — ~20 distinct capabilities (breakpoints, conditional/log breakpoints,
   stepping, run-to-cursor, EXEC evaluation with sandboxed LuaJ value reconstruction, watches, coroutine-aware
   stacks, Defold-typed value rendering, paging, value editing, source navigation, path mapping), all tested.
   Neither VSCode extension has anything comparable — Kit *delegates* to a third-party extension, Buddy just asks
   the editor to start *its* debugger. Rebuilding this from scratch would be months of work and the single biggest
   risk in the whole project.
3. **It already owns build/run end-to-end and has a working hot-reload foundation.** These are non-trivial and
   correct (hot reload needs hardening, not rewriting — [04](04-deep-dives.md)).
4. **The missing features are additive, not architectural.** URL autocomplete, bundling/deploy, richer
   annotations, scaffolding — each slots into the existing structure (`@Service(PROJECT)`, `CompletionContributor`,
   `ProjectBuilder`/`RunRequest`, actions in `plugin.xml`) without reshaping it.
5. **License & provenance.** `Intellij-Defold` is MIT (`LICENSE`), so a fork is clean. The VSCode extensions are
   the *reference* for missing features, but they're TypeScript/VSCode-API — you port behavior, not code.

### What "start from scratch" would cost
You'd throw away the debugger, the build/run loop, hot reload, the console hyperlinks/styling, the file-type and
annotation plumbing, the icons/templates, and the entire test suite — to re-earn all of it before you even reach
today's baseline. The only scenario that justifies it is if you wanted a fundamentally different architecture
(e.g. an LSP-first design), which there's no indication you do.

### The one caveat
The plugin hard-depends on **EmmyLua2** (`com.cppcxy.Intellij-EmmyLua`) + **LSP4IJ** for all Lua language
intelligence and even some debugger PSI. That's a reasonable bet (don't reinvent a Lua engine), but it's an
external coupling to watch — `SourceNavigation.kt` already had to work around an EmmyLua2 API removal. Track those
plugins' compatibility as part of maintenance; it's not a reason to avoid the fork.

---

## Mechanics of the fork
1. Fork `https://github.com/aridclown/Intellij-Defold` to your account (the local clone's `origin` points there).
2. Decide on **rename/rebrand** (plugin `id`, `name`, vendor, package) — only needed if you intend to publish
   alongside the original on the Marketplace. For private use, keep IDs to ease pulling upstream fixes.
3. Verify the toolchain: JDK 21, `./gradlew buildPlugin` / `runIde`, and the `integrationTest` suite. Confirm the
   pinned plugin deps (EmmyLua2 `0.21.0.100-IDEA252`, LSP4IJ `0.19.2`) still resolve.
4. Land the crash fix first (below), confirm you can open a real Defold project, then branch per feature.

---

## Phased plan

### Phase 0 — Make it open (unblock everything) · *small*
- Apply the `DefoldProjectOpenProcessor` fix + update `DefoldProjectOpenProcessorIntegrationTest` ([03](03-bug-project-open-crash.md)).
- Smoke-test: open a real Defold project; confirm build/run/debug still work on 2025.2.
- *(Optional, same PR or follow-up)* address the Tier-1 threading-audit items (`EditorHttpClient` call sites; the
  startup EDT-modal-dialog pattern).
- **Exit criteria:** projects open; existing tests green; debugger/build/run verified by hand.

### Phase 1 — Highest-value additions · *medium*
- **Defold URL autocomplete** (port Buddy + improvements) — [04 §2](04-deep-dives.md#url-autocomplete). New index
  service + `CompletionContributor` + tests.
- **Harden hot reload** — [04 §1](04-deep-dives.md#hot-reload): verify the engine route with an integration test;
  set `DM_SERVICE_PORT` for normal runs; add an editor-delegation fallback; broaden discovery.
- **Exit criteria:** typing a Defold URL in a `.script` suggests real instances/components; hot reload works for a
  plain Run, with a passing engine-route test.

### Phase 2 — Bundling & deploy (close the biggest functional gap) · *medium-large*
- Extend `ProjectBuilder`/`RunRequest`/`DefoldCommandBuilder` to emit Bob `bundle` commands with
  `--platform/--architectures/--bundle-output` and signing flags (model: Kit's `bob.ts` + `config.bundleTargets`).
- Add a **Bundle** action + platform/options picker (Release/debug, texture compression, report, symbols, live
  update; iOS provisioning/identity; Android keystore/alias/format; `--build-server`).
- Optional **Deploy** step (`ios-deploy`/`adb`).
- **Exit criteria:** bundle a project for at least desktop + one mobile target from the IDE.

### Phase 3 — Annotations, dependencies & scaffolding · *medium*
- **Per-dependency Lua annotations** (extract lib `.lua` from `.internal/lib` zips; auto-sync on change).
- **Add-dependency-from-GitHub** workflow; optionally an **Asset Portal** browser (JCEF or Swing).
- **Scaffolding actions**: create game object / gui / lua module; **generate app-manifest**; **generate hashes module**.
- **Exit criteria:** dependency APIs autocomplete; can add a library from GitHub; can scaffold a game object.

### Phase 4 — Polish & differentiators · *ongoing*
- **Bob output → Problems panel** (a problem matcher/annotator) — *none of the three has this; a real edge.*
- Robust **running-game discovery** (SSDP/UPnP) + **live log streaming** for editor-launched games (port Buddy).
- **Teal** support; `game.project` settings form/validation; new-project wizard; native-extension support.

---

## Suggested branch/PR shape
- `fix/project-open-edt-crash` (Phase 0) — land first, independently.
- Then one feature branch per item (`feat/url-autocomplete`, `feat/hot-reload-hardening`, `feat/bundling`, …),
  each mapped to a GitHub issue in [06-proposed-issues.md](06-proposed-issues.md). Keep PRs reviewable and
  test-backed — the codebase's test culture is an asset; preserve it.
