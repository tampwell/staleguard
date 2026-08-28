package com.tampwell.staleguard.impact

/**
 * The linkage view of one class: its API shape, everything it declares at any
 * visibility, and every external member it references.
 *
 * [declaredAll] is the resolution target set for a linkage walk and is a
 * superset of [api].members: private members resolve for same-class refs, and
 * synthetic bridge methods are real call targets. [refs] comes straight from
 * the constant pool, which lists every member a class can possibly touch
 * without any bytecode-body parsing.
 */
data class ClassScan(
    val api: ClassApi,
    val declaredAll: Set<MemberKey>,
    val refs: Set<MemberRef>,
) {
    val internalName: String get() = api.internalName
}
