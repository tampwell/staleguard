# Staleguard

An IntelliJ IDEA plugin that tells you which dependencies are out of date, which are
vulnerable, whether upgrading will break your code, and which version conflicts on your
classpath will throw NoSuchMethodError at runtime - then fixes them - directly in the editor.

**[Install from the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33571-staleguard)** - or in the IDE: Settings -> Plugins -> Marketplace -> search "Staleguard".

## What it does

- **Classpath linkage doctor**: resolves every call every jar makes against what your real
  per-module classpaths actually declare (production and tests separately, your own compiled
  code included) and reports the `NoSuchMethodError` / `NoClassDefFoundError` a version
  conflict will produce, before anything runs. Re-runs by itself after every dependency sync
  and notifies only about NEW problems; the verdict lives in the status bar, at the broken
  declaration in the editor, and survives restarts
- **THE FIX, computed and applied**: finds the earliest released version that satisfies every
  call on your classpath by probing real binaries, then one button bumps declared versions
  where they are declared, pins Maven transitives via `dependencyManagement`, and puts the
  exact Gradle `constraints` block on your clipboard
- **Upgrade rehearsal**: before you upgrade, the impact check swaps the candidate jar into
  every module's classpath and reports what gets fixed and what breaks, anywhere
- **Shadowed class detection**: the same class in two jars with differing APIs, where
  classpath order silently decides which copy runs
- Warns on outdated dependency versions with major / minor / patch severity
- Flags versions with known vulnerabilities, naming the CVE, its severity, and the first fixed version (data from the [OSV database](https://osv.dev))
- **Upgrade impact analysis**: compares the two versions' bytecode and reports the removed
  members your own code calls, with navigable call sites. Removal is judged the way the JVM
  links - a method that moved up into a superclass is never a false alarm. Results follow
  you back into the editor warning and copy as Markdown for pull requests
- One-click version updates via <kbd>Alt</kbd>+<kbd>Enter</kbd>
- Edits Maven `<properties>` and Gradle version catalogs correctly, rather than inlining literals
- Flags dependencies whose newest release is years old
- Shows the release notes between your version and the suggested one, with breaking-change and security banners
- Team rules in a committed `.staleguard.toml`: shared ignore patterns and a license policy (deny or warn by license name); Renovate and Dependabot ignore rules are honored automatically
- Batch update dialog with per-dependency confidence scores; security fixes come preselected
- Checks parent POM freshness, since one edit there updates every managed dependency
- Statistics and timeline tool window with license visibility, vulnerability counts, and Markdown/CSV/PNG export
- CycloneDX 1.5 SBOM export, validated against the official schema, ready for Dependency-Track
- Private repositories: credentials for Nexus and Artifactory live in the IDE password safe,
  are sent only to hosts you list, and can be imported from `~/.m2/settings.xml`; Maven
  mirrors from settings.xml are routed exactly as Maven would
- Version pins and ceilings in `.staleguard.toml`, honored by every surface including batch updates
- Generates runnable try-out scripts (Java, JBang, Kotlin, JShell, Groovy) for any library

Supported build files: `pom.xml`, `build.gradle`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, and `buildSrc` version constants. Gradle `plugins {}` blocks, parent POMs, and `platform()`/scope=import BOMs are checked too.

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

## Community

Questions, ideas, and anything that isn't a clear bug: [GitHub Discussions](https://github.com/tampwell/staleguard/discussions). Confirmed bugs belong in [Issues](https://github.com/tampwell/staleguard/issues) so they get tracked.

If Staleguard is useful to you, [a short review on the marketplace](https://plugins.jetbrains.com/plugin/33571-staleguard/reviews) genuinely helps other people find it.
