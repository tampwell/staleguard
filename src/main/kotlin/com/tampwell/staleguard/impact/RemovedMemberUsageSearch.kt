package com.tampwell.staleguard.impact

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.util.Processor

/**
 * Finds which removed members this project actually calls.
 *
 * Built on the platform's own [ReferencesSearch] rather than a hand-rolled
 * reference walk: it is index-backed, and every language that ships a
 * reference searcher — Java, Kotlin, Groovy, Scala — is handled by the people
 * who wrote that language's resolution, including constructor calls and
 * inherited-method call sites.
 *
 * The search runs against the OLD version's PSI, which is the version
 * currently attached to the project. That is what makes the lookup possible at
 * all: the members still exist to be searched for.
 */
object RemovedMemberUsageSearch {

    /**
     * A pathological upgrade (a major rewrite) can remove tens of thousands of
     * members. Past this many the report has stopped being actionable anyway,
     * and the caller says so rather than freezing the IDE.
     */
    const val MAX_MEMBERS_SEARCHED = 4000

    data class Result(val usages: List<RemovedUsage>, val searchedAll: Boolean)

    fun find(project: Project, removed: Collection<MemberRef>, indicator: ProgressIndicator): Result {
        if (removed.isEmpty()) return Result(emptyList(), searchedAll = true)

        val sourceScope = inReadAction { GlobalSearchScope.projectScope(project) }

        // One index probe per distinct word drops the members whose names
        // appear nowhere in this project — which is nearly all of them, and
        // costs no PSI resolution at all.
        val present = inReadAction {
            val helper = PsiSearchHelper.getInstance(project)
            removed.mapTo(HashSet()) { it.searchWord }
                .filterTo(HashSet()) { word ->
                    // The four-argument overload taking an indicator is
                    // deprecated on every supported line; this one reads the
                    // thread's current indicator, which inside a Backgroundable
                    // task is the same one, so cancellation still applies.
                    helper.isCheapEnoughToSearch(word, sourceScope, null) !=
                        PsiSearchHelper.SearchCostResult.ZERO_OCCURRENCES
                }
        }
        val candidates = removed.filter { it.searchWord in present }
        if (candidates.isEmpty()) return Result(emptyList(), searchedAll = true)

        val searched = candidates.take(MAX_MEMBERS_SEARCHED)
        val usages = mutableListOf<RemovedUsage>()
        for ((index, member) in searched.withIndex()) {
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / searched.size
            indicator.text2 = member.display()
            val locations = inReadAction { locationsOf(project, member, sourceScope) }
            if (locations.isNotEmpty()) usages += RemovedUsage(member, locations)
        }
        return Result(usages, searchedAll = searched.size == candidates.size)
    }

    private fun locationsOf(
        project: Project,
        member: MemberRef,
        scope: GlobalSearchScope,
    ): List<UsageLocation> {
        val target = resolveTarget(project, member) ?: return emptyList()
        val locations = mutableListOf<UsageLocation>()
        // Explicit Processor, not the Kotlin Iterable.forEach extension that
        // Query also matches: only this overload's boolean return stops the
        // search, and with the extension the cap would silently do nothing.
        ReferencesSearch.search(target, scope).forEach(
            Processor { reference ->
                locationOf(project, reference.element)?.let { locations += it }
                locations.size < MAX_LOCATIONS_PER_MEMBER
            },
        )
        return locations
    }

    /**
     * The PSI element for a removed member, found in the version currently on
     * the classpath. Null when the class is not on this project's classpath at
     * all, which simply means nothing here can be calling it.
     */
    private fun resolveTarget(project: Project, member: MemberRef): PsiElement? {
        val psiClass: PsiClass = JavaPsiFacade.getInstance(project)
            .findClass(member.ownerClassName, GlobalSearchScope.allScope(project))
            ?: return null
        if (!member.isMethod) return psiClass.findFieldByName(member.name, false)
        val expected = JvmDescriptors.parameterTypes(member.descriptor)
        val overloads =
            if (member.isConstructor) psiClass.constructors else psiClass.findMethodsByName(member.name, false)
        return overloads.firstOrNull { matchesParameters(it, expected) }
    }

    /**
     * Descriptor-exact matching against erased PSI types. Erasure is what the
     * compiler writes into a descriptor, so `List<String>` and `T` line up
     * with `java.util.List` and the type variable's bound without any generic
     * reasoning here.
     */
    private fun matchesParameters(method: PsiMethod, expected: List<String>): Boolean {
        val parameters = method.parameterList.parameters
        if (parameters.size != expected.size) return false
        return parameters.indices.all { i -> erasedName(parameters[i].type) == expected[i] }
    }

    private fun erasedName(type: PsiType): String {
        val normalized = if (type is PsiEllipsisType) type.toArrayType() else type
        return (TypeConversionUtil.erasure(normalized) ?: normalized).canonicalText
    }

    private fun locationOf(project: Project, element: PsiElement): UsageLocation? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        // Generated sources and build output are not the user's code to fix.
        if (ProjectFileIndex.getInstance(project).isInLibrary(virtualFile)) return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        val offset = element.textRange?.startOffset ?: return null
        val line = document?.takeIf { offset <= it.textLength }?.getLineNumber(offset)?.plus(1) ?: 0
        return UsageLocation(
            fileUrl = virtualFile.url,
            presentablePath = presentablePath(project, virtualFile.path),
            line = line,
            offset = offset,
        )
    }

    private fun presentablePath(project: Project, path: String): String {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/')
        val normalized = path.replace('\\', '/')
        return if (base != null && normalized.startsWith("$base/")) normalized.removePrefix("$base/") else normalized
    }

    private const val MAX_LOCATIONS_PER_MEMBER = 50
}
