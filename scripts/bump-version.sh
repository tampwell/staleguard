#!/usr/bin/env bash
# Bump the plugin version: ./scripts/bump-version.sh [major|minor|patch]
# Updates gradle.properties, adds a CHANGELOG section, commits, and tags.
set -euo pipefail
cd "$(dirname "$0")/.."

PART="${1:?usage: bump-version.sh [major|minor|patch]}"
CURRENT=$(grep -E "^version" gradle.properties | sed 's/.*= *//')
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT"

case "$PART" in
  major) MAJOR=$((MAJOR+1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR+1)); PATCH=0 ;;
  patch) PATCH=$((PATCH+1)) ;;
  *) echo "unknown part: $PART" >&2; exit 1 ;;
esac
NEW="$MAJOR.$MINOR.$PATCH"

sed -i "s/^version = .*/version = $NEW/" gradle.properties
DATE=$(date +%Y-%m-%d)
sed -i "s/^## \[Unreleased\]/## [Unreleased]\n\n## [$NEW] - $DATE/" CHANGELOG.md

git add gradle.properties CHANGELOG.md
git commit -m "chore: bump version to $NEW"
git tag "v$NEW"
echo "Bumped $CURRENT -> $NEW (commit + tag v$NEW created; push with: git push && git push --tags)"
