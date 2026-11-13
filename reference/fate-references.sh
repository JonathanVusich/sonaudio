#!/usr/bin/env bash
# Writes the references for the FATE samples into /fate/sonaudio, inside the reference image, with the FATE directory
# (see fetch-fate-samples.sh) at /fate: use make-fate-references.sh, which starts it there. It stops at the first check
# that fails, except the lossless remux of an Opus file, which it reports in /fate/sonaudio/report.txt and goes on.
set -euo pipefail

fate=/fate
out=$fate/sonaudio
# What FATE's tests/ref/fate/lossless-alac holds, at FFmpeg's n9.0.2 tag.
inside_md5=d0beb768d860b4776358077dd9fcb1e9
opus_names=(testvector01 testvector02 testvector03 testvector04 testvector05 testvector06 testvector07 testvector08
    testvector09 testvector10 testvector11 testvector12 silk-lbrr silk-lbrr-mono tron.6ch.tinypkts)

mkdir -p "$out"
report=$out/report.txt
echo "FATE references made with $(ffmpeg -version | head -n 1)" >"$report"

quiet_ffmpeg() {
    ffmpeg -hide_banner -loglevel error -y "$@"
}

# packet_hashes <file>: each audio packet's size and MD5, as FFmpeg's framemd5 muxer writes them.
packet_hashes() {
    ffmpeg -hide_banner -loglevel error -i "$1" -map 0:a:0 -c copy -f framemd5 - | grep -v '^#' | awk -F', *' '{print $5, $6}'
}

md5_of_file() {
    md5sum "$1" | cut -d' ' -f1
}

# remux_opus <name>: Ogg Opus from the Matroska file's packets, which must be the same packets.
remux_opus() {
    local name=$1
    quiet_ffmpeg -i "$fate/opus/$name.mka" -map 0:a:0 -c copy -f opus "$out/$name.opus"
    if [[ $(packet_hashes "$fate/opus/$name.mka") == "$(packet_hashes "$out/$name.opus")" ]]; then
        echo "$name: remuxed to Ogg with identical packets" >>"$report"
    else
        echo "$name: the remux's packets DIFFER from the Matroska file's" >>"$report"
    fi
}

# FATE's command for each Opus sample (tests/fate/opus.mak): ffmpeg -i <file>.mka -f s16le -af aresample -
decode_opus() {
    local name=$1
    quiet_ffmpeg -i "$fate/opus/$name.mka" -f s16le -af aresample "$out/$name.s16le"
    ffmpeg -hide_banner -loglevel error -i "$out/$name.opus" -f s16le -af aresample "$out/$name.ogg.s16le" 2>/dev/null
    if cmp -s "$out/$name.ogg.s16le" "$out/$name.s16le"; then
        rm "$out/$name.ogg.s16le"
        echo "$name: the Ogg file decodes to the same samples as the Matroska file" >>"$report"
    else
        echo "$name: the Ogg file's decode DIFFERS from the Matroska file's (the packets are the same, but Matroska times them in milliseconds, so the Ogg file's end and start trim differ): its own is $name.ogg.s16le" >>"$report"
    fi
}

make_opus() {
    local name
    for name in "${opus_names[@]}"; do
        remux_opus "$name"
        decode_opus "$name"
    done
}

# encode_alac <name> <source wav> <sample format> [encoder options]: what FATE's fate-alac-* cases encode (their
# enc_dec_pcm writes QuickTime's mov, this the same stream in the ipod muxer's m4a), and that it decodes to the source.
encode_alac() {
    local name=$1 source=$2 format=$3
    shift 3
    quiet_ffmpeg -i "$source" -c:a alac "$@" -f ipod "$out/$name.m4a"
    local decoded
    decoded=$(ffmpeg -hide_banner -loglevel error -i "$out/$name.m4a" -f "$format" - | md5sum | cut -d' ' -f1)
    if [[ $decoded != "$(md5_of_file "$out/$(basename "$source" .wav).$format")" ]]; then
        echo "$name doesn't decode to its source" >&2
        exit 1
    fi
    echo "$name: decodes to the samples of its source" >>"$report"
}

make_alac() {
    local sixteen=$fate/audio-reference/luckynight_2ch_44kHz_s16.wav
    local twenty_four=$fate/audio-reference/divertimenti_2ch_96kHz_s24.wav
    quiet_ffmpeg -i "$sixteen" -f s16le "$out/luckynight_2ch_44kHz_s16.s16le"
    quiet_ffmpeg -i "$twenty_four" -f s24le "$out/divertimenti_2ch_96kHz_s24.s24le"
    local level
    for level in 0 1 2; do
        encode_alac "alac-16-level-$level" "$sixteen" s16le -compression_level "$level"
        encode_alac "alac-24-level-$level" "$twenty_four" s24le -compression_level "$level"
    done
    encode_alac alac-16-lpc-orders "$sixteen" s16le -min_prediction_order 1 -max_prediction_order 30
    encode_alac alac-24-lpc-orders "$twenty_four" s24le -min_prediction_order 1 -max_prediction_order 30
}

# FATE's fate-lossless-alac: the MD5 of ffmpeg -i inside.m4a -f s16le -af aresample <file>.
make_inside() {
    quiet_ffmpeg -i "$fate/lossless-audio/inside.m4a" -f s16le -af aresample "$out/inside.s16le"
    local md5
    md5=$(md5_of_file "$out/inside.s16le")
    echo "$md5" >"$out/inside.md5"
    if [[ $md5 != "$inside_md5" ]]; then
        echo "inside.m4a decodes to MD5 $md5, not FATE's $inside_md5" >&2
        exit 1
    fi
    echo "inside.m4a: decode MD5 $md5, as tests/ref/fate/lossless-alac has it" >>"$report"
}

# fpcalc's output (its DURATION and FINGERPRINT lines) for each file it can fingerprint.
make_fingerprints() {
    local file
    for file in "$out"/*.opus "$out"/*.m4a "$fate/lossless-audio/inside.m4a"; do
        if fpcalc "$file" >"$out/$(basename "$file").fpcalc.txt" 2>/dev/null; then
            echo "$(basename "$file"): fingerprinted" >>"$report"
        else
            rm -f "$out/$(basename "$file").fpcalc.txt"
            echo "$(basename "$file"): fpcalc couldn't fingerprint it" >>"$report"
        fi
    done
}

make_opus
make_alac
make_inside
make_fingerprints
cat "$report"
