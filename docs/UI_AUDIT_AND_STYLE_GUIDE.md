# UI audit + style guide

*Grounded in the actual codebase as of this commit — not aspirations. Update
when UI surfaces change.*

## Inventory (what exists, where, state)

| Surface | File(s) | State |
|---|---|---|
| Editor squiggles | `DependencyFreshnessInspection`, `gradle/Gradle*Inspection` | WARNING for minor/patch, WEAK_WARNING for major/qualifier/abandonment — deliberate inversion: loudness tracks actionability, not size |
| Tooltips | inspection message strings (bundle) | Plain text with humanized ages ("released 2 months ago") and dated abandonment. Plain-text is deliberate: HTML links in inspection tooltips are unreliable across popup modes; the changelog action lives in the quick-fix list instead |
| Quick fixes | 6 classes, all `Iconable` | Edit icon (bumps/catalog), Cancel (ignore), Vcs.History (changelog); names carry specifics ("Update version to 2.14.0", "Set property 'x' to y") |
| Tool window: Statistics | `StaleguardStatsPanel` | Tree (summary → modules → actionable rows), double-click navigation, Refresh All toolbar, positive all-fresh/empty states |
| Tool window: Timeline | `TimelinePanel` | Custom-painted 5y bars, JBColor age palette, legend, tooltips, PNG export |
| Batch dialog | `BatchUpdateDialog` | Severity groups, confidence-sorted rows with scores, patch pre-selected, per-group select-all |
| Confirmation dialogs | property/catalog blast-radius | `MessageDialogBuilder.yesNo` + don't-ask (property path) |
| Settings | `StaleguardConfigurable` (Kotlin UI DSL) | Offline, prereleases, abandonment(+years, enabledIf), ignore list, cache stats+clear; comments under each option |
| Notifications | balloon group "Staleguard" | batch results, offline notice (once/session), onboarding (once/install) |
| Icons | `pluginIcon.svg` + `pluginIcon_dark.svg` | Shield + green up-arrow; auto-discovered by filename convention (there is NO `<icon>` element in plugin.xml — that element does not exist) |

## Conventions (follow these)

- **Colors**: only `JBColor(light, dark)` or theme constants — never bare
  `Color(...)`. Audited clean; keep it that way (`grep -rn "Color(" | grep -v JBColor`).
- **Icons**: platform `AllIcons.*` only; no custom SVGs besides the plugin icon.
  16px contexts (toolbars, intentions) use them as-is.
- **Strings**: every user-visible string in `StaleguardBundle.properties`.
  Sentence-style, specific ("Update catalog version 'gson' to 2.14.0"), no
  jargon like "maven-metadata.xml" in user-facing text.
- **Quick fixes**: implement `Iconable`; name states the exact outcome.
- **Empty states**: never a blank panel — positive message + what to do next.
- **Notifications**: max one per user action; once-per-session for ambient
  warnings (offline); once-per-install for onboarding. Never nag.
- **Dialogs**: `DialogWrapper`/`MessageDialogBuilder`; destructive or wide-
  impact edits get a confirmation listing what's affected.
- **Layouts**: Kotlin UI DSL (`panel { }`) for forms; `SimpleToolWindowPanel`
  + toolbar for tool windows.
- **Keyboard**: no default keybindings claimed — collision risk with user
  keymaps outweighs convenience (a frequent 1-star complaint against plugins
  that steal shortcuts). All actions reachable via Find Action; users bind
  their own in Settings → Keymap.

## Declined from the session-10 work order (reasons)

- Competitor-plugin "UI research" with described animations: cannot observe
  GUIs; refusing to fabricate findings. Real research input: rival plugins'
  REVIEW TEXT (already mined in docs/REFERENCE.md).
- Emoji in tooltips/labels: off-brand for IDE tooling; platform norm is icons.
- Stats table rework (split pane, CSV, sorting columns): tree works; rework is
  v1.1 material once real users say what they need.
- Default keyboard shortcuts: see Conventions.
- HTML tooltips with inline links: unreliable rendering across tooltip modes;
  the changelog quick fix covers the need.
