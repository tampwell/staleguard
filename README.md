# Staleguard

An IntelliJ IDEA plugin that shows outdated Maven and Gradle dependencies directly in the editor.

**[Install from the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33571-staleguard)** - or in the IDE: Settings -> Plugins -> Marketplace -> search "Staleguard".

## What it does

- Warns on outdated dependency versions with major / minor / patch severity
- One-click version updates via <kbd>Alt</kbd>+<kbd>Enter</kbd>
- Edits Maven `<properties>` and Gradle version catalogs correctly, rather than inlining literals
- Flags dependencies whose newest release is years old
- Batch update dialog with a per-dependency confidence score
- Shows dependency licenses, with a marker for copyleft terms
- Links to a project's release notes when its POM declares an SCM URL

Supported build files: `pom.xml`, `build.gradle`, `build.gradle.kts`, `gradle/libs.versions.toml`.

Suggests stable releases only by default. Prereleases are available behind a setting.

## Privacy

No telemetry, no accounts, no external services beyond Maven Central. Version data is
cached on disk for 24 hours and revalidated with ETags. An offline mode is available in
settings.

## Requirements

IntelliJ IDEA 2025.2 or newer.

## Building from source

```bash
./gradlew build          # compile and run tests
./gradlew runIde         # launch a sandbox IDE with the plugin
./gradlew verifyPlugin   # binary compatibility check
./gradlew buildPlugin    # produce the distributable ZIP
```

Requires JDK 21.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

`MavenVersion.kt` is a Kotlin port of Apache Maven's `ComparableVersion`, so that version
ordering matches Maven exactly; attribution is in the NOTICE file.
