package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Verdicts over real walks of the javac-compiled fixtures, so every branch rests on real results. */
class ReachVerdictsTest {

    private val fixtures: Map<String, ByteArray> by lazy {
        val app = checkNotNull(javaClass.classLoader.getResource("reachfix/app/App.class"))
        val root = Path.of(app.toURI()).parent.parent.parent
        Files.walk(root.resolve("reachfix")).use { stream ->
            stream.filter { it.toString().endsWith(".class") }.toList().associate { file ->
                root.relativize(file).joinToString("/").removeSuffix(".class") to Files.readAllBytes(file)
            }
        }
    }

    private fun walk(maxMethods: Int = Reachability.DEFAULT_MAX_METHODS) =
        Reachability.analyze(InMemoryClassSource(fixtures), listOf("reachfix/app/App"), maxMethods = maxMethods)

    private fun vulnerable(name: String) = MemberRef("reachfix/lib/Vulnerable", name, "()V")

    @Test
    fun `a reached fix-touched method yields the shortest explanation`() {
        val verdict = ReachVerdicts.decide(
            touched = setOf(vulnerable("exploitViaCha"), vulnerable("exploitUnused")),
            production = listOf(walk()),
            tests = emptyList(),
        )

        verdict as ReachVerdict.Reached
        assertTrue(verdict.inProduction)
        assertEquals(1, verdict.touchedReached)
        assertEquals(2, verdict.touchedTotal)
        assertEquals(listOf("App.cha", "Dispatcher.dispatch", "DangerousHandler.handle", "Vulnerable.exploitViaCha"), verdict.path)
    }

    @Test
    fun `reached only from tests is said so`() {
        val verdict = ReachVerdicts.decide(
            touched = setOf(vulnerable("exploitViaCha")),
            production = emptyList(),
            tests = listOf(walk()),
        )

        assertEquals(false, (verdict as ReachVerdict.Reached).inProduction)
    }

    @Test
    fun `nothing reached by complete walks is not reached`() {
        assertEquals(
            ReachVerdict.NotReached(1),
            ReachVerdicts.decide(setOf(vulnerable("exploitUnused")), listOf(walk()), listOf(walk())),
        )
    }

    @Test
    fun `an incomplete walk can still prove reached, but never not reached`() {
        val stopped = walk(maxMethods = 3)
        assertEquals(
            ReachVerdict.Unknown(ReachVerdict.Reason.ANALYSIS_INCOMPLETE),
            ReachVerdicts.decide(setOf(vulnerable("exploitUnused")), listOf(stopped), emptyList()),
        )
        assertTrue(ReachVerdicts.decide(setOf(vulnerable("exploitViaCha")), listOf(walk(), stopped), emptyList()) is ReachVerdict.Reached)
    }

    @Test
    fun `a fix that changed no code can say nothing`() {
        assertEquals(
            ReachVerdict.Unknown(ReachVerdict.Reason.FIX_CHANGED_NO_CODE),
            ReachVerdicts.decide(emptySet(), listOf(walk()), emptyList()),
        )
    }

    @Test
    fun `an artifact on no walked classpath is not found, not unreached`() {
        assertEquals(
            ReachVerdict.Unknown(ReachVerdict.Reason.JAR_NOT_FOUND),
            ReachVerdicts.decide(setOf(vulnerable("exploitUnused")), emptyList(), emptyList()),
        )
    }

    @Test
    fun `explanations name non-call edges and elide long middles`() {
        val method = MemberRef("a/B\$C", "<clinit>", "()V")
        val rendered = ReachPaths.render(
            listOf(
                Reachability.Step(MemberRef("app/App", "run", "()V"), Reachability.Edge.ENTRY),
                Reachability.Step(method, Reachability.Edge.STATIC_INIT),
                Reachability.Step(MemberRef("lib/Task", "run", "()V"), Reachability.Edge.RUNTIME_CALLBACK),
            ),
        )
        assertEquals(listOf("App.run", "B.C.static init", "Task.run (called back by the JDK)"), rendered)

        val long = (1..12).map { Reachability.Step(MemberRef("p/C$it", "m", "()V"), Reachability.Edge.CALL) }
        val elided = ReachPaths.render(long)
        assertEquals(ReachPaths.MAX_STEPS, elided.size)
        assertEquals("...", elided[3])
        assertEquals("C12.m", elided.last())
    }
}
