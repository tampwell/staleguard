package com.tampwell.staleguard.plan

/**
 * What comparing the two versions' binaries actually found for one upgrade.
 *
 * [Recommendation] and [ConfidenceScorer] are heuristics about version
 * distance, release age and abandonment: educated guesses about a library in
 * general. This is a measurement of one specific project. When both are
 * available the measurement wins, because "a major bump is probably breaking"
 * and "this removes a method you call on line 42" are not the same claim.
 */
sealed interface MeasuredImpact {

    /** No comparison has been run for this version pair. Nothing is claimed. */
    data object Unknown : MeasuredImpact

    /** Compared: this project calls nothing the new version removes. */
    data object Clean : MeasuredImpact

    /** Compared: this project calls [members] removed members, at [callSites] places. */
    data class Breaks(val members: Int, val callSites: Int) : MeasuredImpact

    /**
     * My code's calls survive, but the classpath rehearsal shows the upgrade
     * introduces linkage problems elsewhere: breaks between other jars that
     * only this version change exposes.
     */
    data class BreaksLinkage(val problems: Int) : MeasuredImpact
}
