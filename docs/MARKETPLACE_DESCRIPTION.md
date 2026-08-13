# Marketplace description — DRAFT

> **STATUS: DRAFT WRITTEN BY THE CODING ASSISTANT.**
> Per project rule, ALL public-facing prose must be rewritten by the owner in
> his own voice before submission. This file is source material: the facts are
> verified, the wording is disposable. Do not paste as-is.

## Facts that are safe to state (all verified in-code)

- Shows outdated Maven/Gradle dependencies directly in the editor with
  severity-labeled warnings (major / minor / patch) and one-click fixes.
- Suggests stable releases only by default (no alphas/RCs/snapshots);
  prereleases available via a setting.
- Abandonment detection: flags libraries whose newest release is older than a
  configurable threshold (default 2 years), with the actual last-release date.
- Property-aware: fixes edit Maven `<properties>` definitions and Gradle
  version-catalog `[versions]` entries — never inline literals over
  references — with a confirmation when one property/version key drives
  multiple dependencies.
- Batch updates with a severity-grouped preview; one-click "apply all patch".
- Statistics tool window (per-module counts, licenses with copyleft marker)
  and a dependency-age timeline with PNG export.
- Changelog links derived from artifact POM `<scm>` tags (GitHub/GitLab).
- Coverage: pom.xml, build.gradle (Groovy), build.gradle.kts (Kotlin DSL),
  gradle/libs.versions.toml version catalogs.
- Privacy: no telemetry, no accounts. Talks only to Maven Central
  (metadata + POM files), with a 24h disk cache, ETag revalidation, and a
  never-block-the-editor architecture.

## Positioning notes (for the owner's rewrite)

- Primary audience: users of the deprecated JetBrains Package Search
  (3.16M installs, service shut down April 2025) and of the two abandoned
  dependency-helper plugins (~620K combined).
- Differentiators vs. Maven Dependency Checker (the one active rival):
  in-editor squiggles (not a report dialog), real write-back fixes,
  Gradle + Kotlin DSL + catalogs, abandonment signal, license visibility.
- Suggested claims to AVOID: anything about security/CVEs (out of scope,
  crowded space), anything AI, download-count comparisons.

## Screenshots needed (1280×800, capture after final UI pass)

1. pom.xml with a minor-upgrade squiggle + hover tooltip showing the
   recommendation and release age.
2. Batch update dialog, several dependencies selected, property impact label
   visible.
3. Staleguard tool window, Statistics tab: module rows with license +
   copyleft marker.
4. Timeline tab with the age color-coding and legend visible.
