/*
 * Kotlin port of org.apache.maven.artifact.versioning.ComparableVersion from
 * Apache Maven (https://github.com/apache/maven), licensed under the Apache
 * License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Ported so that Staleguard orders versions exactly as Maven itself does —
 * including qualifier ranking (alpha < beta < milestone < rc < SNAPSHOT <
 * release < sp), string/digit transitions, and the MNG-5568 / MNG-6964 /
 * MNG-7644 edge-case fixes. Behavior is locked by the ported official Maven
 * test suite in MavenVersionTest.
 */
package com.tampwell.staleguard.version

import java.math.BigInteger
import java.util.ArrayDeque
import java.util.Locale

private const val MAX_INT_ITEM_LENGTH = 9
private const val MAX_LONG_ITEM_LENGTH = 18

private const val INT_ITEM = 3
private const val LONG_ITEM = 4
private const val BIGINTEGER_ITEM = 0
private const val STRING_ITEM = 1
private const val LIST_ITEM = 2
private const val COMBINATION_ITEM = 5

private interface Item {
    val type: Int
    val isNull: Boolean
    operator fun compareTo(other: Item?): Int
}

private class IntItem : Item {
    val value: Int

    constructor() {
        value = 0
    }

    constructor(str: String) {
        value = str.toInt()
    }

    override val type get() = INT_ITEM
    override val isNull get() = value == 0

    override fun compareTo(other: Item?): Int {
        if (other == null) return if (value == 0) 0 else 1 // 1.0 == 1, 1.1 > 1
        return when (other.type) {
            INT_ITEM -> value.compareTo((other as IntItem).value)
            LONG_ITEM, BIGINTEGER_ITEM -> -1
            STRING_ITEM -> 1
            COMBINATION_ITEM -> 1 // 1.1 > 1-sp
            LIST_ITEM -> 1 // 1.1 > 1-1
            else -> throw IllegalStateException("invalid item: ${other.javaClass}")
        }
    }

    override fun equals(other: Any?) = other is IntItem && value == other.value
    override fun hashCode() = value
    override fun toString() = value.toString()

    companion object {
        val ZERO = IntItem()
    }
}

private class LongItem(str: String) : Item {
    val value: Long = str.toLong()

    override val type get() = LONG_ITEM
    override val isNull get() = value == 0L

    override fun compareTo(other: Item?): Int {
        if (other == null) return if (value == 0L) 0 else 1
        return when (other.type) {
            INT_ITEM -> 1
            LONG_ITEM -> value.compareTo((other as LongItem).value)
            BIGINTEGER_ITEM -> -1
            STRING_ITEM -> 1
            COMBINATION_ITEM -> 1
            LIST_ITEM -> 1
            else -> throw IllegalStateException("invalid item: ${other.javaClass}")
        }
    }

    override fun equals(other: Any?) = other is LongItem && value == other.value
    override fun hashCode() = (value xor (value ushr 32)).toInt()
    override fun toString() = value.toString()
}

private class BigIntegerItem(str: String) : Item {
    val value: BigInteger = BigInteger(str)

    override val type get() = BIGINTEGER_ITEM
    override val isNull get() = BigInteger.ZERO == value

    override fun compareTo(other: Item?): Int {
        if (other == null) return if (BigInteger.ZERO == value) 0 else 1
        return when (other.type) {
            INT_ITEM, LONG_ITEM -> 1
            BIGINTEGER_ITEM -> value.compareTo((other as BigIntegerItem).value)
            STRING_ITEM -> 1
            COMBINATION_ITEM -> 1
            LIST_ITEM -> 1
            else -> throw IllegalStateException("invalid item: ${other.javaClass}")
        }
    }

    override fun equals(other: Any?) = other is BigIntegerItem && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = value.toString()
}

private class StringItem(rawValue: String, followedByDigit: Boolean) : Item {
    val value: String

    init {
        var v = rawValue
        if (followedByDigit && v.length == 1) {
            // a1 = alpha-1, b1 = beta-1, m1 = milestone-1
            when (v[0]) {
                'a' -> v = "alpha"
                'b' -> v = "beta"
                'm' -> v = "milestone"
            }
        }
        value = ALIASES[v] ?: v
    }

    override val type get() = STRING_ITEM
    override val isNull get() = value.isEmpty()

    override fun compareTo(other: Item?): Int {
        if (other == null) {
            // 1-rc < 1, 1-ga > 1
            return comparableQualifier(value).compareTo(RELEASE_VERSION_INDEX)
        }
        return when (other.type) {
            INT_ITEM, LONG_ITEM, BIGINTEGER_ITEM -> -1 // 1.any < 1.1

            STRING_ITEM -> comparableQualifier(value).compareTo(comparableQualifier((other as StringItem).value))

            COMBINATION_ITEM -> {
                val result = this.compareTo((other as CombinationItem).stringPart)
                if (result == 0) -1 else result
            }

            LIST_ITEM -> -1 // 1.any < 1-1

            else -> throw IllegalStateException("invalid item: ${other.javaClass}")
        }
    }

    override fun equals(other: Any?) = other is StringItem && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = value

    companion object {
        private val QUALIFIERS = listOf("alpha", "beta", "milestone", "rc", "snapshot", "", "sp")
        private val RELEASE_QUALIFIERS = listOf("ga", "final", "release")
        private val ALIASES = mapOf("cr" to "rc")

        /** Comparable value of the empty-string qualifier (= a plain release). */
        private val RELEASE_VERSION_INDEX = QUALIFIERS.indexOf("").toString()

        fun comparableQualifier(qualifier: String): String {
            if (qualifier in RELEASE_QUALIFIERS) return RELEASE_VERSION_INDEX
            val i = QUALIFIERS.indexOf(qualifier)
            return if (i == -1) "${QUALIFIERS.size}-$qualifier" else i.toString()
        }
    }
}

/** A string qualifier immediately followed by a number, e.g. the "rc1" in "1.0-rc1". */
private class CombinationItem(value: String) : Item {
    val stringPart: StringItem
    val digitPart: Item

    init {
        var index = 0
        for (i in value.indices) {
            if (value[i].isDigit()) {
                index = i
                break
            }
        }
        stringPart = StringItem(value.substring(0, index), true)
        digitPart = parseItem(isCombination = false, isDigit = true, buf = value.substring(index))
    }

    override val type get() = COMBINATION_ITEM
    override val isNull get() = false

    override fun compareTo(other: Item?): Int {
        if (other == null) {
            // 1-rc1 < 1, 1-ga1 > 1
            return stringPart.compareTo(other)
        }
        return when (other.type) {
            INT_ITEM, LONG_ITEM, BIGINTEGER_ITEM -> -1

            STRING_ITEM -> {
                val result = stringPart.compareTo(other)
                if (result == 0) 1 else result // X1 > X
            }

            LIST_ITEM -> -1

            COMBINATION_ITEM -> {
                val result = stringPart.compareTo((other as CombinationItem).stringPart)
                if (result == 0) digitPart.compareTo(other.digitPart) else result
            }

            else -> 0
        }
    }

    override fun equals(other: Any?) =
        other is CombinationItem && stringPart == other.stringPart && digitPart == other.digitPart

    override fun hashCode() = 31 * stringPart.hashCode() + digitPart.hashCode()
    override fun toString() = "$stringPart$digitPart"
}

private class ListItem : ArrayList<Item>(), Item {
    override val type get() = LIST_ITEM
    override val isNull get() = size == 0

    fun normalize() {
        for (i in size - 1 downTo 0) {
            val lastItem = get(i)
            if (lastItem.isNull) {
                if (i == size - 1 || get(i + 1).type == STRING_ITEM) {
                    removeAt(i)
                } else if (get(i + 1).type == LIST_ITEM) {
                    val first = (get(i + 1) as ListItem)[0]
                    if (first.type == COMBINATION_ITEM || first.type == STRING_ITEM) {
                        removeAt(i)
                    }
                }
            }
        }
    }

    override fun compareTo(other: Item?): Int {
        if (other == null) {
            if (size == 0) return 0 // 1-0 = 1- (normalize) = 1
            // Compare the entire list of items with null - not just the first one, MNG-6964
            for (item in this) {
                val result = item.compareTo(null)
                if (result != 0) return result
            }
            return 0
        }
        return when (other.type) {
            INT_ITEM, LONG_ITEM, BIGINTEGER_ITEM -> -1 // 1-1 < 1.0.x

            STRING_ITEM -> 1
            COMBINATION_ITEM -> 1 // 1-1 > 1-sp

            LIST_ITEM -> {
                val left = iterator()
                val right = (other as ListItem).iterator()
                var result = 0
                while (left.hasNext() || right.hasNext()) {
                    val l = if (left.hasNext()) left.next() else null
                    val r = if (right.hasNext()) right.next() else null
                    // if this is shorter, then invert the compare and mul with -1
                    result = l?.compareTo(r) ?: (r?.let { -1 * it.compareTo(null) } ?: 0)
                    if (result != 0) break
                }
                result
            }

            else -> throw IllegalStateException("invalid item: ${other.javaClass}")
        }
    }

    override fun toString(): String {
        val buffer = StringBuilder()
        for (item in this) {
            if (buffer.isNotEmpty()) {
                buffer.append(if (item is ListItem) '-' else '.')
            }
            buffer.append(item)
        }
        return buffer.toString()
    }
}

private fun parseItem(isCombination: Boolean, isDigit: Boolean, buf: String): Item {
    if (isCombination) {
        return CombinationItem(buf.replace("-", ""))
    }
    if (isDigit) {
        val stripped = stripLeadingZeroes(buf)
        return when {
            stripped.length <= MAX_INT_ITEM_LENGTH -> IntItem(stripped)
            stripped.length <= MAX_LONG_ITEM_LENGTH -> LongItem(stripped)
            else -> BigIntegerItem(stripped)
        }
    }
    return StringItem(buf, false)
}

private fun stripLeadingZeroes(buf: String): String {
    if (buf.isEmpty()) return "0"
    for (i in buf.indices) {
        if (buf[i] != '0') return buf.substring(i)
    }
    return buf
}

/**
 * A version string ordered exactly as Maven orders it. Immutable.
 *
 * `MavenVersion("1.0-rc1") < MavenVersion("1.0") < MavenVersion("1.0-sp1")`
 */
class MavenVersion(val value: String) : Comparable<MavenVersion> {

    private val items: ListItem = parse(value)

    /** The version as Maven canonically understands it, e.g. `1.0.0-ga` -> `1`. */
    val canonical: String by lazy(LazyThreadSafetyMode.PUBLICATION) { items.toString() }

    override fun compareTo(other: MavenVersion): Int = items.compareTo(other.items)

    override fun equals(other: Any?): Boolean = other is MavenVersion && items == other.items

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = value

    private companion object {

        fun parse(version: String): ListItem {
            val items = ListItem()
            val lower = version.lowercase(Locale.ENGLISH)

            var list = items
            val stack = ArrayDeque<ListItem>()
            stack.push(list)

            var isDigit = false
            var isCombination = false
            var startIndex = 0

            var i = 0
            while (i < lower.length) {
                val character = lower[i]
                var c = character.code
                if (Character.isHighSurrogate(character) && i + 1 < lower.length) {
                    // combine the surrogate pair into a single code point
                    c = Character.codePointAt(lower, i)
                    i++
                }

                if (c == '.'.code) {
                    if (i == startIndex) {
                        list.add(IntItem.ZERO)
                    } else {
                        list.add(parseItem(isCombination, isDigit, lower.substring(startIndex, i)))
                    }
                    isCombination = false
                    startIndex = i + 1
                } else if (c == '-'.code) {
                    if (i == startIndex) {
                        list.add(IntItem.ZERO)
                        startIndex = i + 1
                    } else {
                        // X-1 is going to be treated as X1
                        if (!isDigit && i != lower.length - 1 && lower[i + 1].isDigit()) {
                            isCombination = true
                            i++
                            continue
                        }
                        list.add(parseItem(isCombination, isDigit, lower.substring(startIndex, i)))
                        startIndex = i + 1
                    }
                    if (list.isNotEmpty()) {
                        val sub = ListItem()
                        list.add(sub)
                        list = sub
                        stack.push(list)
                    }
                    isCombination = false
                } else if (c >= '0'.code && c <= '9'.code) { // ASCII digits only
                    if (!isDigit && i > startIndex) {
                        // X1
                        isCombination = true
                        if (list.isNotEmpty()) {
                            val sub = ListItem()
                            list.add(sub)
                            list = sub
                            stack.push(list)
                        }
                    }
                    isDigit = true
                } else {
                    if (isDigit && i > startIndex) {
                        list.add(parseItem(isCombination, true, lower.substring(startIndex, i)))
                        startIndex = i
                        val sub = ListItem()
                        list.add(sub)
                        list = sub
                        stack.push(list)
                        isCombination = false
                    }
                    isDigit = false
                }
                i++
            }

            if (lower.length > startIndex) {
                // 1.0.0.X1 < 1.0.0-X2 : treat .X as -X for any string qualifier X
                if (!isDigit && list.isNotEmpty()) {
                    val sub = ListItem()
                    list.add(sub)
                    list = sub
                    stack.push(list)
                }
                list.add(parseItem(isCombination, isDigit, lower.substring(startIndex)))
            }

            while (stack.isNotEmpty()) {
                stack.pop().normalize()
            }

            return items
        }
    }
}
