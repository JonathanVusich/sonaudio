#!/usr/bin/env bash
# Prepares everything the FATE tests need, locally: builds the reference image if it's missing or out of date, fetches the
# FATE samples and makes their references. Nothing already there and up to date is fetched or made again.
#
# Usage: reference/prepare-fate.sh [dir]   (default: .fate-samples/ at the project root; point FATE_SAMPLES at it
# if it's elsewhere)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
dir=${1:-$here/../.fate-samples}

mkdir -p "$dir"
"$here/fetch-fate-samples.sh" "$dir"
"$here/make-fate-references.sh" "$dir"
echo "FATE samples are ready in $(cd "$dir" && pwd)"
