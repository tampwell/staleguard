package com.tampwell.staleguard.services

import com.intellij.util.messages.Topic

/** Project-bus event: the warm cache changed — freshness UIs should rebuild. */
fun interface FreshnessListener {

    fun freshnessChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<FreshnessListener> =
            Topic.create("Staleguard freshness", FreshnessListener::class.java)
    }
}
