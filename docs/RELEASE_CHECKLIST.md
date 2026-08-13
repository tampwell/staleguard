# Release checklist (every version)

## Before release
- [ ] `./gradlew.bat clean build` green (all tests)
- [ ] `./gradlew.bat verifyPlugin` — Compatible, zero internal API usage
- [ ] Manual smoke test in sandbox (`runIde` + a sample project): squiggles,
      one quick fix, batch dialog, tool window
- [ ] PROGRESS.md current; CHANGELOG.md [Unreleased] section accurate
- [ ] All user-visible strings in StaleguardBundle (grep for stray literals)
- [ ] Release notes drafted (`./scripts/generate-changelog.sh`) and REWRITTEN
      by the owner in his own voice

## Release
- [ ] `./scripts/bump-version.sh [major|minor|patch]`
- [ ] `./gradlew.bat clean buildPlugin` → inspect zip: our jars only, no
      kotlin-stdlib/coroutines, correct <version>, pluginIcon present
- [ ] Install-from-disk test in a real IDE
- [ ] `git push && git push --tags`
- [ ] Upload zip on JetBrains Marketplace; owner reviews listing text
- [ ] Submit (review typically ~2 business days)

## After approval
- [ ] Fresh-IDE install from marketplace works
- [ ] Owner announces (his words only) — r/java, JetBrains forum, etc.
- [ ] Respond to reviews/issues within 24-48h (see ISSUE_TRIAGE.md)
- [ ] Track installs weekly against the gates:
      Gate 2 = 1,000 installs by month 4 (<300 → stop, reassess thesis)
      Gate 3 = 5,000 + 10 unsolicited requests by month 8 → design paid tier
- [ ] Check JetBrains Package Checker changelog (platform risk) quarterly
