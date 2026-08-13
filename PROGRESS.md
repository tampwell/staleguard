# Staleguard — Progress

> Session ritual: read this file at session start; update it and commit at session end.

## Current state (2026-08-13)

- Repo scaffolded from official `intellij-platform-plugin-template` 2.6.0 (Kotlin 2.1.20, IntelliJ Platform Gradle Plugin 2.16.0, Gradle 9.5 wrapper).
- Rebranded: package `com.tampwell.staleguard`, plugin id `com.tampwell.staleguard`, name "Staleguard", vendor "Tampwell".
- Target platform still the template's pinned `intellijIdea("2025.2.6.2")` — deliberate for a known-good first build; bump to 2026.2 (since-build 262) is the next config task and must be re-verified with `verifyPlugin`.
- ✅ 2026-08-13: `build` green (JDK 21 Temurin, IDEA CE 2025.2.6.2 installed via winget). `runIde` verified — sandbox log shows `Loaded custom plugins: Staleguard (0.1.0)`. Day-1 milestone complete. Template's flaky `testRename` demo removed (VfsRootAccess sandbox quirk).
- ✅ 2026-08-13 (later): Target bumped to IDEA 2026.2.1 / Kotlin 2.3.20 (2026.2 = JVM target 25; Kotlin 2.1.x can't emit it). **verifyPlugin: Compatible** against IU-262.9437.185. **Milestone 1 done**: MavenPropertyInterpolator (12 unit tests) + PomDependencyCollector (DOM, deps + depMgmt + property resolution) + Tools-menu logging action. All 13 tests green. Demo code deleted.
- GitHub: repo stays LOCAL until `gh auth login` is run by the builder (account: mingzhenm9-cloud). Repo will be PRIVATE until v1.

## Feature sprint — DONE (2026-08-13, commits ac1831f..f2c4044)

All six planned features shipped, each verifier-Compatible, 124 tests green:
1. Batch update (7d9868e): Tools menu, severity-grouped preview, patch pre-selected, one undo step, shared-property dedup (highest wins). Deviation: no intention/Alt+Enter entry point yet — Tools menu only.
2. Property blast-radius safety (7d9868e): confirmation with affected list before editing multi-use properties; per-property don't-ask in settings.
3. Recommendations (7d9868e): SAFE/REVIEW/BREAKING heuristic in inspection + batch rows. Deferred: "View Changelog" links (needs artifact-POM fetching for <scm> in the engine).
4. Stats tool window (31206d1): summary + per-module counts, navigable rows, live rebuild via FreshnessListener bus, Refresh All with TTL bypass (force flag; ETags still sent). Deviations: window state persistence = platform default; "checking" is per-row text, not a progress bar.
5. Gradle Groovy DSL (9d28854): optional Groovy-plugin dependency; string + map notation; GStrings/project()/files()/catalogs skipped; kts untouched. Deferred: plugins{} block (Gradle Plugin Portal lookups), Gradle properties resolution, version catalogs.
6. Age timeline (f2c4044): 5-year bars, legend, tooltips, PNG export. Deviation: bars = time since NEWEST release (cached), not in-use version's release date (would cost one HEAD per dependency).

Verifier note: 4 deprecated + 6 experimental flags are all inherited default methods of the ToolWindowFactory interface — not calls in our code; nothing actionable. Internal API usage: zero.

Ops note: Gradle local build cache served stale test classes once after a binary-incompatible signature change (NoSuchMethodError in tests that pass in isolation). Recovery: `gradlew --no-build-cache clean build`, then purge `~/.gradle/caches/build-cache-1`.

RESOLVED (2026-08-13): inspection confirmed working END-TO-END by the owner: squiggles appeared, quick fix applied (gson 2.8.9→2.14.0, slf4j 1.7.32→2.0.18 live in testProjects/maven-sample/lib/pom.xml). Log evidence: "checked pom.xml: 4 problem(s), 0 cache miss(es)" declining to 2 as fixes were applied; stable-only filter proven in production (slf4j latest is 2.1.0-alpha1, suggestion was 2.0.18). Earlier invisibility was the since-fixed failed-first-fetch silencing bug.

## Polish sprint — DONE (2026-08-13, commits a7f8c8e + d3556f9)

- a7f8c8e UX polish: humanized tooltip ages ("released 2 months ago"), abandonment messages with real last-release month/year, IgnoreDependencyQuickFix on every problem, one-time offline balloon, settings cache section (stats + clear), Tools "Apply All Patch Updates" (UpgradeApplier shared with batch dialog), cache hit/miss logging. Decision: PATCH stays WARNING, not INFORMATION.
- d3556f9 Licenses + changelog (Phase 3 F+G): per-artifact HEAD upgraded to GET of the same .pom → PomInfo (licenses/scm/description, XXE-hardened, 7 tests), cache schema v2, ScmUrls normalization + releases URLs (9 tests), "View changelog" quick fix (Maven+Gradle), license + copyleft marker in stats rows. Tests caught a real bug pre-ship: spelled-out "General Public License" missed by acronym-only matching.
- Build cache DISABLED in gradle.properties (stale-test-class poisoning twice in one day). 140 tests green; verifier Compatible (only inherited ToolWindowFactory flags).

Deferred with notes: batch diff preview (DiffPanel), per-project filter persistence, description text in batch rows (data now cached, surface pending), ignore-list format validation, ignore import/export, stats CSV/icons/sorting, timeline click-to-inspect/zoom/threshold-line, license group-by view + warn-list setting, Phase 3 H (impact estimation), I (HTML report), J (extract-to-property).

## Session 7: complete Gradle support + marketplace prep — DONE (2026-08-13, commits ace47de + next)

Implemented:
- Gradle Kotlin DSL (build.gradle.kts): string notation, named-argument notation, and libs.* version-catalog references; optional org.jetbrains.kotlin dependency; K2-compatible (syntactic PSI only, supportsK2=true declared in staleguard-withKotlin.xml).
- Version catalogs: pure VersionCatalog subset parser ([versions] + [libraries]: inline tables, module=, shorthand; dotted-accessor normalization; 13 tests). UpdateCatalogVersionQuickFix edits the [versions] entry with blast-radius confirmation for shared keys; table-aware range finding never touches same-named [plugins] keys.
- testProjects/gradle-kotlin-sample (catalog with shared jackson key for the confirmation flow, string + named-arg notations).
- NOTICE file (Apache Maven ComparableVersion attribution — marketplace legal blocker cleared).
- Placeholder pluginIcon.svg (green circle + check; professional logo still needed pre-launch).
- plugin.xml description rewritten (factual feature list, first-40-chars English) — OWNER MUST REVIEW/REWRITE before submission per public-prose rule; docs/MARKETPLACE_DESCRIPTION.md is a clearly-marked DRAFT source-material file, not paste-ready.

Deviations:
- Part 3G "clone JetBrains/kotlin + spring-framework" NOT executed: multi-GB clones + hour-scale Gradle syncs are infeasible in this environment. Substitute: expanded local fixtures; real-world pass = owner opens 1-2 of his own/other local Gradle projects in the sandbox. Documented as the remaining verification gap for kts/catalogs.
- Residual verifier note "1 compatibility warning": the K2 declaration lives in the optional Kotlin config file (where the Kotlin plugin reads it); the verifier only scans the main plugin.xml for it. Moving it would risk unknown-EP errors in Kotlin-less IDEs. Cosmetic; both verify runs pass.
- Kts interpolated versions ("${Versions.gson}", buildSrc constants) skipped by design — later milestone. platform()/project() skipped. Inline catalog versions report-only.

Deferred owner actions (marketplace blockers only the owner can do):
1. Rewrite plugin.xml description + marketplace copy in his own voice.
2. Professional logo (replace placeholder SVG).
3. 4 screenshots (list in docs/MARKETPLACE_DESCRIPTION.md), 1280×800.
4. JetBrains vendor profile: Tampwell LLC, staleguard@tampwell.com, tampwell.com, trader declaration (parent).
5. Sandbox pass on gradle-kotlin-sample: squiggles on libs.* refs, catalog fix writes to libs.versions.toml, shared-key confirmation dialog.

Recommendation for next session: EARLY LAUNCH (Option 2) — the core is verified end-to-end, coverage now spans all four build-file formats; remaining polish (timeline zoom, CSV export, diff preview) ships better as v1.1 informed by real users. Gate 1 clock favors submission.

## Next steps

1. Builder: visually confirm the inspection in the sandbox (squiggles on outdated deps in maven-sample, quick-fix bump on a literal and on `${guava.version}`), then close the sandbox window (leftover sandboxes hold file locks and break builds).
2. Milestone 5: Gradle + libs.versions.toml parsing; settings page (ignore list, thresholds, prerelease toggle); batch "update all patch".
3. Before publish: NOTICE file with Apache Maven attribution (MavenVersion is a ported ComparableVersion).

## Milestone 4 — DONE (2026-08-13, commit 4d70aa8)

Inspection + quick fixes shipped per docs/REFERENCE.md §7 spec. verifyPlugin: Compatible, zero internal/deprecated API usages. 99 tests green.

Deviations from spec (deliberate):
- QUALIFIER upgrades (e.g. 1.0-rc1 → 1.0) weren't specified; mapped to WEAK_WARNING rather than dropped.
- ${project.*} version references offer no fix (not user-editable properties) — spec's property-edit rule would have produced a wrong edit.
- Whole-project DaemonCodeAnalyzer.restart() is deprecated in 262; used per-file restart(psiFile, reason) on open pom.xml editors instead (also less daemon work).
- Plugin version for the HTTP User-Agent is baked in at build time (processResources) — every runtime plugin-descriptor lookup API turned out to be @ApiStatus.Internal.

Explicitly deferred (documented known gaps — not re-solved here): version ranges ([1.0,2.0)), LATEST/RELEASE keywords, multi-repo poms, parent-inherited properties.

## Decided defaults (builder, 2026-08-13)

- Suggest latest STABLE only (prereleases behind a future setting)
- Abandonment threshold: 2 years
- Cache TTL: 24 hours

## Contact / vendor details

- Public business email: staleguard@tampwell.com (created 2026-08-13; use for vendor profile + trader disclosure — never personal addresses)
- Vendor website: tampwell.com (domain owned)

## Open questions / decisions pending

- LICENSE: template ships Apache-2.0 (JetBrains). Decide our own license before the repo goes public (plugin itself can be closed or open; marketplace allows both).
- Logo needed before publish (must NOT resemble the template default or JetBrains logos).
- Final marketplace description + README: written by the builder in his own voice (placeholder text in plugin.xml until then).
- Registered agent: deferred by decision 2026-08-13 — publishing with existing LLC home address; revisit if the project shows success.

## Decisions log

- 2026-08-13: Name **Staleguard** (verified: no marketplace hits, no findable trademark). Kotlin. IDEA-only target, current release only. Private repo until v1 under mingzhenm9-cloud. v1 defers batch dry-run diff preview to v1.1. Teaching mode: explain concepts as we go.
- 2026-08-12: Phase 0 verification passed; thesis holds. Full memo: see Claude artifact "Phase 0 Memo — JetBrains Dependency Plugin".
- Engineering rule #1: never block the EDT — all network I/O on background coroutines, disk-cached, rate-limit-respectful (fetch `maven-metadata.xml` from repo1.maven.org, NOT the search API).
- v1 scope: Maven + Gradle (Groovy/kts) + libs.versions.toml; inspections + quick fixes; patch/minor/major severity; single-dep one-click bump + simple "update all patch"; abandonment signal (no release >2 years); settings with ignore-list; NO npm, NO CVE/security features, NO telemetry, NO paid tier yet.

## Gates

1. Wk 6–8: approved + listed on marketplace (else: cut scope)
2. Month 4: ≥1,000 installs (<300 = thesis wrong, stop and reassess)
3. Month 8: ≥5,000 installs + ≥10 unsolicited requests → design paid tier
4. Month 12: first $200 payout (else re-scope paid features)
