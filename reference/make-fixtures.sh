#!/usr/bin/env bash
# Regenerates the committed ALAC and Opus fixtures, their references and the fingerprint references, inside the reference
# image (built first if it's missing or out of date). The files are rewritten byte for byte, so `git status` after a run
# shows only what changed in how they're made.
#
# Usage: reference/make-fixtures.sh
set -euo pipefail

source "$(dirname "$0")/lib.sh"

ensure_image
run_in_image fixtures.sh
