package com.tampwell.staleguard.impact

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

/**
 * "Does this JDK type declare a member with this name" through the project
 * SDK index. findMethodsByName/findFieldByName with checkBases=true walk the
 * JDK's own hierarchy, which is the contract [LinkageAudit] expects, and the
 * SDK answers for exactly the Java version this project compiles against.
 * Answers are memoised; an audit asks about the same few platform types
 * thousands of times.
 */
internal class PsiPlatformMembers(private val project: Project) {

    private val cache = HashMap<String, Boolean>()

    fun has(internalName: String, memberName: String): Boolean =
        cache.getOrPut("$internalName#$memberName") {
            inReadAction {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(
                        internalName.replace('/', '.').replace('$', '.'),
                        GlobalSearchScope.allScope(project),
                    )
                    // An unindexed platform class cannot prove absence; the
                    // audit treats "true" as resolved, which is the quiet side.
                    ?: return@inReadAction true
                when {
                    memberName == "<init>" -> true
                    psiClass.findMethodsByName(memberName, true).isNotEmpty() -> true
                    psiClass.findFieldByName(memberName, true) != null -> true
                    else -> false
                }
            }
        }
}
