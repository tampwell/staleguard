package com.tampwell.staleguard.gradle

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * The kts PSI walk against real Kotlin PSI (KtPsiFactory needs no script
 * resolution — the collector reads structure only). This is the deliberate
 * heavyweight test: kts PSI shapes are underdocumented and regressions here
 * are silent coverage loss in the editor.
 */
class KtsDependencyCollectorPlatformTest : BasePlatformTestCase() {

    private fun collect(
        body: String,
        properties: Map<String, String> = emptyMap(),
    ): List<KtsDeclared> {
        val file = KtPsiFactory(project).createFile(
            "build.gradle.kts",
            """
            dependencies {
            $body
            }
            """.trimIndent(),
        )
        return KtsDependencyCollector.collect(
            file, VersionCatalog.EMPTY, null,
            gradleProperties = properties,
            gradlePropertiesPath = if (properties.isEmpty()) null else "/fake/gradle.properties",
        )
    }

    fun `test plain string notation still collects`() {
        val declared = collect("""implementation("com.google.code.gson:gson:2.10.1")""").single()
        assertEquals("com.google.code.gson", declared.group)
        assertEquals("gson", declared.name)
        assertEquals("2.10.1", declared.version)
        assertFalse(declared.isPlatform)
    }

    fun `test platform wrapper is unwrapped and flagged`() {
        val declared = collect(
            """implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.0"))""",
        ).single()
        assertEquals("org.springframework.boot", declared.group)
        assertEquals("spring-boot-dependencies", declared.name)
        assertEquals("3.2.0", declared.version)
        assertTrue(declared.isPlatform)
    }

    fun `test enforcedPlatform wrapper is unwrapped and flagged`() {
        val declared = collect(
            """testImplementation(enforcedPlatform("org.junit:junit-bom:5.10.0"))""",
        ).single()
        assertEquals("junit-bom", declared.name)
        assertTrue(declared.isPlatform)
    }

    fun `test platform declaration is reported exactly once`() {
        val all = collect("""implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.0"))""")
        assertEquals(1, all.size)
    }

    fun `test kotlin notation with explicit version maps to kotlin coordinates`() {
        val declared = collect("""implementation(kotlin("reflect", "1.9.24"))""").single()
        assertEquals("org.jetbrains.kotlin", declared.group)
        assertEquals("kotlin-reflect", declared.name)
        assertEquals("1.9.24", declared.version)
        assertFalse(declared.isPlatform)
    }

    fun `test kotlin notation with named version argument`() {
        val declared = collect("""implementation(kotlin("test", version = "1.9.24"))""").single()
        assertEquals("kotlin-test", declared.name)
        assertEquals("1.9.24", declared.version)
    }

    fun `test versionless kotlin notation is skipped`() {
        assertEmpty(collect("""implementation(kotlin("stdlib"))"""))
    }

    fun `test named-argument notation still collects`() {
        val declared = collect(
            """implementation(group = "org.slf4j", name = "slf4j-api", version = "2.0.13")""",
        ).single()
        assertEquals("slf4j-api", declared.name)
        assertEquals("2.0.13", declared.version)
    }

    fun `test interpolated platform version is skipped`() {
        assertEmpty(collect("""implementation(platform("org.example:bom:${'$'}{bomVersion}"))"""))
    }

    fun `test project and files calls stay ignored`() {
        assertEmpty(collect("""implementation(project(":core"))${'\n'}implementation(files("libs/local.jar"))"""))
    }

    fun `test interpolated version resolves from gradle properties`() {
        val declared = collect(
            """implementation("com.example:lib:${'$'}{libVersion}")""",
            properties = mapOf("libVersion" to "2.10.1"),
        ).single()
        assertEquals("com.example", declared.group)
        assertEquals("lib", declared.name)
        assertEquals("2.10.1", declared.version)
        assertTrue(declared.fix("3.0.0") is UpdateGradlePropertyQuickFix)
    }

    fun `test simple-name interpolation also resolves`() {
        val declared = collect(
            """implementation("com.example:lib:${'$'}libVersion")""",
            properties = mapOf("libVersion" to "2.10.1"),
        ).single()
        assertEquals("2.10.1", declared.version)
    }

    fun `test unknown property stays skipped`() {
        assertEmpty(collect("""implementation("com.example:lib:${'$'}{nope}")""", properties = mapOf("other" to "1")))
    }

    fun `test complex interpolation stays skipped`() {
        assertEmpty(
            collect(
                """implementation("com.example:lib:${'$'}{rootProject.extra["v"]}")""",
                properties = mapOf("v" to "1"),
            ),
        )
    }

    fun `test interpolated platform version resolves and keeps the flag`() {
        val declared = collect(
            """implementation(platform("org.example:bom:${'$'}{bomVersion}"))""",
            properties = mapOf("bomVersion" to "1.0.0"),
        ).single()
        assertEquals("1.0.0", declared.version)
        assertTrue(declared.isPlatform)
    }
}
