# Defold IDE Extensions — Analysis & Roadmap

> Goal: get a **working, fully-featured Defold plugin for IntelliJ IDEA**, by assessing the
> existing IntelliJ plugin and the two VSCode extensions, fixing the crash that currently
> prevents opening projects, and porting the missing features.

This folder is the result of a deep, cross-codebase analysis of three extensions:

| Repo (local folder) | Name | Upstream | Platform |
|---|---|---|---|
| `Intellij-Defold/` | **Defold Support** (`com.aridclown.IntelliJ-Defold`) | [aridclown/Intellij-Defold](https://github.com/aridclown/Intellij-Defold) | IntelliJ IDEA 2025.2 (Kotlin) |
| `vscode-defold/` | **Defold Kit** (`astronachos.defold`) | [astrochili/vscode-defold](https://github.com/astrochili/vscode-defold) | VSCode (TypeScript) |
| `vscode-defold-buddy/` | **Defold Buddy** (`mikatuo.vscode-defold-ide`) | [mikatuo/vscode-defold-buddy](https://github.com/mikatuo/vscode-defold-buddy) | VSCode (TypeScript) |

## Document map

1. **[01-feature-inventory.md](01-feature-inventory.md)** — exhaustive feature list of each of the three extensions.
2. **[02-comparison-and-gaps.md](02-comparison-and-gaps.md)** — the overlap matrix (what each has, what differs) and the precise list of features the IntelliJ plugin is missing.
3. **[03-bug-project-open-crash.md](03-bug-project-open-crash.md)** — root cause of the "Start Failed / EDT only" crash, the verified fix (with corrected code + test change), and an audit of other latent threading hazards.
4. **[04-deep-dives.md](04-deep-dives.md)** — the high-value port targets in detail: **Hot Reload**, **Defold URL autocomplete**, and **build/bundle/deploy + annotations**.
5. **[05-roadmap.md](05-roadmap.md)** — recommendation (fork vs. from-scratch), and a phased plan from "crash fixed" to "feature parity+".
6. **[06-proposed-issues.md](06-proposed-issues.md)** — a ready-to-file set of GitHub issues mirroring the findings.

---

## TL;DR

### The crash is small and well-understood
`DefoldProjectOpenProcessor.openProjectAsync` is a `suspend` function that IntelliJ 2025.2 invokes on a
**background coroutine thread**, but it calls `runWithModalProgressBlocking`, which **must run on the EDT**.
That assertion throws on every project open. The fix is a ~10-line change (drop the blocking modal bridge on
the async path, call the suspend `ProjectManagerEx.openProjectAsync`) plus a one-line test mock update. See
[03](03-bug-project-open-crash.md). **This is not a sign the plugin is rotten — it's a single API-contract slip.**

### Recommendation: **fork `Intellij-Defold`, don't start from scratch**
The IntelliJ plugin is, by a wide margin, the most IDE-grade of the three. It already has the *hardest* thing
to build — a complete, from-scratch **MobDebug debugger** (breakpoints, conditional/log breakpoints, stepping,
run-to-cursor, expression evaluation, watches, coroutine-aware call stacks, Defold-typed value rendering,
value editing, source navigation), all unit + integration tested. It also already owns build/run end-to-end and
has a working (if narrow) hot-reload implementation. Neither VSCode extension has a real debugger — Defold Kit
delegates to a third-party extension, Buddy just tells the editor to start *its* debugger. Throwing that away to
rewrite would be a large, high-risk step backwards. The missing pieces (URL autocomplete, bundling/deploy,
richer annotations) are **additive**. See [05](05-roadmap.md) for the full argument.

### What's missing in IntelliJ (and who to copy it from)
| Missing capability | Best reference implementation |
|---|---|
| **Defold URL / `#component` / `/instance` autocomplete** | Buddy (`url-completion-provider.ts` + `defold-file-indexer.ts`) |
| **Cross-platform bundling + mobile/web export + signing** | Defold Kit (`bob.ts` + `config.bundleTargets` + `deployer.ts`) |
| **Per-dependency Lua annotations** (extract lib `.lua` from `.internal/lib`) | both VSCode extensions |
| **Teal support** | Buddy |
| **"Add dependency from GitHub" / Asset Portal browser** | Buddy |
| **Scaffolding** (create game object / gui / lua module, app-manifest, hashes module) | Buddy |
| **Robust running-game discovery** (SSDP/UPnP, editor-launched games) | Buddy (`findRunningDefoldGame.ts`) |
| **Project scaffolding / new-project wizard** | Defold Kit (setup wizard) |

### What IntelliJ already does *better* than both VSCode extensions
- A real, native **debugger** (the others have none of their own).
- **Owns the run loop** (build with Bob → extract matching `dmengine` → launch → parse logs) with no dependency on a running editor or a third-party debug extension.
- **Self-implemented hot reload** straight to the engine (no editor required) — see caveats in [04](04-deep-dives.md).
- First-class IDE niceties: clickable `file:line` console hyperlinks, log severity coloring, file icons, file templates, disabling of conflicting IDE build actions.

---

*Generated from a static analysis of the three codebases as checked out on 2026-05-30. Line numbers and file
paths refer to those snapshots. No changes were made to any of the three repos.*
