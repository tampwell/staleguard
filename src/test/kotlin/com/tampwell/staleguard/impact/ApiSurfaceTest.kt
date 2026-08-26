package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JvmDescriptorsTest {

    @Test
    fun `parses mixed parameter kinds in order`() {
        assertEquals(
            listOf("java.lang.String", "int", "long[]"),
            JvmDescriptors.parameterTypes("(Ljava/lang/String;I[J)V"),
        )
    }

    @Test
    fun `no-argument methods have no parameters`() {
        assertEquals(emptyList<String>(), JvmDescriptors.parameterTypes("()Ljava/lang/String;"))
    }

    @Test
    fun `field descriptors have no parameter list`() {
        assertEquals(emptyList<String>(), JvmDescriptors.parameterTypes("Ljava/util/List;"))
    }

    @Test
    fun `nested classes read as source-level names so PSI comparison lines up`() {
        assertEquals(
            listOf("com.fasterxml.jackson.annotation.JsonInclude.Include"),
            JvmDescriptors.parameterTypes("(Lcom/fasterxml/jackson/annotation/JsonInclude\$Include;)V"),
        )
    }

    @Test
    fun `multi-dimensional object arrays keep every dimension`() {
        assertEquals(listOf("java.lang.String[][]"), JvmDescriptors.parameterTypes("([[Ljava/lang/String;)V"))
    }

    @Test
    fun `every primitive tag maps to its source name`() {
        assertEquals(
            listOf("byte", "char", "double", "float", "int", "long", "short", "boolean"),
            JvmDescriptors.parameterTypes("(BCDFIJSZ)V"),
        )
    }

    @Test
    fun `a truncated descriptor yields what it could read instead of throwing`() {
        assertEquals(listOf("int"), JvmDescriptors.parameterTypes("(ILjava/lang/String"))
    }
}

class ApiSurfaceTest {

    private fun clazz(
        name: String,
        superName: String? = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        vararg members: Pair<String, String>,
    ) = ClassApi(name, superName, interfaces, members.map { MemberKey(it.first, it.second) }.toSet())

    @Test
    fun `a dropped method is removed`() {
        val old = ApiSurface.of(listOf(clazz("a/B", members = arrayOf("gone" to "()V", "kept" to "()V"))))
        val new = ApiSurface.of(listOf(clazz("a/B", members = arrayOf("kept" to "()V"))))

        assertEquals(setOf(MemberRef("a/B", "gone", "()V")), old.removedIn(new))
    }

    @Test
    fun `a changed return type is removed because the JVM links on the whole descriptor`() {
        val old = ApiSurface.of(listOf(clazz("a/B", members = arrayOf("get" to "()Ljava/lang/String;"))))
        val new = ApiSurface.of(listOf(clazz("a/B", members = arrayOf("get" to "()Ljava/lang/Object;"))))

        assertEquals(setOf(MemberRef("a/B", "get", "()Ljava/lang/String;")), old.removedIn(new))
    }

    @Test
    fun `a method pulled up into a superclass is NOT removed`() {
        val old = ApiSurface.of(listOf(clazz("a/Sub", superName = "a/Base", members = arrayOf("run" to "()V"))))
        val new = ApiSurface.of(
            listOf(
                clazz("a/Sub", superName = "a/Base"),
                clazz("a/Base", members = arrayOf("run" to "()V")),
            ),
        )

        assertEquals(emptySet<MemberRef>(), old.removedIn(new))
    }

    @Test
    fun `a method pulled up into an interface is NOT removed`() {
        val old = ApiSurface.of(listOf(clazz("a/Impl", members = arrayOf("run" to "()V"))))
        val new = ApiSurface.of(
            listOf(
                clazz("a/Impl", interfaces = listOf("a/Api")),
                clazz("a/Api", superName = null, members = arrayOf("run" to "()V")),
            ),
        )

        assertEquals(emptySet<MemberRef>(), old.removedIn(new))
    }

    @Test
    fun `a deleted class removes every member it declared`() {
        val old = ApiSurface.of(listOf(clazz("a/Gone", members = arrayOf("x" to "()V", "y" to "I"))))

        assertEquals(
            setOf(MemberRef("a/Gone", "x", "()V"), MemberRef("a/Gone", "y", "I")),
            old.removedIn(ApiSurface.EMPTY),
        )
    }

    @Test
    fun `an unresolvable third-party supertype suppresses the report rather than guessing`() {
        // ObjectMapper extends ObjectCodec from a sibling jar: without that jar
        // we cannot tell a pull-up from a deletion, and a wrong accusation is
        // worse than a miss.
        val old = ApiSurface.of(listOf(clazz("a/Mapper", superName = "other/Codec", members = arrayOf("read" to "()V"))))
        val new = ApiSurface.of(listOf(clazz("a/Mapper", superName = "other/Codec")))

        assertEquals(emptySet<MemberRef>(), old.removedIn(new))
    }

    @Test
    fun `the same supertype found on the classpath restores the verdict`() {
        val old = ApiSurface.of(listOf(clazz("a/Mapper", superName = "other/Codec", members = arrayOf("read" to "()V"))))
        val new = ApiSurface.of(listOf(clazz("a/Mapper", superName = "other/Codec")))
        val siblingJar = ClassApiLookup { name ->
            if (name == "other/Codec") clazz("other/Codec") else null
        }

        assertEquals(setOf(MemberRef("a/Mapper", "read", "()V")), old.removedIn(new, siblingJar))
    }

    @Test
    fun `a pull-up into a sibling jar's class is still not a removal`() {
        val old = ApiSurface.of(listOf(clazz("a/Mapper", superName = "other/Codec", members = arrayOf("read" to "()V"))))
        val new = ApiSurface.of(listOf(clazz("a/Mapper", superName = "other/Codec")))
        val siblingJar = ClassApiLookup { name ->
            if (name == "other/Codec") clazz("other/Codec", members = arrayOf("read" to "()V")) else null
        }

        assertEquals(emptySet<MemberRef>(), old.removedIn(new, siblingJar))
    }

    @Test
    fun `a missing JDK supertype is certainty, not ignorance`() {
        val old = ApiSurface.of(
            listOf(clazz("a/B", superName = "java/util/AbstractList", members = arrayOf("gone" to "()V"))),
        )
        val new = ApiSurface.of(listOf(clazz("a/B", superName = "java/util/AbstractList")))

        assertEquals(setOf(MemberRef("a/B", "gone", "()V")), old.removedIn(new))
    }

    @Test
    fun `a cyclic hierarchy terminates`() {
        val surface = ApiSurface.of(
            listOf(
                clazz("a/X", superName = "a/Y"),
                clazz("a/Y", superName = "a/X"),
            ),
        )

        assertEquals(ApiSurface.Resolution.ABSENT, surface.resolve("a/X", MemberKey("nope", "()V")))
    }

    @Test
    fun `platform types are recognised by prefix`() {
        assertTrue(ApiSurface.isPlatformType("java/lang/Object"))
        assertTrue(ApiSurface.isPlatformType("javax/swing/JPanel"))
        assertFalse(ApiSurface.isPlatformType("com/fasterxml/jackson/databind/ObjectMapper"))
    }
}

class MemberRefTest {

    @Test
    fun `a method displays with simple parameter type names`() {
        val ref = MemberRef(
            "com/fasterxml/jackson/databind/ObjectMapper",
            "setDateFormat",
            "(Ljava/text/DateFormat;)Lcom/fasterxml/jackson/databind/ObjectMapper;",
        )

        assertEquals("ObjectMapper.setDateFormat(DateFormat)", ref.display())
        assertEquals("com.fasterxml.jackson.databind.ObjectMapper", ref.ownerClassName)
        assertEquals("setDateFormat", ref.searchWord)
    }

    @Test
    fun `a constructor displays and is searched as its class name`() {
        val ref = MemberRef("a/b/Widget", "<init>", "(I)V")

        assertEquals("Widget(int)", ref.display())
        assertEquals("Widget", ref.searchWord)
    }

    @Test
    fun `a nested class constructor searches on the innermost name`() {
        val ref = MemberRef("a/b/Outer\$Inner", "<init>", "()V")

        assertEquals("Inner", ref.searchWord)
        assertEquals("a.b.Outer.Inner", ref.ownerClassName)
    }

    @Test
    fun `a field displays without a parameter list`() {
        assertEquals("Widget.COUNT", MemberRef("a/b/Widget", "COUNT", "I").display())
    }

    @Test
    fun `array parameters keep their brackets in the display form`() {
        val ref = MemberRef("a/B", "f", "([Ljava/lang/String;)V")

        assertEquals("B.f(String[])", ref.display())
    }
}

class ImpactVerdictTest {

    private fun report(
        removedTotal: Int = 0,
        usages: List<RemovedUsage> = emptyList(),
        incomplete: ImpactReport.Incomplete? = null,
    ) = ImpactReport("g:a", "1.0", "2.0", removedTotal, usages, incomplete)

    private val someUsage = RemovedUsage(
        MemberRef("a/B", "gone", "()V"),
        listOf(UsageLocation("file:///x/Y.java", "src/Y.java", 12, 340)),
    )

    @Test
    fun `no removals at all is the clean verdict`() {
        assertEquals(ImpactVerdict.NO_REMOVALS, report().verdict)
    }

    @Test
    fun `removals nobody calls are safe`() {
        assertEquals(ImpactVerdict.REMOVALS_UNUSED, report(removedTotal = 197).verdict)
    }

    @Test
    fun `a called removal breaks`() {
        assertEquals(ImpactVerdict.BREAKS, report(removedTotal = 197, usages = listOf(someUsage)).verdict)
    }

    @Test
    fun `an incomplete analysis never claims safety`() {
        assertEquals(
            ImpactVerdict.UNKNOWN,
            report(incomplete = ImpactReport.Incomplete.CANDIDATE_JAR_UNAVAILABLE).verdict,
        )
    }

    @Test
    fun `call sites are counted across members`() {
        val second = RemovedUsage(
            MemberRef("a/B", "other", "()V"),
            listOf(
                UsageLocation("file:///x/Z.java", "src/Z.java", 1, 2),
                UsageLocation("file:///x/Z.java", "src/Z.java", 5, 60),
            ),
        )

        assertEquals(3, report(usages = listOf(someUsage, second)).affectedCallSites)
    }
}
