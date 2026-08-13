package com.tampwell.staleguard.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.tampwell.staleguard.StaleguardBundle

/**
 * Settings page (Settings → Tools → Staleguard), built with the Kotlin UI
 * DSL. BoundConfigurable handles isModified/apply/reset from the bindings.
 */
class StaleguardConfigurable : BoundConfigurable(StaleguardBundle.message("settings.display.name")) {

    private val settings = StaleguardSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox(StaleguardBundle.message("settings.offline"))
                .comment(StaleguardBundle.message("settings.offline.comment"))
                .bindSelected(settings::offlineMode)
        }
        row {
            checkBox(StaleguardBundle.message("settings.prereleases"))
                .comment(StaleguardBundle.message("settings.prereleases.comment"))
                .bindSelected(settings::suggestPrereleases)
        }
        lateinit var abandonmentBox: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
        row {
            abandonmentBox = checkBox(StaleguardBundle.message("settings.abandonment"))
                .comment(StaleguardBundle.message("settings.abandonment.comment"))
                .bindSelected(settings::abandonmentEnabled)
        }
        row(StaleguardBundle.message("settings.abandonment.years")) {
            intTextField(range = 1..25)
                .bindIntText(settings::abandonmentYears)
        }.enabledIf(abandonmentBox.selected)
        group(StaleguardBundle.message("settings.ignore.title")) {
            row {
                textArea()
                    .rows(6)
                    .align(AlignX.FILL)
                    .comment(StaleguardBundle.message("settings.ignore.comment"))
                    .bindText(
                        getter = { settings.ignoredCoordinates.joinToString("\n") },
                        setter = { text ->
                            settings.ignoredCoordinates =
                                text.lines().map(String::trim).filter(String::isNotEmpty).toMutableList()
                        },
                    )
            }
        }
        group(StaleguardBundle.message("settings.cache.title")) {
            row {
                val lookup = com.tampwell.staleguard.services.VersionLookupService.getInstance()
                val (count, bytes) = lookup.cacheStats()
                val statsLabel = label(StaleguardBundle.message("settings.cache.stats", count, bytes / 1024))
                button(StaleguardBundle.message("settings.cache.clear")) {
                    lookup.clearCache()
                    statsLabel.component.text = StaleguardBundle.message("settings.cache.cleared")
                }
            }
        }
    }
}
