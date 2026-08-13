# Gradle Kotlin DSL verification results

Date: 2026-08-13 · Commit under test: 77fb6c4 (+ session fixes) · Fixture: testProjects/gradle-kotlin-sample

Two verification channels, honestly separated: MACHINE = evidence collected
programmatically (sandbox log lines, cache files, file diffs) by the agent,
which cannot operate the IDE's GUI. HUMAN = visual checks only the owner can
perform.

## Machine-verified (agent)

- [ ] `Staleguard: checked build.gradle.kts: N problem(s)` appears in the
      sandbox idea.log after the file is opened  → *pending: watcher armed*
- [ ] Version-cache files appear for the kts-only coordinates
      (jackson-databind, jackson-annotations, commons-collections, guava,
      junit-jupiter) → *pending*
- [ ] After the owner applies the catalog quick fix: gradle/libs.versions.toml
      diff shows `gson = "<new stable>"` → *pending*
- [x] Pure-core behavior locked by tests: catalog parsing/resolution/edit
      ranges (13), notation parsing (7), 153 total green.

## Human verification script (owner)

Corrected expectations (the work order's numbers drifted from the fixture):

1. Open build.gradle.kts. Squiggles need only the version lookups (a few
   seconds) — Gradle sync is NOT required by Staleguard's inspection.
2. Expect problems on: libs.gson (minor → 2.14.0), libs.slf4j.api (major),
   libs.commons.collections (patch + abandonment), the guava string literal,
   and the junit-jupiter named-argument version.
   - [ ] confirmed
3. Alt+Enter on libs.gson → "Update catalog version 'gson' to 2.14.0" →
   apply → libs.versions.toml now reads gson = "2.14.0".
   - [ ] confirmed
4. Alt+Enter on libs.jackson.databind → confirmation dialog states
   **2 libraries** use version 'jackson' (databind + annotations; the work
   order's "3" was wrong). Yes → both entries' shared key updated.
   - [ ] confirmed
5. Ignore fix: Alt+Enter → "Ignore <coords> in Staleguard" → squiggle gone
   after re-highlight.
   - [ ] confirmed

## Known limitations (documented, not bugs)

- Catalog quick fix does NOT auto-trigger a Gradle re-sync (deliberate v1
  scope; IDE prompts for sync on file change anyway).
- Statistics/Timeline tool window currently aggregates MAVEN modules only —
  Gradle rows are a v1.1 item (tracked in PROGRESS.md).
- Interpolated kts versions ("${'$'}{Versions.gson}", buildSrc constants) are
  skipped by design.

## Overall assessment

Status: PENDING OWNER PASS — machine evidence to be appended below when the
watcher fires.
