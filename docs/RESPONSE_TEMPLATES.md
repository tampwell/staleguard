# Response template DRAFTS

> All public replies are posted by the OWNER in his own words (hard rule).
> These are private drafts to adapt — never paste verbatim. Every technical
> claim below is grounded in what the plugin actually does.

## "No squiggles appear" (the most likely report)

Ask for: IDE version, build system, and the `idea.log` lines containing
"Staleguard:" (Help → Show Log in Explorer). Those lines answer everything:
- `checked <file>: N problem(s), X hit(s), Y miss(es)` → inspection ran; if
  N=0 all deps may genuinely be current (point to the tool window summary).
- `resolved <coords> -> FAILED` → network/proxy: point to the offline-mode
  setting and the once-per-session offline balloon; retries are automatic
  with backoff.
- No Staleguard lines at all → the build file was never opened in an editor
  (inspections run on open files), or the file isn't named
  pom.xml / build.gradle / build.gradle.kts.

## "Quick fix crashed / wrote the wrong thing"

P0. Ask for: the exact dependency coordinates + declared version text, build
file type, and the stack trace from idea.log. Note the write-back rules when
diagnosing: property-versioned deps edit the PROPERTY definition; catalog refs
edit [versions] in libs.versions.toml; managed/mixed versions get no fix at
all — a report of "it edited the wrong place" needs the raw declared text.

## "Does it scan for vulnerabilities/CVEs?"

No, by design — freshness and abandonment only. Point to JetBrains Package
Checker (bundled) or Snyk for CVEs; Staleguard complements them by flagging
outdated and abandoned libraries before they become security debt.

## "Why doesn't it suggest <newest version X>?"

Almost always: X is a prerelease (alpha/beta/rc/M/SNAPSHOT) and stable-only
suggestion is the default — the setting "Suggest prerelease versions" changes
it. Verified example to reuse: slf4j latest is a 2.1.0-alpha; Staleguard
suggests 2.0.x stable.

## "Wrong version ordering"

Ask for the two versions. Check against Maven's own rules before conceding a
bug (our comparator is a port of Maven's ComparableVersion, locked by Maven's
own test suite): 1.0-rc1 < 1.0 < 1.0-sp; 1a1 == 1-alpha-1; case-insensitive.

## 1-star review

Never defensive. Ask them to email staleguard@tampwell.com with what they
expected vs. saw + IDE version; commit to a response inside 24h. If resolved,
it's fine to ask — once — whether they'd consider updating the review.

## Feature request

Thank, then ask: how often would you use it, what's the current workaround,
does it block team adoption? Route to a GitHub issue (once repo is public);
prioritization happens via docs/V1.1_PLANNING.md, demand-first.

## Claims to NEVER make
- "Open source" (repo private until the owner decides otherwise)
- Anything about CVE detection, AI, or download-count comparisons
- A "Generate Diagnostic Report" feature (does not exist — the diagnostic IS
  the idea.log Staleguard lines)
