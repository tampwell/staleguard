package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.maven.PropertyUsageFinder
import com.tampwell.staleguard.settings.StaleguardSettings
import org.jetbrains.idea.maven.dom.MavenDomUtil

/**
 * One-click version bump.
 *
 * startInWriteAction is false because the Property path may show a blast-
 * radius confirmation first (a property can drive many dependencies across
 * modules) — dialogs are illegal inside write actions. Each branch wraps its
 * actual PSI edit in its own WriteCommandAction.
 *
 * For [FixTarget.Property] the anchor is the version tag but the EDIT happens
 * at the property definition — per project rule, never inline a literal over
 * a `${property}` reference.
 */
class BumpVersionQuickFix(
    private val newVersion: String,
    private val target: FixTarget,
) : LocalQuickFix, com.intellij.openapi.util.Iconable {

    override fun startInWriteAction(): Boolean = false

    override fun getIcon(flags: Int): javax.swing.Icon = com.intellij.icons.AllIcons.Actions.Edit

    override fun getFamilyName(): String = StaleguardBundle.message("fix.bump.family")

    override fun getName(): String = when (target) {
        is FixTarget.Property -> StaleguardBundle.message("fix.bump.property.name", target.name, newVersion)
        else -> StaleguardBundle.message("fix.bump.name", newVersion)
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        when (target) {
            FixTarget.Literal -> {
                val versionTag = element as? XmlTag ?: return
                WriteCommandAction.runWriteCommandAction(project, name, null, {
                    versionTag.value.text = newVersion
                })
            }

            is FixTarget.Property -> {
                val file = element.containingFile as? XmlFile ?: return
                if (!confirmPropertyImpact(project, target.name)) return
                WriteCommandAction.runWriteCommandAction(project, name, null, {
                    val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile)
                        ?: return@runWriteCommandAction
                    val propertyTag = model.properties.xmlTag
                        ?.subTags
                        ?.firstOrNull { it.name == target.name }
                        ?: return@runWriteCommandAction
                    propertyTag.value.text = newVersion
                })
            }

            FixTarget.None -> Unit // never offered; defensive no-op
        }
    }

    /**
     * Blast-radius gate: when the property drives more than one dependency,
     * show what else changes before touching it. Suppressible per property.
     * No dialog in unit-test mode or for single-use properties.
     */
    private fun confirmPropertyImpact(project: Project, propertyName: String): Boolean {
        val settings = StaleguardSettings.getInstance()
        if (propertyName in settings.state.suppressedPropertyWarnings) return true

        val usages = PropertyUsageFinder.usages(project, propertyName)
        if (usages.size <= 1) return true
        if (ApplicationManager.getApplication().isUnitTestMode) return true

        val affectedList = usages.joinToString("\n") { usage ->
            "  • ${usage.moduleName}: ${usage.coordinates}" +
                (usage.resolvedVersion?.let { " ($it)" } ?: "")
        }
        val confirmed = MessageDialogBuilder.yesNo(
            StaleguardBundle.message("fix.property.impact.title"),
            StaleguardBundle.message("fix.property.impact.message", propertyName, usages.size, affectedList),
        )
            .doNotAsk(object : com.intellij.openapi.ui.DoNotAskOption.Adapter() {
                override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                    if (isSelected && exitCode == com.intellij.openapi.ui.Messages.YES) {
                        settings.state.suppressedPropertyWarnings.add(propertyName)
                    }
                }

                override fun getDoNotShowMessage(): String =
                    StaleguardBundle.message("fix.property.impact.dontask")
            })
            .ask(project)
        return confirmed
    }
}
