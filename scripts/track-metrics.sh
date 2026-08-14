#!/usr/bin/env bash
# Standalone marketplace metrics tracker — runs on the DEV machine, ships
# nothing to users. Appends one CSV row per run from the public plugin API.
# Usage: ./scripts/track-metrics.sh [pluginId]   (id assigned at first publish)
# Suggested cadence: 1-2x daily during launch month (be polite to the API).
set -euo pipefail
cd "$(dirname "$0")/.."

PLUGIN_ID="${1:?usage: track-metrics.sh <numeric marketplace plugin id>}"
OUT="staleguard-metrics.csv"
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

curl -sf "https://plugins.jetbrains.com/api/plugins/$PLUGIN_ID" -o "$TMP"

python3 - "$TMP" "$OUT" <<'EOF'
import json, sys, datetime, pathlib
data = json.load(open(sys.argv[1], encoding="utf-8"))
row = [
    datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    str(data.get("downloads", "")),
    str(data.get("rating", "")),
    str((data.get("vendor") or {}).get("name", "")).replace(",", " "),
    str(data.get("cdate", "")),
]
out = pathlib.Path(sys.argv[2])
new = not out.exists()
with out.open("a", encoding="utf-8") as f:
    if new:
        f.write("timestamp_utc,downloads,rating,vendor,last_update_ms\n")
    f.write(",".join(row) + "\n")
print("Appended:", ",".join(row))
EOF