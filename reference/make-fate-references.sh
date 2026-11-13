#!/usr/bin/env bash
# Writes the references for the FATE samples in <dir> (default: .fate-samples/ at the project root) into <dir>/sonaudio,
# inside the reference image: Opus files remuxed to Ogg, FFmpeg's decodes, FATE's ALAC encodes, inside.m4a's decode MD5
# and fpcalc's fingerprints. It does nothing when they were made already by this image, these scripts and these samples
# (the stamp file says so), so it reruns only after reference/ changes. Run fetch-fate-samples.sh first, or
# prepare-fate.sh, which does both.
#
# Usage: reference/make-fate-references.sh [dir]
set -euo pipefail

source "$(dirname "$0")/lib.sh"

FATE_DIR=$(cd "${1:-$repo_dir/.fate-samples}" && pwd)
export FATE_DIR
stamp_file=$FATE_DIR/sonaudio/STAMP
stamp=$(cat "$reference_dir/Dockerfile" "$reference_dir/fate-references.sh" "$reference_dir/fate-samples.manifest" | sha256sum | cut -d' ' -f1)

if [[ -f $stamp_file && $(cat "$stamp_file") == "$stamp" ]]; then
    echo "FATE references in $FATE_DIR/sonaudio are up to date"
    exit 0
fi

ensure_image
rm -rf "$FATE_DIR/sonaudio"
run_in_image fate-references.sh
echo "$stamp" >"$stamp_file"
