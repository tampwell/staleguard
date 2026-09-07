package com.tampwell.staleguard

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.StaleguardBundle"

// The two-argument constructor is the range-stable one: the single-argument
// overload is deprecated from the 263 line onward, and the class-qualified
// form exists all the way back to the 243 floor.
object StaleguardBundle : DynamicBundle(StaleguardBundle::class.java, BUNDLE) {

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
