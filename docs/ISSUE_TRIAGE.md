# Issue triage guidelines (post-launch)

## Intake + labels (GitHub, once repo is public)
Channels: GitHub issues (primary) · staleguard@tampwell.com · marketplace
reviews · social mentions. Labels: bug / enhancement / question / duplicate /
wontfix + P0-P3. Every intake gets: severity call within 24h, a reply from
the templates in RESPONSE_TEMPLATES.md (owner-adapted), and a line in
PROGRESS.md if P0/P1. Close the loop on every fix: comment "fixed in vX",
thank, close.

## Response targets
- P0 Critical (IDE crash/freeze, corrupts build files, security): 24h
- P1 Major (feature broken, wrong version suggestions, proxy-blocked): 48h
- P2 Minor (cosmetic, exotic-file edge cases, unclear messages): 1 week
- P3 Enhancement (feature requests): acknowledge within a week; prioritize
  against Gate 2/3 data, not loudest-voice

## Classification notes specific to Staleguard
- "No squiggles" reports: first ask for the idea.log lines containing
  "Staleguard:" — the diagnostic logging answers whether the inspection ran,
  what it found, and whether the network worked. Most such reports will be
  offline/proxy cases (point to the offline-mode setting) or unopened build
  files (inspection runs on open editors).
- Wrong-version reports: ask for groupId:artifactId + declared version; check
  against MavenVersionTest cases before assuming a bug — Maven ordering
  surprises people (1.0-rc1 < 1.0 < 1.0-sp).
- NEVER add telemetry to diagnose remotely — logs come from users, by hand.

## Channel policy
- GitHub issues = primary (public record; repo must be public by launch).
- Marketplace reviews: owner responds to every review in his own words;
  negative reviews get a request for details + an issue link.
- All public replies are written by the owner. Agent may draft privately.

## Weekly rhythm (first month)
- Check installs + reviews twice weekly; log numbers in PROGRESS.md.
- Collect feature requests verbatim in a v1.1 candidates list — ship the top
  REQUESTED items, not the most interesting ones to build.
