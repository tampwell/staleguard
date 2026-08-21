package com.tampwell.staleguard.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclaredRepositoriesTest {

    @Test
    fun `pom repositories are extracted`() {
        val pom = """
            <project>
              <repositories>
                <repository>
                  <id>corp</id>
                  <url>https://nexus.corp.example/repository/maven-public/</url>
                </repository>
                <repository>
                  <id>jitpack</id>
                  <url>https://jitpack.io</url>
                </repository>
              </repositories>
            </project>
        """.trimIndent()
        assertEquals(
            listOf("https://nexus.corp.example/repository/maven-public", "https://jitpack.io"),
            DeclaredRepositories.fromPomXml(pom),
        )
    }

    @Test
    fun `gradle block and call forms are extracted`() {
        val gradle = """
            repositories {
                mavenCentral()
                maven { url = uri("https://nexus.corp.example/repo") }
                maven { url "https://plugins.example.org/m2/" }
                maven("https://jitpack.io")
            }
        """.trimIndent()
        val urls = DeclaredRepositories.fromGradle(gradle)
        assertTrue("https://nexus.corp.example/repo" in urls)
        assertTrue("https://plugins.example.org/m2" in urls)
        assertTrue("https://jitpack.io" in urls)
    }

    @Test
    fun `well-known defaults are excluded`() {
        val gradle = """
            repositories {
                maven("https://repo1.maven.org/maven2")
                maven("https://dl.google.com/dl/android/maven2")
                maven("https://plugins.gradle.org/m2")
            }
        """.trimIndent()
        assertTrue(DeclaredRepositories.fromGradle(gradle).isEmpty())
    }

    @Test
    fun `duplicates collapse`() {
        val gradle = """
            maven("https://jitpack.io")
            maven("https://jitpack.io/")
        """.trimIndent()
        assertEquals(listOf("https://jitpack.io"), DeclaredRepositories.fromGradle(gradle))
    }

    @Test
    fun `extras come last in every router chain`() {
        val central = MavenLayoutSource(MavenRepositoryUrls.MAVEN_CENTRAL)
        val google = GoogleMavenSource()
        val portal = MavenLayoutSource(SourceRouter.PLUGIN_PORTAL_URL)
        val corp = MavenLayoutSource("https://nexus.corp.example/repo")
        val router = SourceRouter(central, google, portal, { null }, { listOf(corp) })

        assertEquals(listOf(central, corp), router.sourcesFor(Coordinates("com.corp.internal", "lib")))
        assertEquals(listOf(google, central, corp), router.sourcesFor(Coordinates("androidx.core", "core")))
    }

    @Test
    fun `a mirrored central swaps the base url for every chain`() {
        val central = MavenLayoutSource(MavenRepositoryUrls.MAVEN_CENTRAL)
        val router = SourceRouter(
            central, GoogleMavenSource(), MavenLayoutSource(SourceRouter.PLUGIN_PORTAL_URL), { null },
            centralRoute = { MavenMirrorSelector.CentralRoute.Via("https://nexus.corp/maven-public", "corp") },
        )
        val sources = router.sourcesFor(Coordinates("org.slf4j", "slf4j-api"))
        val mirrored = sources.single() as MavenLayoutSource
        assertTrue(mirrored !== central)
    }

    @Test
    fun `a blocked central drops it from the chain without touching extras`() {
        val corp = MavenLayoutSource("https://nexus.corp.example/repo")
        val router = SourceRouter(
            MavenLayoutSource(MavenRepositoryUrls.MAVEN_CENTRAL), GoogleMavenSource(),
            MavenLayoutSource(SourceRouter.PLUGIN_PORTAL_URL), { null },
            extras = { listOf(corp) },
            centralRoute = { MavenMirrorSelector.CentralRoute.Blocked("blocker") },
        )
        assertEquals(listOf<VersionSource>(corp), router.sourcesFor(Coordinates("org.slf4j", "slf4j-api")))
    }
}
