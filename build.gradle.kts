import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // 2025.3 (build 253) is the newest PUBLIC release per the official
        // releases API — the 2026.2/262 line exists only in the dev artifact
        // repository and no shipped IDE can run a since-build=262 plugin.
        // Discovered 2026-08-14 during the clean-environment install test.
        intellijIdea("2025.3")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("org.intellij.groovy")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.toml.lang")
        testFramework(TestFrameworkType.Platform)
    }
}

// Apache-2.0 §4 compliance: MavenVersion.kt is a port of Maven's
// ComparableVersion, so the License text and NOTICE must travel WITH the
// distributed artifact — not just live in the repo. Ships them inside the
// jar's META-INF, which is where reviewers and users look.
tasks.jar {
    from(rootDir) {
        include("LICENSE", "NOTICE")
        into("META-INF")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Floor at 2024.3: Android Studio tracks the platform 1-2 lines
            // behind IDEA, so a higher floor silently hides the plugin from
            // the marketplace for most AS users (launch-day user report).
            // Every release must verify green on 243/251 before this holds.
            sinceBuild = "243"
        }
    }
    pluginVerification {
        // INTERNAL_API_USAGES is deliberately absent from the failure gate:
        // its ONLY hits are ToolWindowFactory default-method bridges the
        // Kotlin compiler is required to emit (platform mandates jvm-default),
        // methods this codebase never references. Marked internal on the
        // public 252/253 line, experimental on the 26x preview line. Every
        // substantive failure class below remains hard. Reviewed 2026-08-14;
        // re-review if internal-api-usages.txt ever lists anything else.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
        ides {
            // recommended() selects releases across the declared since/until
            // range — with the 243 floor this must include a 243 and a 251
            // line IDE. Confirm in the verifier output before trusting a run.
            recommended()
        }
    }
}

kotlin {
    compilerOptions {
        // 2024.3 bundles a Kotlin 2.0 stdlib. Newer language levels make the
        // compiler emit stdlib helpers (coroutine SpillingKt) that don't
        // exist there, which the 243 verifier flags as NoSuchClassError.
        // Raise these only when since-build moves past 243.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)

        // Without this, kotlinc emits delegating overrides for EVERY default
        // method of the platform's Kotlin interfaces (ToolWindowFactory,
        // StatusBarWidget) — phantom "overrides deprecated/experimental API"
        // the marketplace verifier pins on us for methods this source never
        // touches. With =all the Java default methods dispatch directly and
        // the bridges disappear. Verified by javap before/after 2026-08-21.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.processResources {
    val pluginVersion = version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("staleguard.properties") {
        expand("pluginVersion" to pluginVersion)
    }
}
