#!/bin/bash
# Shipped tags whose host today's bundles must still run on: release-v* / hotfix-v* at or above
# compat/floor.txt, minus the tag a CI run is building (GITHUB_REF_TYPE=tag), which has not shipped.
# --json prints a JSON array (the CI matrix). COMPAT_SPREAD=N (N >= 2) keeps N of them spread evenly
# by index: always the floor and the newest, deduplicated; all of them when there are N or fewer.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
floor=$(tr -d '[:space:]' < "$here/floor.txt")
building=""
[ "${GITHUB_REF_TYPE:-}" = tag ] && building=$GITHUB_REF_NAME
tags=$(git -C "$here" tag --list 'release-v*' 'hotfix-v*' | while read -r tag; do
    [ "$tag" = "$building" ] && continue
    version=${tag#*-v}
    lowest=$(printf '%s\n%s\n' "$floor" "$version" | sort -V | head -n1)
    [ "$lowest" = "$floor" ] && printf '%s %s\n' "$version" "$tag"
done | sort -V | cut -d' ' -f2)
spread=${COMPAT_SPREAD:-0}
if [ "$spread" -gt 0 ]; then
    [ "$spread" -ge 2 ] || { echo "COMPAT_SPREAD must be at least 2 (the floor and the newest)" >&2; exit 1; }
    tags=$(printf '%s\n' $tags | awk -v k="$spread" '
        { tag[NR - 1] = $0 }
        END {
            n = NR
            for (i = 0; i < k && i < n; i++) {
                index_ = n <= k ? i : int(i * (n - 1) / (k - 1) + 0.5)
                if (!(index_ in seen)) { seen[index_] = 1; print tag[index_] }
            }
        }')
fi
if [ "${1:-}" = "--json" ]; then
    printf '%s\n' $tags | jq -R . | jq -sc 'map(select(. != ""))'
else
    printf '%s\n' $tags
fi
