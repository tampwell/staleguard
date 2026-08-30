<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Staleguard Changelog

## [Unreleased]

## [2.1.0] - 2026-08-30

### Added
- Your own code joins the classpath linkage check. Every module's compiled classes are audited exactly like the jars, so the calls YOUR code makes into a conflicted classpath, which is where a version conflict actually bites, are reported with the same precision. The verdict states whether your code was included and as of which build; a module with no compiled output is named, and a clean claim is never made while any module is unchecked, because "your code is clean" is a promise and promises need the whole project built.
- The classpath check now tells you the fix. For every jar whose calls cannot link, Staleguard finds the earliest released version that satisfies everything the classpath asks of it, by actually downloading candidates and checking, and says "bump jackson-core to 2.15.0 or later" right in the findings. When no released version satisfies every call, it says that instead. Suggestions are computed, never guessed: capped at eight probes per jar through the same repositories, mirrors and credentials your other lookups use, never a version your .staleguard.toml pins forbid, and a cold cache means no suggestion this run rather than a surprise network fan-out.
- The classpath check now watches. After a Maven or Gradle sync changes your dependencies, the check re-runs by itself in the background and notifies you only when the change introduced a NEW problem that was not there before the sync. The first run establishes a baseline silently, known findings never re-notify, and one click opens the full report. Automatic means local: the background re-check scans and resolves, nothing more, and the whole behavior sits behind one setting, on by default.
- Each module's REAL classpath is audited, not a project-wide union. A union is a set no JVM ever loads: it stays silent when a class is missing from the module that needs it but happens to sit on a sibling module's classpath. Every module is now resolved against exactly its own jars and module outputs, findings name the classpaths they hold in, and the same break in several places is one finding rather than several.
- Production and test classpaths are audited separately, because they fail separately: a mixed JUnit platform kills the test runner while production is fine, and a test-only jar must never pollute a production verdict. Every module gets both audits, your compiled test classes join when a build has produced them, and a finding marked "in app (tests)" tells you at a glance it is a test-runtime problem. Test dependencies are treated as non-transitive, exactly as Maven and Gradle treat them.
- Shadowed class detection. When the same class exists in more than one jar with DIFFERING APIs, classpath order silently decides which copy runs. These conflicts are reported by the jar whose copy wins and the jars it shadows, grouped by the jar set in conflict rather than per class, because one conflicted pair carrying forty duplicated classes is one problem with one fix. Byte-identical copies and compiler-synthetic differences stay silent, priced against a real 141-jar classpath where 271 duplicated names contained exactly one differing group.

### Changed
- The clean verdict in a multi-module project now says what was actually proven: every call resolves on each module's own classpath, a strictly stronger statement than any union check could make.

## [2.0.0] - 2026-08-28

### Added
- Classpath linkage check. One click in the tool window resolves every call every jar makes against what the rest of your resolved classpath actually declares, and reports the calls that cannot link: the NoSuchMethodError and NoClassDefFoundError a version conflict produces when dependency resolution evicted the version somebody was compiled against. You see them before the app runs, named by the jar whose calls fail and the jar whose version has to move.
- The check reads constant pools, not bytecode bodies, so a full classpath resolves in seconds: a 28 jar, 125,000 class product classpath checks 977,000 references in under four seconds, and repeat runs re-read nothing that has not changed.
- Precision over recall, throughout. JDK membership is answered by your project SDK, so the verdict matches the Java you actually compile against. A missing class only counts when its package is partially present, which is what separates a version conflict from an optional dependency. Groovy runtime callers are excluded because Groovy dispatches through its own runtime. On a real product classpath this leaves three findings in 977,000 references, and two of them are real.
- Results copy as Markdown, because a classpath conflict is a team conversation: the paste names the member, the caller, and the jar whose version has to move.

## [1.9.0] - 2026-08-27

### Added
- Copy as Markdown on the upgrade impact report: one click puts the verdict, the removed members your code calls, and every call site on the clipboard, ready to paste into a pull request or a team chat. An incomplete analysis exports as exactly that, never as a conclusion.
- The tool window agrees with the editor: once you have checked an upgrade, its row says [checked: safe] or [checked: breaks N members you call].
- Check Impact for Selected in the batch update dialog: one click compares the binaries for every selected upgrade, writes the verdict into each row, and deselects anything measured as breaking, so a bulk apply can never silently include an upgrade known to break the build. Cancelling keeps the verdicts that finished.
- The impact comparison cache is visible in Settings next to the version cache, with its own clear button.

### Fixed
- platform() and enforcedPlatform() BOM entries no longer offer the upgrade impact check. A BOM is a pom, not a jar, so the check could only ever fail after a pointless download.

## [1.8.0] - 2026-08-25

### Added
- Upgrade impact analysis. On any outdated dependency, Alt+Enter now offers "Check upgrade impact": Staleguard compares the two versions' actual binaries and tells you whether your own code calls anything the new version removes, with every call site listed and one click away. The answer is the one an upgrade hint cannot give you, and the one that decides whether an upgrade is a version bump or an afternoon.
- Removals are judged the way the JVM judges them. Resolution walks superclasses and interfaces, so a method that simply moved up into a supertype is not reported as a break. On jackson-databind 2.13 to 2.19 that is the difference between 197 alarming differences and 182 real ones.
- Works the same on Maven, Gradle, Kotlin DSL and version catalogs, because it compares two artifacts rather than two resolved dependency graphs. Android .aar dependencies are unpacked and compared through their classes.jar. Private repositories, mirrors and credentials are reused from your existing configuration.
- Results are cached per version pair, so asking a second time costs no download. Nothing runs during highlighting: the check downloads a jar, so it only ever happens when you ask for it.
- The answer follows you back to the editor. Once you have checked an upgrade, the warning on that dependency says what the check found, and the confidence score in the batch update dialog reflects it: a measured break caps the score no matter how safe the version distance looks. A check that could not finish claims nothing at all, so "checked" never appears next to an upgrade that was not fully checked.

## [1.7.0] - 2026-08-22

### Added
- SBOM export: the tool window exports a CycloneDX 1.5 JSON bill of materials covering every resolved dependency, with Maven purls, the license names published in each POM, and the known OSV vulnerabilities affecting your declared versions linked back to the components they affect. The output validates against the official CycloneDX schema and imports into Dependency-Track and anything else that reads CycloneDX, so a compliance request no longer means a CI pipeline.

## [1.6.0] - 2026-08-22

### Added
- Private repository credentials: hosts you list under Settings get HTTP Basic authentication for version lookups, so dependencies from a company Nexus or Artifactory resolve like any other. Secrets are stored in the IDE password safe (your OS keychain) and are never written to settings files, logs, or exported reports, and they are only ever sent to hosts you explicitly configure. Artifactory API keys and Nexus user tokens work in the password field.
- Import from Maven settings.xml: one click lists the server entries in ~/.m2/settings.xml and lets you pick which credentials to bring into the password safe. Server ids resolve to hosts through your mirrors, profile repositories, and the repositories declared in open projects. Entries encrypted with the Maven master password are listed but never decrypted; those are entered manually.
- Authentication failures are reported honestly: a rejected credential names the host and offers to open Settings, instead of a generic connectivity warning.
- A first-scan confirmation: a project where everything is current used to show nothing at all, since warnings only appear when something is wrong and the status bar hides itself when there is nothing to act on. Staleguard now says so once per project, and never while lookups are still running or any coordinate is unresolved.
- Report an Issue button in the tool window, which opens the GitHub bug form with your IDE and plugin version already filled in.

## [1.5.0] - 2026-08-21

### Added
- Gradle plugins block versions are checked in both DSLs: id("...") version "..." and the kotlin("jvm") shorthand compare against the Plugin Portal, with quick fixes and batch updates.
- Gradle platform() and enforcedPlatform() BOMs are inspected in build.gradle.kts, and scope=import BOMs in Maven dependencyManagement get the same one-edit-updates-everything message as a parent POM.
- Versions defined in gradle.properties resolve ("g:a:${libVersion}"), with a quick fix that edits gradle.properties and warns when the property feeds several declarations. buildSrc object Versions constants resolve read-only.
- kotlin("reflect", "1.9.24") style dependencies resolve and update.
- Maven build plugins (build/plugins and pluginManagement) get freshness and vulnerability checks; abandonment deliberately does not apply to them.
- Version pins: a [pins] table in .staleguard.toml caps suggestions for teams staying on an older major on purpose, for example "org.springframework.boot:*:2.*". Prefix wildcards, Maven ranges, and comparison operators all work; pinned rows are labeled in the tool window.
- Renovate and Dependabot version conditions now count: allowedVersions, ignore version ranges, and no-major-updates rules cap suggestions the same way they cap the bot.
- Staleguard never suggests upgrading into a known vulnerability: when the newest version has advisories and a lower clean version exists, that one is suggested instead.
- Maven mirrors from ~/.m2/settings.xml are respected for Central lookups, with Maven's own mirrorOf matching rules. A blocked central is never probed directly. Toggle in Settings.
- New-advisory alerts: when a dependency that was clean at the last check gains a published advisory, one notification says so.
- Tool window rows name the worst advisory inline, and an up-to-date dependency with a known CVE now gets a row.

### Fixed
- A Dependabot ignore entry with version ranges silenced the whole dependency instead of only those ranges; the same for Renovate rules scoped to major updates.
- Versions defined by parent POM properties (and ${revision}-style CI versions) now resolve in child modules.
- Catalog libraries with nested rich versions ({ strictly = "..." }) now resolve instead of being skipped.
- Batch updates now apply everything the dialog shows, including gradle.properties-backed and plugins-block versions.
- Marketplace compatibility warnings drop to the honest minimum: the compiler no longer emits phantom overrides of deprecated platform methods this code never touches.

## [1.4.0] - 2026-08-21

### Added
- Known-vulnerability alerts: dependencies are checked against the OSV database (osv.dev), and a version with a published CVE gets an editor warning naming the advisory, its severity, and the first fixed version, with a one-click update to that version and a link to the full advisory. The upgrade recommendation escalates to "Update now" while the current version is vulnerable. Cached 24 hours, honors offline mode, and can be turned off in Settings.
- License policy rules in the committed .staleguard.toml: a [licenses] table with deny and warn pattern arrays flags dependencies by their published license, so a team can keep copyleft or source-available licenses out of the build. Off unless the project commits rules.
- Vulnerability counts in the status bar, the statistics tool window, and the batch update dialog, which now shows the same "Update now" recommendation the editor does for vulnerable versions, naming the driving advisory next to it.
- Exported Markdown and CSV reports include an Advisories column, and the dependency age timeline marks vulnerable versions so the PNG snapshot carries the security picture too.
- Vulnerability lookups are batched: one request per project scan instead of one per dependency, so large projects stay fast and polite to the OSV service.
- Security context at every decision point: the Show What Changed dialog opens with the known vulnerabilities in your current version, vulnerability fixes score higher confidence and float up the batch dialog, and security updates come preselected there.

## [1.3.0] - 2026-08-19

### Added
- Library Try-Out Script generator (Tools menu): coordinates to a runnable Java, JBang, Kotlin, JShell, or Groovy script, with the version pre-filled from Staleguard's own data
- Parent POM freshness: spring-boot-starter-parent and other platform parents are flagged when outdated - one edit updates every managed dependency
- Show What Changed: in-IDE release notes for every version between yours and the suggested one, with a warning banner when the notes mention breaking changes
- Status-bar dependency counter (visible only when something needs attention; click opens the overview)
- Snapshot pinning warnings: -SNAPSHOT versions are flagged as reproducibility risks in all supported build files
- Team ignore rules: a committed .staleguard.toml ([ignore] dependencies, group:artifact patterns with wildcards) applies to everyone who opens the project
- Renovate and Dependabot alignment: ignore rules in renovate.json and .github/dependabot.yml are honored, so editor hints never contradict the team bot
- Project-declared repositories (anonymous read): corporate mirrors and hosts like JitPack declared in build files are consulted as last-resort lookup sources

### Changed
- Minimum supported IDE version lowered from 2025.2 to 2024.3, so current stable Android Studio and older IntelliJ installs can use the plugin (verified against every line from 2024.3 through 2026.2)

## [1.2.0] - 2026-08-18

### Added

- Freshness checks inside `libs.versions.toml` itself: stale `[versions]`
  keys, inline library versions, and `[plugins]` versions are flagged in the
  catalog file, with one-click updates and the same blast-radius confirmation
  for shared version keys
- Google Maven repository support: `androidx.*`, `com.android.*`, and other
  Google-hosted dependencies (Firebase, Play Services) now resolve, so
  Android projects get real version data instead of silence
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
  definitions, and Gradle version-catalog `[versions]` entries, with a
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
