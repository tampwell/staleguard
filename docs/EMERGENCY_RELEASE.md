# Emergency release + rollback

## Critical bug definition (P0)
IDE crash/freeze caused by us; corrupting a build file; security issue;
inspection completely dead for all users.

## Hotfix path (target: same day)
1. Reproduce (`runIde` + sample projects) → write the failing test FIRST.
2. Fix; `./gradlew.bat clean build verifyPlugin` green (build cache stays OFF).
3. `./scripts/bump-version.sh patch`; CHANGELOG entry (owner words for the
   public-facing notes).
4. `./gradlew.bat buildPlugin`; install-from-disk check in a real IDE.
5. Owner uploads the new zip on the plugin's Versions page and submits.
   NOTE: marketplace review applies to updates too (typically ~2 business
   days; an "expedited/critical" lane is NOT a confirmed marketplace feature
   — plan around normal review time, and offer the zip directly to affected
   reporters as the immediate fix: Settings → Plugins → ⚙ → Install from Disk).
6. Reply to every affected reporter with the zip + workaround.
7. Post-mortem within 48h: why did tests miss it; add the regression test;
   note in PROGRESS.md.

## Rollback
Facts to verify at first need (marketplace mechanics not fully public):
vendors can manage listed versions from the plugin page; whether an old
version can be re-promoted vs. only uploading a new one is untested by us.
Practical order of operations:
1. Prefer FIX-FORWARD: revert the offending commit, patch-bump, resubmit —
   fastest lane we fully control.
2. Meanwhile: pin a known-good zip for manual install and reply to reporters
   with it.
3. Owner posts a short known-issue note (his words) wherever users will look
   (plugin description update requires a new version upload — so usually the
   GitHub README once public).

## Prevention checklist deltas
Any P0 escape adds a line to docs/RELEASE_CHECKLIST.md — the checklist only
grows from real failures, not speculation.
