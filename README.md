# Staleguard

An IntelliJ IDEA plugin that shows outdated Maven and Gradle dependencies directly in the editor.

**[Install from the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33571-staleguard)** - or in the IDE: Settings -> Plugins -> Marketplace -> search "Staleguard".

## What it does

- Warns on outdated dependency versions with major / minor / patch severity
- Flags versions with known vulnerabilities, naming the CVE, its severity, and the first fixed version (data from the [OSV database](https://osv.dev))
- One-click version updates via <kbd>Alt</kbd>+<kbd>Enter</kbd>
- Edits Maven `<properties>` and Gradle version catalogs correctly, rather than inlining literals
- Flags dependencies whose newest release is years old
- Shows the release notes between your version and the suggested one, with breaking-change and security banners
- Team rules in a committed `.staleguard.toml`: shared ignore patterns and a license policy (deny or warn by license name); Renovate and Dependabot ignore rules are honored automatically
- Batch update dialog with per-dependency confidence scores; security fixes come preselected
- Checks parent POM freshness, since one edit there updates every managed dependency
- Statistics and timeline tool window with license visibility, vulnerability counts, and Markdown/CSV/PNG export
- Generates runnable try-out scripts (Java, JBang, Kotlin, JShell, Groovy) for any library

Supported build files: `pom.xml`, `build.gradle`, `build.gradle.kts`, `gradle/libs.versions.toml`.

Suggests stable releases only by default. Prereleases are available behind a setting.

## Privacy

No telemetry, no accounts. Network traffic is metadata lookups for the artifacts in your
build files: version data from Maven Central, Google's Maven repository, and the Gradle
Plugin Portal, and vulnerability data from osv.dev. Everything is cached on disk for 24
hours and revalidated with ETags. Vulnerability checks have their own switch in settings,
and an offline mode disables all network contact.

## Requirements

IntelliJ IDEA or Android Studio on platform 2024.3 or newer.

## Building from source

```bash
./gradlew build          # compile and run tests
./gradlew runIde         # launch a sandbox IDE with the plugin
./gradlew verifyPlugin   # binary compatibility check
./gradlew buildPlugin    # produce the distributable ZIP
```

Requires JDK 21.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

`MavenVersion.kt` is a Kotlin port of Apache Maven's `ComparableVersion`, so that version
ordering matches Maven exactly; attribution is in the NOTICE file.
