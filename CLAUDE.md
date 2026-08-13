# Staleguard — project conventions

Staleguard is an IntelliJ IDEA plugin for dependency freshness and upgrade hygiene (Maven + Gradle + version catalogs). Kotlin, built on the IntelliJ Platform Gradle Plugin 2.x. Vendor: Tampwell LLC. The builder is learning Kotlin and the IntelliJ SDK — explain each new platform concept briefly the first time it appears in a session.

## Hard rules

- **Never block the EDT.** All network and disk I/O on background coroutines. Any violation is a bug even if it "works".
- **No telemetry, no collection of user data.** The plugin reads build files only.
- **No CVE/vulnerability features** — Snyk/Sonar own that niche; we do freshness only.
- **Version data sources**: `https://repo1.maven.org/maven2/<group-path>/<artifact>/maven-metadata.xml` for Maven Central; `https://plugins.gradle.org/m2/<id-path>/<plugin.id>.gradle.plugin/maven-metadata.xml` for Gradle plugins. Never the Solr search API for lookups. Cache on disk (hours-scale TTL); on HTTP 429, back off — never retry harder.
- **Public words are the builder's.** README, marketplace description, changelog entries visible to users: draft structure at most; the builder writes final copy.
- **Never commit secrets.** No API keys exist in this project; keep it that way.
- Session end = committable, testable state + PROGRESS.md updated.

## Build & verify

- `./gradlew.bat build` — compile + test
- `./gradlew.bat runIde` — sandboxed IDE with the plugin (day-1 smoke test)
- `./gradlew.bat verifyPlugin` — run before every milestone; a plugin that fails verification cannot ship
- JDK 21 (Temurin). Kotlin stdlib is provided by the platform (`kotlin.stdlib.default.dependency = false`) — never add it or kotlinx-coroutines as dependencies.

## Testing priorities

Exhaustively unit-test (plain JUnit, no heavyweight platform fixtures needed): version comparison/ordering (semver + Maven quirks: qualifiers, `1.0-SNAPSHOT`, `1.0.Final`, date-based versions), version-range handling, BOM-managed versions, property-interpolated versions in pom.xml (`${foo.version}`), version-catalog references. These edge cases are where dependency tools embarrass themselves.

## Style

- Kotlin official code style; classes not `object` for extension points (platform manages lifecycle).
- UI surface: inspections (`LocalInspectionTool`) + quick fixes (`LocalQuickFix`), severity-tiered patch/minor/major.
- Keep the dumb-simple rule: parsing, resolution, and comparison logic lives in plain testable Kotlin, isolated from platform APIs wherever possible.
