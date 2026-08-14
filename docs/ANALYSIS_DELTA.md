# Strategic analysis — delta against existing evidence (2026-08-14)

The four audits requested by the session-13 order already exist with evidence;
re-deriving them would violate the order's own "not a feature factory" clause.
This file maps each dimension to its source of truth, then records the two
findings that are actually NEW.

## Where each requested analysis already lives

| Requested audit | Existing evidence |
|---|---|
| Code quality | Two grep-verified audits (PROGRESS sessions 8+11): 0 TODO/FIXME, 0 `!!`, 0 error()/check()/require() in prod, 0 hardcoded colors, only intended test-locked throws. Verifier: 0 internal APIs; residual flags are inherited interface defaults. |
| Competitive features | docs/REFERENCE.md §1-2 + Phase 0 memo: live marketplace data, rival-by-rival feature/gap table (report-only rival, dead Package Search, stale Gradle checker). Feature matrix conclusion unchanged: no competitor has kts + catalogs + license + changelog + abandonment together. |
| User journeys | Shipped from exactly this lens: first-run toast (journey 1), warm-cache instant re-checks + one-click patch action (journey 2), blast-radius dialogs + shared-ignore backlog (journey 3), offline mode + stale-serving (degraded-environment user). |
| Technical debt | Measured zero in the marker/assertion sense; accepted trade-offs documented in ledgers (build cache off, kts platform-test infeasible, verifier cosmetic flags). |

## NEW finding 1 — the downgrade journey (order's Journey 4, genuinely novel)

An emergency user mid-incident wants to go BACK a version; Staleguard is
upgrade-only. No prior session considered this. Assessment: real journey,
plausible differentiator ("pin/downgrade with the same property/catalog-aware
write-back"), but building it pre-launch = inventing demand. → Added to
V1.1_PLANNING backlog (Effort 3 — the write-back machinery already exists and
is direction-agnostic; Strategic 2; Demand 1 until users ask).

## NEW finding 2 — tool-window collection scale (accepted debt, trigger defined)

StaleguardStatsPanel/TimelinePanel collect DOM rows on the EDT during
rebuild(). Correct for normal projects (PSI access is EDT-conventional,
bounded, no I/O — the never-block invariant governs network/disk, which stay
off-EDT). At 100+-module monorepo scale this could produce perceptible rebuild
pauses. Per the order's own "not pre-optimization" rule this stays ACCEPTED
DEBT until evidence: revisit trigger = any user report of tool-window lag OR
first measurement >200ms on a real large project. Fix shape when triggered:
ReadAction.nonBlocking collection + debounced rebuild coalescing.

## P0/P1 implementations arising from this analysis: NONE

Stated per the order's instruction to say so when things are well-done: after
mapping all six suggested areas (inspection intelligence → recommendation
labels + confidence shipped; batch UX → severity groups/confidence sort/patch
defaults shipped; performance → cache/coalescing/backoff shipped and the one
scale question is documented above; resilience → offline/stale/backoff/
self-healing cache shipped; team collaboration → deliberately post-Gate-2;
ecosystem/CLI → post-launch, demand-gated), no change meets the order's own
four-part bar (high impact AND sound AND maintainable AND differentiating)
better than shipping v1.0.0 does. The highest-impact action available to this
project remains the owner checklist.
