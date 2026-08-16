<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Staleguard Changelog

## [Unreleased]

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
