# Staleguard — Project Reference

*For someone reading this project for the first time. Accurate as of 2026-08-13 (commit `52b2994`). If you change the architecture, change this file.*

## 1. What this is

Staleguard is an IntelliJ IDEA plugin for **dependency freshness and upgrade hygiene**: it shows, inside the editor, which declared Maven/Gradle dependencies have newer versions, how big each jump is (patch / minor / major), offers one-click version bumps, and — its differentiator — flags dependencies whose newest release is more than 2 years old ("this library looks abandoned").

Business context that shapes technical decisions:

- **Free core, forever.** JetBrains Marketplace search ranking multiplies by `log10(1 + 5 × downloads)` — a free tier is the distribution engine. A paid teams tier is gate-locked behind adoption milestones and does not exist in this codebase yet.
- **Positioned as the successor to JetBrains' deprecated Package Search** (3.16M downloads, backend shut down April 2025) and two abandoned ~620K-download helper plugins.
- **The niche's #1 rating killer is IDE freezes** from blocking network calls (verified across rival plugins' 1-star reviews). Never blocking the UI thread is an architectural invariant here, not an optimization.
- **Explicitly out of scope**: vulnerability/CVE data (Snyk/Sonar/JetBrains Package Checker own it), npm (v2), telemetry of any kind, AI features.
- Vendor of record: Tampwell LLC. Public contact: staleguard@tampwell.com. All public-facing prose (README, marketplace description) is written by the project owner personally — do not generate it.

## 2. Stack (verified versions, not aspirations)

| Thing | Version | Notes |
|---|---|---|
| Language | Kotlin 2.3.20 | 2.1.x cannot emit JVM target 25, which branch 262 requires |
| Build | IntelliJ Platform Gradle Plugin 2.16.0, Gradle 9.5 wrapper | scaffolded from `intellij-platform-plugin-template` 2.6.0 |
| Target IDE | IntelliJ IDEA 2026.2.1 (build 262.x) only | "current release only" policy — no back-compat matrix, ~2 bumps/year expected |
| JDK | Temurin 21 to run Gradle; toolchain provisions what compilation needs | |
| Plugin deps | `org.jetbrains.idea.maven` (bundled Maven plugin) | declared in both `plugin.xml` (`<depends>`) and `build.gradle.kts` (`bundledPlugin(...)`) — both are required |
| Serialization | Gson (bundled in the platform) | deliberately not kotlinx.serialization: no compiler-plugin coupling for a 20-line cache format |

Build commands: `./gradlew.bat build` (compile + all tests) · `runIde` (sandboxed IDE with plugin) · `runIde --args="<project path>"` (open a project directly) · `verifyPlugin` (binary compatibility — must pass before every milestone).

## 3. Architecture: pure core, thin platform shell

The load-bearing design rule: **everything with logic worth testing is plain Kotlin with zero platform imports; platform APIs live only at the edges.** This is why the project has 78 fast unit tests and exactly one heavyweight platform-fixture test (a canary).

```
                    PURE CORE (no platform imports, exhaustively tested)
  version/    MavenVersion            faithful port of Apache Maven's ComparableVersion
              UpgradeSeverity         MAJOR/MINOR/PATCH/QUALIFIER classification
  maven/      MavenPropertyInterpolator   ${property} resolution, cycle-safe
  model/      DeclaredDependency      a dependency as written in the build file
  repository/ MavenMetadata           maven-metadata.xml parsing (XXE-hardened)
              VersionLookupEngine     ALL caching/network policy (injected client+clock)
              DiskVersionCache        atomic JSON-per-artifact persistence
              MavenRepositoryUrls     URL construction

                    PLATFORM SHELL (thin, mostly untested by design)
  maven/      PomDependencyCollector  reads declared deps via Maven DOM API
  repository/ HttpMavenRepositoryClient   HttpRequests-based network edge
  services/   VersionLookupService    @Service(APP) wiring engine to the IDE
  actions/    ListDeclaredDependenciesAction   temporary Tools-menu proof surface
```

Data flow for the eventual inspection (Milestone 4, not yet built):

```
pom.xml --PomDependencyCollector--> DeclaredDependency(resolvedVersion)
                                        |
inspection (highlighting pass) ---- reads WARM CACHE ONLY, never network
                                        |
VersionLookupService.lookup() --async-> VersionLookupEngine
    cache fresh (<24h)  -> return, zero I/O
    cache expired       -> conditional GET with ETag (repo1 serves 304s)
    network failed      -> serve stale, marked stale=true
    newest changed      -> one HEAD on its .pom for the release date
                                        |
        on new data --> DaemonCodeAnalyzer.restart(project) re-highlights
```

## 4. Why each non-obvious decision was made

- **`MavenVersion` is a port, not an original.** Version ordering is where dependency tools embarrass themselves. The class is a line-faithful Kotlin port of `org.apache.maven.artifact.versioning.ComparableVersion` (Apache-2.0, attributed in the file header; add a NOTICE file before marketplace publish), and its test suite is a port of Maven's own `ComparableVersionTest` — 23 tests including the MNG-5568/6572/6964/7644/7700/7714 regressions. If you "simplify" this class, the test suite is the referee. Do not replace it with a semver library; Maven ordering is not semver (`1.0-rc1 < 1.0 < 1.0-sp`, `1a1 == 1-alpha-1`, case-insensitive).
- **Maven DOM, not raw XML, for pom parsing.** `MavenDomProjectModel` costs a bundled-plugin dependency but gives PSI-anchored elements — which is what makes in-editor highlighting and safe write-back (the quick fix) possible later. A raw XML parser would need re-anchoring work anyway.
- **`maven-metadata.xml` from repo1, never the search API.** The metadata files are small, CDN-fronted, ETag-revalidatable (live-verified: conditional GET returns 304), and identical across Central/Google/private Nexus — so private-repo support later is a base-URL parameter, not a new code path. Sonatype's own 429 guidance pushes bulk users off the search index.
- **We compute `latest`/`latestStable` ourselves.** The `<latest>` tag includes SNAPSHOTs and `<release>` means "last *added*", which goes backwards on backport releases. Both are ignored; we take the max of `<versions>` under our own ordering. Stability filtering is token-aware against the *canonical* form (so `2.0-arch` is stable despite containing "rc", and `3.0M3` is correctly a milestone).
- **Abandonment date via HEAD on the newest version's `.pom`.** `lastUpdated` in metadata is a deploy/regeneration time (Sonatype regenerates server-side — observed) and unreliable. The `.pom`'s `Last-Modified` is the exact release date of that version (live-verified against search.maven.org's timestamp), works on any Maven repo, and is immutable — so it's cached until the newest version changes. Cost: one HEAD per artifact per new release.
- **Coroutine scopes are platform-injected.** `VersionLookupService(scope: CoroutineScope)` receives its scope from the container; the SDK docs forbid self-created scopes (leak + cancellation bugs). Blocking HTTP runs under `Dispatchers.IO`. `HttpRequests` (not raw JDK HttpClient) inherits the IDE's proxy and certificate configuration for free; `PlatformHttpClient` is the eventual successor but still `@ApiStatus.Experimental`.
- **The disk cache is disposable by contract.** Any unreadable or schema-mismatched file is deleted and refetched. Never make the cache a source of truth; never migrate schemas — bump `SCHEMA_VERSION` and let it rebuild.
- **`class`, never `object`, for extension points** (platform manages lifecycle), and never bundle kotlin-stdlib or kotlinx-coroutines (`kotlin.stdlib.default.dependency=false` is set; the platform provides both).

## 5. Product defaults (decided by the owner, 2026-08-13)

| Decision | Value | Rationale |
|---|---|---|
| Suggested upgrade | latest **stable** only | prerelease suggestions are the top complaint on rival plugins; prerelease toggle comes with the settings page |
| Abandonment threshold | 2 years since newest release | conservative; user-adjustable later |
| Cache TTL | 24 hours | freshness beyond a day is worthless for this use case; politeness to Central |
| UI surface | inspections + quick fixes (not ExternalAnnotator/inlays) | inspection-profile UX, batch-run support; precedent: Android Studio's "Newer Library Versions Available" |

## 6. Testing philosophy

- Pure core: exhaustive JUnit 4 tests, no IDE fixture, milliseconds each. This is where all the hard bugs live (version ordering, property cycles, cache policy, coalescing).
- `VersionLookupEngine` is tested with a fake client + fake clock: TTL hit, ETag 304 path, stale-on-failure, 404, single-flight coalescing under 8 concurrent callers, release-date refetch-only-on-change, malformed-body fallback.
- One `BasePlatformTestCase` smoke test (`PlatformSmokeTest`) exists as a canary for platform/JDK breakage. Platform-fixture tests are expensive and flaky (the template's own rename demo test was VfsRootAccess-broken on Windows and was deleted) — add them only when they guard something real.
- Manual end-to-end: `testProjects/maven-sample` is a deliberately hostile 3-module Maven project (property-interpolated versions, BOM import, parent-managed versions, two abandoned libraries). `runIde --args` opens it; Tools → "Staleguard: List Declared Dependencies" should report **10 dependencies across 3 modules**.

## 7. Implementation recommendations (the next builder's to-do)

**Milestone 4 — the inspection (start here):**
1. `LocalInspectionTool` registered via `<localInspection>` for XML, filtered to `pom.xml` files; walk the DOM dependencies (reuse `PomDependencyCollector`), and for each with a resolved version, consult a **synchronous cache-peek** API (add `peek(coordinates): ArtifactVersions?` to the service — the inspection must never suspend or fetch).
2. On cache miss, enqueue the coordinates for background lookup; when the engine returns fresh data, call `DaemonCodeAnalyzer.getInstance(project).restart()` (debounced) so highlighting re-runs against the now-warm cache.
3. Map `UpgradeSeverity` → highlight: MAJOR = WEAK_WARNING with "major" wording, MINOR/PATCH = WARNING (tune after dogfooding). Abandonment (newestReleaseAt > 2y) is its own inspection message even when the version is current.
4. Quick fix (`LocalQuickFix`): set the `<version>` element's value via the DOM (`dep.version.stringValue = ...`) — DOM writes handle PSI/undo correctly inside the quick-fix's write action. Property-defined versions (`${x.version}`): the fix must edit the **property definition**, not inline the literal — follow the reference from `MavenDomProjectModel.properties`. Versions managed by a parent/BOM: offer no fix in v1 (report only).
5. Wire messages through `StaleguardBundle` — hardcoded strings block future i18n and fail plugin verification style checks.

**After M4, in order:** Gradle Groovy DSL (Groovy PSI, `bundledPlugin` on the Groovy plugin), `build.gradle.kts` (Kotlin PSI), `libs.versions.toml` (bundled `org.toml.lang` TOML PSI) — the engine and cache are format-agnostic already; only collectors are new. Gradle *plugin* versions resolve via `https://plugins.gradle.org/m2/<id-path>/<plugin.id>.gradle.plugin/maven-metadata.xml`. Then the settings page (`PersistentStateComponent` + `Configurable`: ignore-list, thresholds, prerelease toggle, TTL) and the batch "update all patch versions" action.

**Known gaps a first-timer should not be surprised by:**
- Property resolution covers the current pom's `<properties>` + `project.*` built-ins; **parent-inherited properties are not resolved yet** (documented in `PomDependencyCollector`).
- Version **ranges** (`[1.0,2.0)`) and `LATEST`/`RELEASE` keywords are unhandled — decide policy (probably: report, never auto-fix).
- Only Maven Central is queried; Google's Maven repo (Android artifacts) and multi-repo poms are future work — the URL layer already supports any base URL.
- `ListDeclaredDependenciesAction` is scaffolding; delete it once the inspection ships.
- LICENSE file is still the template's Apache-2.0; the licensing decision (and the NOTICE file for the ComparableVersion port) must be settled **before** marketplace submission.

**Marketplace submission checklist (gate 1):** `verifyPlugin` green → plugin name must not contain "Plugin"/"IntelliJ"/"JetBrains" ("Staleguard" is clear) → logo must not resemble the template default or JetBrains logos → first 40 chars of the description are English and are the preview-card text → screenshots 1280×800 → owner-written prose → vendor profile under Tampwell LLC with staleguard@tampwell.com + tampwell.com → trader declaration made by the LLC's owner (public disclosure of LLC contact details is mandatory even for free plugins under the DSA — this was verified against the Developer Agreement v3.1 and JetBrains' trader docs).

## 8. Working conventions

Read `PROGRESS.md` at session start; update it and commit at session end — it is the single source of truth for state, next steps, and the decisions log. `CLAUDE.md` holds the hard rules (never block the EDT; no telemetry; no CVE features; approved data sources; no secrets; public words are the owner's). Every session ends at a committable, testable state. Gates (marketplace listing by week 8; 1,000 installs by month 4; 5,000 + 10 unsolicited requests by month 8; first $200 payout by month 12) override momentum — if a gate fails, stop and reassess instead of building more features.
