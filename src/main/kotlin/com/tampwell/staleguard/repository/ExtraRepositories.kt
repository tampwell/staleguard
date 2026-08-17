package com.tampwell.staleguard.repository

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.util.concurrent.ConcurrentHashMap

/**
 * The custom repositories seen across open projects, as lookup sources for
 * the router. They sit at the END of every source chain, so public artifacts
 * never generate traffic to corporate hosts — extras are consulted only when
 * everything upstream misses. App-level because the version cache is.
 */
@Service(Service.Level.APP)
class ExtraRepositories {

    private val sources = ConcurrentHashMap<String, MavenLayoutSource>()

    fun register(urls: Collection<String>) {
        for (url in urls) {
            sources.computeIfAbsent(url, ::MavenLayoutSource)
        }
    }

    fun sources(): List<VersionSource> = sources.values.toList()

    companion object {
        fun getInstance(): ExtraRepositories = service()
    }
}
