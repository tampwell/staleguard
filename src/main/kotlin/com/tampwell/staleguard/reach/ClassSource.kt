package com.tampwell.staleguard.reach

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile

/** The classes one runtime classpath can load, the first definition winning as it does in the JVM. */
interface ClassSource : AutoCloseable {
    /** Internal names, classpath order. */
    val classNames: Collection<String>

    fun bytes(internalName: String): ByteArray?

    /**
     * Classes the runtime instantiates without any static call naming them:
     * java.util.ServiceLoader providers. Their constructors run when anyone
     * loads the service, which is how JDBC drivers, servlet initializers and
     * logging bindings come alive.
     */
    val serviceProviders: Set<String> get() = emptySet()

    /**
     * Classes a framework drives entirely by reflection: Spring Boot
     * auto-configuration and factories. Their methods run at startup whether
     * or not any code names them.
     */
    val frameworkEntries: Set<String> get() = emptySet()

    override fun close() = Unit
}

class InMemoryClassSource(
    private val classes: Map<String, ByteArray>,
    override val serviceProviders: Set<String> = emptySet(),
    override val frameworkEntries: Set<String> = emptySet(),
) : ClassSource {
    override val classNames: Collection<String> get() = classes.keys
    override fun bytes(internalName: String): ByteArray? = classes[internalName]
}

/**
 * Jars and class directories, indexed by entry name when opened and read on
 * demand, so an analysis that touches a tenth of the classpath reads a tenth
 * of it. META-INF/versions is skipped, as the linkage audit skips it: the
 * base version is the one every supported JVM links against.
 */
class ClasspathClassSource private constructor(
    private val index: Map<String, Location>,
    override val serviceProviders: Set<String>,
    override val frameworkEntries: Set<String>,
    private val zips: Map<Path, ZipFile>,
) : ClassSource {

    private sealed interface Location {
        class InJar(val jar: Path, val entry: String) : Location
        class InDirectory(val file: Path) : Location
    }

    override val classNames: Collection<String> get() = index.keys

    override fun bytes(internalName: String): ByteArray? = when (val location = index[internalName]) {
        null -> null
        is Location.InJar -> zips[location.jar]?.let { zip ->
            zip.getEntry(location.entry)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
        }
        is Location.InDirectory -> runCatching { Files.readAllBytes(location.file) }.getOrNull()
    }

    override fun close() {
        zips.values.forEach { runCatching { it.close() } }
    }

    companion object {
        /** An unreadable root is skipped, never fatal; the analysis states what it could not see. */
        fun open(roots: List<Path>): ClasspathClassSource {
            val index = LinkedHashMap<String, Location>()
            val providers = LinkedHashSet<String>()
            val framework = LinkedHashSet<String>()
            val zips = LinkedHashMap<Path, ZipFile>()
            for (root in roots) {
                if (Files.isDirectory(root)) {
                    indexDirectory(root, index, providers, framework)
                } else if (Files.isRegularFile(root)) {
                    val zip = runCatching { ZipFile(root.toFile()) }.getOrNull() ?: continue
                    zips[root] = zip
                    indexJar(root, zip, index, providers, framework)
                }
            }
            return ClasspathClassSource(index, providers, framework, zips)
        }

        private fun indexJar(
            jar: Path,
            zip: ZipFile,
            index: MutableMap<String, Location>,
            providers: MutableSet<String>,
            framework: MutableSet<String>,
        ) {
            for (entry in zip.entries()) {
                val name = entry.name
                when {
                    name.startsWith("META-INF/services/") && !entry.isDirectory ->
                        providers += parseServiceFile(zip.getInputStream(entry).use { String(it.readBytes()) })
                    name == "META-INF/spring.factories" ->
                        framework += parseSpringFactories(zip.getInputStream(entry).use { String(it.readBytes()) })
                    name.startsWith("META-INF/spring/") && name.endsWith(".imports") ->
                        framework += parseServiceFile(zip.getInputStream(entry).use { String(it.readBytes()) })
                    isIndexedClass(name) -> index.putIfAbsent(name.removeSuffix(".class"), Location.InJar(jar, name))
                }
            }
        }

        private fun indexDirectory(
            root: Path,
            index: MutableMap<String, Location>,
            providers: MutableSet<String>,
            framework: MutableSet<String>,
        ) {
            runCatching {
                Files.walk(root).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val name = root.relativize(file).joinToString("/")
                        when {
                            name.startsWith("META-INF/services/") ->
                                providers += parseServiceFile(Files.readString(file))
                            name == "META-INF/spring.factories" ->
                                framework += parseSpringFactories(Files.readString(file))
                            name.startsWith("META-INF/spring/") && name.endsWith(".imports") ->
                                framework += parseServiceFile(Files.readString(file))
                            isIndexedClass(name) ->
                                index.putIfAbsent(name.removeSuffix(".class"), Location.InDirectory(file))
                        }
                    }
                }
            }
        }

        private fun isIndexedClass(name: String): Boolean =
            name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class")

        /** One binary class name per line, '#' starts a comment. Also the Spring .imports format. */
        internal fun parseServiceFile(text: String): List<String> =
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() && it.all { c -> c.isJavaIdentifierPart() || c == '.' || c == '$' } }
                .map { it.replace('.', '/') }
                .toList()

        /** Properties format with backslash continuations; every value is a comma-separated class list. */
        internal fun parseSpringFactories(text: String): List<String> {
            val properties = Properties()
            runCatching { properties.load(text.reader()) }
            return properties.values.flatMap { value ->
                value.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }.map { it.replace('.', '/') }
        }
    }
}

/**
 * Class bytes for the JDK itself, from the platform class loader: it covers
 * the JDK's modules and nothing else, where the IDE's application loader
 * would also answer with the IDE's own copies of Guava or Jackson. The IDE's
 * runtime can differ from the project's SDK, but the JDK's type hierarchy is
 * the one thing that does not move between versions.
 */
internal object PlatformClasses {
    fun bytes(internalName: String): ByteArray? = runCatching {
        ClassLoader.getPlatformClassLoader().getResourceAsStream("$internalName.class")?.use { it.readBytes() }
    }.getOrNull()
}
