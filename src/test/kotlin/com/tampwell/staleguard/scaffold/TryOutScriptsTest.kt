package com.tampwell.staleguard.scaffold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TryOutScriptsTest {

    @Test
    fun `jbang script wires the dependency via DEPS`() {
        val script = TryOutScripts.render(TryOutScripts.Lang.JBANG, "org.slf4j", "slf4j-api", "2.0.18")
        assertTrue(script.contains("//DEPS org.slf4j:slf4j-api:2.0.18"))
        assertTrue(script.contains("class TrySlf4jApi"))
    }

    @Test
    fun `groovy script uses Grab so it needs no build file`() {
        val script = TryOutScripts.render(TryOutScripts.Lang.GROOVY, "com.google.code.gson", "gson", "2.14.0")
        assertTrue(script.contains("@Grab('com.google.code.gson:gson:2.14.0')"))
    }

    @Test
    fun `class name from artifact id is a legal identifier`() {
        assertEquals("TrySlf4jApi", TryOutScripts.className("slf4j-api"))
        assertEquals("TryJacksonDatabind", TryOutScripts.className("jackson-databind"))
    }

    @Test
    fun `file names match language conventions`() {
        assertEquals("TryGson.java", TryOutScripts.fileName("gson", TryOutScripts.Lang.JAVA))
        assertEquals("TryGson.kt", TryOutScripts.fileName("gson", TryOutScripts.Lang.KOTLIN))
        assertEquals("try-gson.jsh", TryOutScripts.fileName("gson", TryOutScripts.Lang.JSHELL))
        assertEquals("try-gson.groovy", TryOutScripts.fileName("gson", TryOutScripts.Lang.GROOVY))
    }

    @Test
    fun `every language renders with the gav present`() {
        for (lang in TryOutScripts.Lang.values()) {
            val script = TryOutScripts.render(lang, "g.example", "thing", "1.0")
            assertTrue(lang.name, script.contains("g.example:thing:1.0"))
            assertFalse(lang.name, script.contains("{{"))
        }
    }
}
