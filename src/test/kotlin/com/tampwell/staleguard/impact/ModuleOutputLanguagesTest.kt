package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ModuleOutputLanguagesTest {

    @Test
    fun `a Gradle source set gathers every language's class directory`() {
        val build = Files.createTempDirectory("module").resolve("build/classes")
        listOf("java/main", "kotlin/main", "groovy/main", "kotlin/test").forEach {
            Files.createDirectories(build.resolve(it))
        }

        val main = ModuleOutputs.languageDirectories(build.resolve("java/main"))

        assertEquals(
            listOf(build.resolve("java/main"), build.resolve("groovy/main"), build.resolve("kotlin/main")),
            main,
        )
        // Test output never leaks into production.
        assertEquals(
            listOf(build.resolve("kotlin/test")),
            ModuleOutputs.languageDirectories(build.resolve("kotlin/test")),
        )
    }

    @Test
    fun `any other layout is taken exactly as recorded`() {
        val maven = Files.createTempDirectory("module").resolve("target/classes")
        Files.createDirectories(maven)
        assertEquals(listOf(maven), ModuleOutputs.languageDirectories(maven))

        val intellij = Files.createTempDirectory("project").resolve("out/production/app")
        Files.createDirectories(intellij)
        assertEquals(listOf(intellij), ModuleOutputs.languageDirectories(intellij))
    }
}
