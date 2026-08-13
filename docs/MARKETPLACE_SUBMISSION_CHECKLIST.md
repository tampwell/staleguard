# Marketplace submission checklist

## Automated verifications (state as of this session)

- [x] 153 unit tests passing (pure-core exhaustive; one platform canary)
- [x] Plugin verifier: Compatible; 0 internal API usages
      (residual notes: 4 deprecated + 6 experimental flags are inherited
      ToolWindowFactory interface defaults, not our calls; 1 cosmetic K2
      warning — declaration lives in the optional Kotlin config file where
      the Kotlin plugin reads it, verifier only scans the main plugin.xml)
- [x] NOTICE file (Apache Maven ComparableVersion attribution)
- [x] No telemetry, no accounts, no CVE features (hard rules held)
- [x] Version set to 1.0.0
- [ ] Distribution ZIP built and contents inspected (this session, after sandbox closes)
- [ ] ZIP must NOT contain kotlin-stdlib/coroutines jars (platform-provided)

## Verification gaps (honest list)

- [ ] Kotlin DSL visual pass in sandbox (owner: squiggles on libs.* refs,
      catalog fix writes to libs.versions.toml, shared-key dialog says
      2 libraries for 'jackson') — see docs/KOTLIN_DSL_VERIFICATION.md
- [ ] Manual install-from-ZIP test (owner: Settings → Plugins → ⚙ →
      Install Plugin from Disk → restart → open a Maven project)

## Owner tasks (blocking, non-code)

- [ ] Rewrite plugin.xml description in your own voice (facts pre-verified;
      keep the first 40 characters English — they are the preview card)
- [ ] Rewrite docs/MARKETPLACE_DESCRIPTION.md draft into the marketplace page copy
- [ ] Professional logo to replace the placeholder pluginIcon.svg
      (must not resemble JetBrains marks or the template default)
- [ ] 4 screenshots, 1280×800 (shot list in docs/MARKETPLACE_DESCRIPTION.md)
- [ ] Parent: JetBrains vendor profile — Tampwell LLC, staleguard@tampwell.com,
      https://tampwell.com, trader declaration (public disclosure of LLC
      contact details is mandatory under the DSA even for free plugins)
- [ ] Upload ZIP, submit for review (~2 business days typical)

## Post-submission

- [ ] Respond to marketplace review comments within 24h
- [ ] Do NOT announce anywhere until owner writes the announcement personally
- [ ] Watch JetBrains Package Checker changelog quarterly (platform risk)
- [ ] v1.1 candidates (from PROGRESS.md deferred list): stats window Gradle
      rows, timeline zoom/click-to-inspect, batch diff preview, CSV export,
      kts interpolated-version resolution, Gradle plugins{} block

## Approval-guideline compliance (verified)

- Name "Staleguard": no "Plugin"/"IntelliJ"/"JetBrains", no third-party marks
- Description first 40 chars English
- Icon: distinct from template default and JetBrains logos (placeholder OK for
  review, professional version strongly recommended before launch)
- No interference with IDE licensing/trial mechanisms
- Vendor profile completeness required at upload time
