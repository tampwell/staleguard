#!/usr/bin/env bash
# Draft release notes from conventional commits since the last tag.
# Usage: ./scripts/generate-changelog.sh [since-ref]   (default: last tag)
# Output: markdown on stdout — ALWAYS edited by the owner before publishing.
set -euo pipefail
cd "$(dirname "$0")/.."

SINCE="${1:-$(git describe --tags --abbrev=0 2>/dev/null || echo "")}"
RANGE=${SINCE:+$SINCE..HEAD}
VERSION=$(grep -E "^version" gradle.properties | sed 's/.*= *//')

echo "# Staleguard v$VERSION release notes (DRAFT — owner edits before publishing)"
echo
section() {
  local title="$1" pattern="$2"
  local lines
  lines=$(git log $RANGE --pretty=format:'- %s' | grep -E "^- $pattern" | sed -E "s/^- $pattern(\([^)]*\))?:? ?/- /" || true)
  if [ -n "$lines" ]; then
    echo "## $title"
    echo "$lines"
    echo
  fi
}
section "New features" "feat"
section "Fixes" "fix"
section "Performance" "perf"
section "Documentation" "docs"
echo "_Generated from commits ${SINCE:-<repo start>}..HEAD_"
