<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Staleguard Changelog

## [Unreleased]

### Added
- Library Try-Out Script generator (Tools menu): coordinates to a runnable Java, JBang, Kotlin, JShell, or Groovy script, with the version pre-filled from Staleguard's own data
- Parent POM freshness: spring-boot-starter-parent and other platform parents are flagged when outdated - one edit updates every managed dependency
- Show What Changed: in-IDE release notes for every version between yours and the suggested one, with a warning banner when the notes mention breaking changes
- Status-bar dependency counter (visible only when something needs attention; click opens the overview)
- Snapshot pinning warnings: -SNAPSHOT versions are flagged as reproducibility risks in all supported build files
- Team ignore rules: a committed .staleguard.toml ([ignore] dependencies, group:artifact patterns with wildcards) applies to everyone who opens the project
- Renovate and Dependabot alignment: ignore rules in renovate.json and .github/dependabot.yml are honored, so editor hints never contradict the team bot
- Project-declared repositories (anonymous read): corporate mirrors and hosts like JitPack declared in build files are consulted as last-resort lookup sources


### Added

- Freshness checks inside `libs.versions.toml` itself: stale `[versions]`
  keys, inline library versions, and `[plugins]` versions are flagged in the
  catalog file, with one-click updates and the same blast-radius confirmation
  for shared version keys
- Google Maven repository support: `androidx.*`, `com.android.*`, and other
  Google-hosted dependencies (Firebase, Play Services) now resolve — Android
  projects get real version data instead of silence
- Gradle Plugin Portal support: versions in `plugins { }` catalog entries are
  checked through their plugin marker artifacts
- The batch update dialog now covers Gradle build files and version catalogs,
  not just Maven modules
- Statistics tool window: export the current report as Markdown or CSV

### Changed

- Ancient date-stamped versions (for example commons-collections `20040616`)
  are no longer suggested over normal dotted versions
- Tool window data collection moved off the UI thread

## [1.1.0] - 2026-08-16

### Added

- Gradle dependencies now appear in the Staleguard tool window: statistics and
  timeline include `build.gradle` and `build.gradle.kts` files, including
  version-catalog references

### Changed

- "Update version" now appears above "Ignore" in the Alt+Enter menu

## [1.0.0] - 2026-08-15

### Added

- Inline warnings for outdated Maven and Gradle dependencies, with
  major / minor / patch severity and release-age context
- One-click version updates: literal versions, Maven `<properties>`
  definitions, and Gradle version-catalog `[versions]` entries — with a
  confirmation when one property or catalog key drives several dependencies
- Abandonment detection: flags dependencies whose newest release is older
  than a configurable threshold (default two years)
- Batch update dialog with per-dependency confidence scores, and an
  "Apply All Patch Updates" action
- Statistics tool window with per-module counts, license visibility, and a
  dependency age timeline (PNG export)
- Changelog links derived from each dependency's published SCM information
- Settings: stable-only or prerelease suggestions, abandonment threshold,
  ignore list, offline mode, cache management
- Supported build files: `pom.xml`, `build.gradle`, `build.gradle.kts`,
  `gradle/libs.versions.toml`
