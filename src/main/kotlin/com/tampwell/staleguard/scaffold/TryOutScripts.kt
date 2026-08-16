package com.tampwell.staleguard.scaffold

/**
 * Generates runnable try-out scripts for a dependency — the fastest path from
 * "Staleguard says 2.0.18 is current" to code that actually uses the library.
 * Pure string logic; the action layer owns files and dialogs.
 *
 * Each language uses its own native dependency wiring where one exists
 * (JBang //DEPS, Groovy @Grab), so the generated script resolves the library
 * itself — no build file needed.
 */
object TryOutScripts {

    enum class Lang(val id: String, val extension: String, val display: String) {
        JAVA("java", "java", "Java"),
        JBANG("jbang", "java", "Java (JBang script)"),
        KOTLIN("kotlin", "kt", "Kotlin"),
        JSHELL("jshell", "jsh", "JShell session"),
        GROOVY("groovy", "groovy", "Groovy (@Grab)"),
    }

    fun fileName(artifactId: String, lang: Lang): String {
        val base = "try-" + artifactId.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        return when (lang) {
            Lang.JAVA, Lang.JBANG, Lang.KOTLIN -> className(artifactId) + "." + lang.extension
            Lang.JSHELL, Lang.GROOVY -> base + "." + lang.extension
        }
    }

    fun className(artifactId: String): String {
        val cleaned = artifactId.split(Regex("[^A-Za-z0-9]")).filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
        val name = if (cleaned.isEmpty() || !cleaned.first().isLetter()) "Try$cleaned" else "Try$cleaned"
        return name
    }

    fun render(lang: Lang, groupId: String, artifactId: String, version: String): String {
        val gav = "$groupId:$artifactId:$version"
        val cls = className(artifactId)
        return when (lang) {
            Lang.JBANG -> """
                ///usr/bin/env jbang "${'$'}0" "${'$'}@" ; exit ${'$'}?
                //DEPS $gav

                // Run with: jbang $cls.java   (https://jbang.dev — no build file needed)
                public class $cls {
                    public static void main(String[] args) {
                        System.out.println("$gav is on the classpath.");
                        // TODO: import the library's classes and try it here.
                    }
                }
            """.trimIndent()

            Lang.JAVA -> """
                // Try-out for $gav (version current per Staleguard).
                // Add the dependency to your build, or compile directly:
                //   javac -cp $artifactId-$version.jar $cls.java
                public class $cls {
                    public static void main(String[] args) {
                        System.out.println("Trying $gav");
                        // TODO: import the library's classes and try it here.
                    }
                }
            """.trimIndent()

            Lang.KOTLIN -> """
                // Try-out for $gav (version current per Staleguard).
                // Gradle: implementation("$gav")
                fun main() {
                    println("Trying $gav")
                    // TODO: import the library's classes and try it here.
                }
            """.trimIndent()

            Lang.JSHELL -> """
                // Try-out session for $gav (version current per Staleguard).
                // Run with: jshell --class-path $artifactId-$version.jar ${fileName(artifactId, Lang.JSHELL)}
                System.out.println("Trying $gav")
                // TODO: import the library's classes and experiment line by line.
            """.trimIndent()

            Lang.GROOVY -> """
                // Try-out for $gav — @Grab downloads it on first run, no build file.
                @Grab('$gav')
                import groovy.transform.Field

                println "Trying $gav"
                // TODO: import the library's classes and try it here.
            """.trimIndent()
        } + "\n"
    }
}
