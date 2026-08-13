package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.tampwell.staleguard.StaleguardBundle
import org.jetbrains.idea.maven.dom.MavenDomUtil

/**
 * One-click version bump. Runs inside the platform-provided write action
 * (startInWriteAction is true by default for LocalQuickFix).
 *
 * The problem is always anchored on the dependency's `<version>` tag for
 * [FixTarget.Literal]; for [FixTarget.Property] the anchor is also the version
 * tag but the EDIT happens at the property definition — per project rule,
 * never inline a literal over a `${property}` reference.
 */
class BumpVersionQuickFix(
    private val newVersion: String,
    private val target: FixTarget,
) : LocalQuickFix {

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
                versionTag.value.text = newVersion
            }

            is FixTarget.Property -> {
                val file = element.containingFile as? XmlFile ?: return
                val model = MavenDomUtil.getMavenDomProjectModel(project, file.virtualFile) ?: return
                val propertyTag = model.properties.xmlTag
                    ?.subTags
                    ?.firstOrNull { it.name == target.name }
                    ?: return
                propertyTag.value.text = newVersion
            }

            FixTarget.None -> Unit // never offered; defensive no-op
        }
    }
}
