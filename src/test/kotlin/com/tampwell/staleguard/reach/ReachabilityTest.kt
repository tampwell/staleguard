package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef
import com.tampwell.staleguard.reach.Reachability.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The dispatch model against real javac output (src/test/java/reachfix):
 * every scenario routes to its own target in Vulnerable, so each verdict and
 * each explanation is asserted on its own. The JDK side comes from the real
 * platform class loader, exactly as in production.
 */
class ReachabilityTest {

    private val fixtures: Map<String, ByteArray> by lazy {
        val app = checkNotNull(javaClass.classLoader.getResource("reachfix/app/App.class")) {
            "fixture classes missing from the test classpath"
        }
        val root = Path.of(app.toURI()).parent.parent.parent
        Files.walk(root.resolve("reachfix")).use { stream ->
            stream.filter { it.toString().endsWith(".class") }.toList().associate { file ->
                root.relativize(file).joinToString("/").removeSuffix(".class") to Files.readAllBytes(file)
            }
        }
    }

    private fun analyze(
        providers: Set<String> = emptySet(),
        maxMethods: Int = Reachability.DEFAULT_MAX_METHODS,
        entries: List<String> = listOf("reachfix/app/App", "reachfix/app/Late"),
    ): Reachability.Result = Reachability.analyze(
        InMemoryClassSource(fixtures, serviceProviders = providers),
        entryClasses = entries,
        maxMethods = maxMethods,
    )

    private fun target(name: String, descriptor: String = "()V") =
        MemberRef("reachfix/lib/Vulnerable", name, descriptor)

    private fun Reachability.Result.render(method: MemberRef): List<String> =
        checkNotNull(path(method)) { "expected $method to be reached" }
            .map { "${it.method.owner.substringAfterLast('/')}.${it.method.name}:${it.via}" }

    @Test
    fun `a method present on the classpath but never called is not reached`() {
        val result = analyze()

        assertFalse(result.reaches(target("exploitUnused")))
        assertTrue(result.reaches(MemberRef("reachfix/lib/Parser", "parse", "(Ljava/lang/String;)V")))
        assertTrue(result.reaches(MemberRef("reachfix/lib/Helper", "normalize", "(Ljava/lang/String;)Ljava/lang/String;")))
        assertTrue(result.complete)
    }

    @Test
    fun `a library interface call reaches implementations nobody instantiates statically`() {
        val result = analyze()

        assertEquals(
            listOf("App.cha:ENTRY", "Dispatcher.dispatch:CALL", "DangerousHandler.handle:CALL", "Vulnerable.exploitViaCha:CALL"),
            result.render(target("exploitViaCha")),
        )
    }

    @Test
    fun `an anonymous class whose enclosing code is unreached is never a receiver`() {
        assertFalse(analyze().reaches(target("exploitCreatedElsewhere")))
    }

    @Test
    fun `an anonymous class created by code the walk reaches later still receives earlier call sites`() {
        val expected = listOf(
            "App.cha:ENTRY", "Dispatcher.dispatch:CALL", "Factory\$2.handle:CALL", "Vulnerable.exploitCreatedLate:CALL",
        )
        assertEquals(expected, analyze().render(target("exploitCreatedLate")))
        // The verdict must not depend on which entry the walk happens to start from.
        assertTrue(analyze(entries = listOf("reachfix/app/Late", "reachfix/app/App")).reaches(target("exploitCreatedLate")))
    }

    @Test
    fun `a named class stays a receiver even when only unreached code creates it`() {
        // Deliberate: plugin systems create named classes by name, often via
        // reflectively invoked static factories. Log4Shell runs through one.
        assertEquals(
            listOf(
                "App.cha:ENTRY", "Dispatcher.dispatch:CALL", "NamedCreatedElsewhere.handle:CALL",
                "Vulnerable.exploitNamedCreatedElsewhere:CALL",
            ),
            analyze().render(target("exploitNamedCreatedElsewhere")),
        )
    }

    @Test
    fun `an object handed to the JDK has its JDK overrides reached as callbacks`() {
        val result = analyze()

        assertEquals(
            listOf("App.callback:ENTRY", "CallbackTask.run:RUNTIME_CALLBACK", "Vulnerable.exploitViaCallback:CALL"),
            result.render(target("exploitViaCallback")),
        )
    }

    @Test
    fun `a call through a JDK interface does not reach classes nobody creates`() {
        assertFalse(analyze().reaches(target("exploitUncreated")))
    }

    @Test
    fun `callbacks cover JDK overrides only, not every method of a created object`() {
        val result = analyze()

        assertTrue(result.reaches(MemberRef("reachfix/lib/Quiet", "run", "()V")))
        assertFalse(result.reaches(target("exploitQuiet")))
    }

    @Test
    fun `reading a static field runs the static initializer`() {
        assertEquals(
            listOf("App.staticInit:ENTRY", "StaticInit.<clinit>:STATIC_INIT", "Vulnerable.exploitStaticInit:CALL"),
            analyze().render(target("exploitStaticInit")),
        )
    }

    @Test
    fun `a lambda body is reached where the lambda is created`() {
        val path = analyze().render(target("exploitLambda"))

        assertEquals("App.lambda:ENTRY", path.first())
        assertEquals("LambdaUser.make:CALL", path[1])
        assertTrue("the lambda's synthetic body must be on the path: $path", path[2].startsWith("LambdaUser.lambda$"))
        assertEquals("Vulnerable.exploitLambda:CALL", path.last())
    }

    @Test
    fun `a method reference target is reached through the bootstrap handle`() {
        assertEquals(
            listOf("App.methodRef:ENTRY", "MethodRefUser.apply:CALL", "Vulnerable.exploitMethodRef:CALL"),
            analyze().render(target("exploitMethodRef", "(Ljava/lang/String;)V")),
        )
    }

    @Test
    fun `an inherited default method is selected for a class that does not override it`() {
        assertEquals(
            listOf("App.defaultMethod:ENTRY", "DefaultIface.defaultThing:CALL", "Vulnerable.exploitDefault:CALL"),
            analyze().render(target("exploitDefault")),
        )
    }

    @Test
    fun `a self call in a superclass dispatches to subclass overrides, never to unrelated classes`() {
        val result = analyze()

        assertEquals(
            listOf("App.template:ENTRY", "Base.template:CALL", "Derived.hook:CALL", "Vulnerable.exploitHook:CALL"),
            result.render(target("exploitHook")),
        )
        assertFalse(result.reaches(target("exploitUnrelated")))
    }

    @Test
    fun `a method inherited unchanged is reached through the subclass receiver`() {
        assertEquals(
            listOf("App.template:ENTRY", "Base.inherited:CALL", "Vulnerable.exploitInherited:CALL"),
            analyze().render(target("exploitInherited")),
        )
    }

    @Test
    fun `a ServiceLoader provider runs only when some jar declares it`() {
        assertFalse(analyze().reaches(target("exploitService")))

        val declared = analyze(providers = setOf("reachfix/lib/ServiceImpl"))
        assertEquals(
            listOf("ServiceImpl.<init>:FRAMEWORK", "Vulnerable.exploitService:CALL"),
            declared.render(target("exploitService")),
        )
    }

    @Test
    fun `an exhausted budget is reported as incomplete, never as unreached`() {
        val result = analyze(maxMethods = 5)

        assertFalse(result.complete)
        assertTrue(result.gaps.single().contains("stopped after 5"))
    }

    @Test
    fun `an unreached method has no path`() {
        assertNull(analyze().path(target("exploitUnused")))
        assertNotNull(analyze().path(target("exploitViaCha")))
    }
}
