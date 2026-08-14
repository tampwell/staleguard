package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.tampwell.staleguard.StaleguardBundle
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral

/**
 * In-place version bump for Gradle Groovy DSL. The problem anchors on the
 * string literal itself; ElementManipulators handles quote styles for us.
 */
class GradleBumpVersionQuickFix(
    private val newVersion: String,
    private val mode: Mode,
) : LocalQuickFix, com.intellij.openapi.util.Iconable {

    enum class Mode {
        /** `implementation 'g:a:1.0'` — swap the version inside the notation. */
        NOTATION,

        /** `version: '1.0'` in map notation — replace the whole literal content. */
        MAP_VERSION,
    }

    override fun getIcon(flags: Int): javax.swing.Icon = com.intellij.icons.AllIcons.Actions.Edit

    override fun getFamilyName(): String = StaleguardBundle.message("fix.bump.family")

    override fun getName(): String = StaleguardBundle.message("fix.bump.name", newVersion)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement as? GrLiteral ?: return
        val value = literal.value as? String ?: return
        val newContent = when (mode) {
            Mode.NOTATION -> {
                val parsed = GradleNotationParser.parse(value) ?: return
                GradleNotationParser.withVersion(value, parsed, newVersion)
            }
            Mode.MAP_VERSION -> newVersion
        }
        ElementManipulators.handleContentChange(literal, newContent)
    }
}
