#!/usr/bin/env bash
# Downloads the FFmpeg FATE samples that sonaudio's FATE tests need into <dir> (default: .fate-samples/ at the project
# root), laid out as fate-suite.ffmpeg.org has them. Idempotent: a file that's already there and matches
# fate-samples.manifest is left alone. The manifest holds each file's SHA-256 and size; a file that isn't in it yet has
# them recorded on its first fetch, so commit the manifest after that.
#
# Usage: reference/fetch-fate-samples.sh [dir]
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
dir=${1:-$here/../.fate-samples}
manifest=$here/fate-samples.manifest
base=https://fate-suite.ffmpeg.org

files=()
for vector in 01 02 03 04 05 06 07 08 09 10 11 12; do
    files+=("opus/testvector$vector.mka" "opus/testvector$vector.dec")
done
files+=(
    opus/testvector05_v2.dec opus/testvector06_v2.dec
    opus/silk-lbrr.mka opus/silk-lbrr.dec
    opus/silk-lbrr-mono.mka opus/silk-lbrr-mono.dec
    opus/tron.6ch.tinypkts.mka opus/tron.6ch.tinypkts.dec
    lossless-audio/inside.m4a
    audio-reference/luckynight_2ch_44kHz_s16.wav
    audio-reference/divertimenti_2ch_96kHz_s24.wav
)

touch "$manifest"
mkdir -p "$dir"

recorded() { grep -F "  $1" "$manifest" | head -n 1 || true; }

for file in "${files[@]}"; do
    target=$dir/$file
    line=$(recorded "$file")
    if [[ -f $target && -n $line ]]; then
        expected=$(echo "$line" | cut -d' ' -f1)
        if [[ $(sha256sum "$target" | cut -d' ' -f1) == "$expected" ]]; then
            continue
        fi
        echo "$file doesn't match the manifest, fetching it again"
    fi
    mkdir -p "$(dirname "$target")"
    echo "fetching $file"
    curl -fsSL --retry 3 -o "$target.part" "$base/$file"
    sum=$(sha256sum "$target.part" | cut -d' ' -f1)
    size=$(wc -c <"$target.part" | tr -d ' ')
    if [[ -z $line ]]; then
        echo "$sum  $file  $size" >>"$manifest"
    elif [[ $sum != $(echo "$line" | cut -d' ' -f1) ]]; then
        rm -f "$target.part"
        echo "$file doesn't match the manifest's checksum" >&2
        exit 1
    fi
    mv "$target.part" "$target"
done
echo "FATE samples are in $dir"
