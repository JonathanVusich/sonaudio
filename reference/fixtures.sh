#!/usr/bin/env bash
# Writes the committed ALAC and Opus fixtures and their references. It runs inside the reference image, with the
# repository at /repo: use make-fixtures.sh, which starts it there. Every source is synthetic (aevalsrc with its own
# seeded generator) and every encode is bit-exact, so running it again rewrites the same bytes.
#
# The fixtures are short (a fraction of a second) because their references are raw samples that gzip hardly shrinks.
set -euo pipefail

samples=/repo/src/test/resources/dev/javax/sonaudio/samples
fingerprints=/repo/src/test/resources/dev/javax/sonaudio/fingerprint
tools=/repo/reference
alac=$samples/alac
opus=$samples/opus
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
rm -rf "$alac" "$opus" "$fingerprints"/alac_* "$fingerprints"/opus_*
mkdir -p "$alac" "$opus"

bitexact=(-bitexact -fflags +bitexact -map_metadata -1)

quiet_ffmpeg() {
    ffmpeg -hide_banner -loglevel error -y "$@"
}

# signal <channels> <noise amplitude>: aevalsrc expressions for tones that sweep in level, plus noise from the
# generator's own seeded random(), each channel with its own pitches and noise.
signal() {
    local channels=$1 noise=$2 expressions=() channel
    for ((channel = 0; channel < channels; channel++)); do
        expressions+=("0.3*sin(2*PI*(440+110*$channel)*t)+0.2*sin(2*PI*(1250+97*$channel)*t)*(0.6+0.4*sin(2*PI*3*t))+$noise*(2*random($channel)-1)")
    done
    (
        IFS='|'
        echo "${expressions[*]}"
    )
}

# clicks <channels>: a tone under clicks of noise every 110 ms, for CELT's transients.
clicks() {
    local channels=$1 expressions=() channel
    for ((channel = 0; channel < channels; channel++)); do
        expressions+=("0.25*sin(2*PI*(330+70*$channel)*t)+0.8*lt(mod(t+0.01*$channel,0.11),0.0006)*(2*random($channel)-1)")
    done
    (
        IFS='|'
        echo "${expressions[*]}"
    )
}

# synthesize <expressions> <layout> <rate> <seconds> <codec> <out.wav>
synthesize() {
    quiet_ffmpeg -f lavfi -i "aevalsrc='$1':c=$2:s=$3:d=$4" -c:a "$5" "${bitexact[@]}" "$6"
}

# raw_decode <file> <format>: the file as FFmpeg decodes it, as raw little-endian samples, on stdout.
raw_decode() {
    ffmpeg -hide_banner -loglevel error -i "$1" -f "$2" -
}

md5_of_decode() {
    raw_decode "$1" "$2" | md5sum | cut -d' ' -f1
}

# store_reference <file> <sample format> <reference name in the same directory>
store_reference() {
    raw_decode "$1" "$2" | gzip -n -9 >"$(dirname "$1")/$3.pcm.gz"
}

# alac_fixture <name> <source wav> <sample format> [encoder options]: encodes the source and checks that FFmpeg decodes
# it back to the source's samples, which a lossless codec must.
alac_fixture() {
    local name=$1 source=$2 format=$3
    shift 3
    quiet_ffmpeg -i "$source" -c:a alac "$@" "${bitexact[@]}" -f ipod "$alac/$name.m4a"
    local decoded source_samples
    decoded=$(md5_of_decode "$alac/$name.m4a" "$format")
    source_samples=$(md5_of_decode "$source" "$format")
    if [[ $decoded != "$source_samples" ]]; then
        echo "$name doesn't decode to its source" >&2
        exit 1
    fi
}

# opus_fixture <name> <source wav> [encoder options]: encodes the source with libopus and writes FFmpeg's decode of
# it (its native decoder, s16le at 48 kHz) as the reference.
opus_fixture() {
    local name=$1 source=$2
    shift 2
    quiet_ffmpeg -i "$source" -c:a libopus "$@" "${bitexact[@]}" "$opus/$name.opus"
    store_reference "$opus/$name.opus" s16le "$name"
}

make_alac() {
    # 0.5 s: 22050 samples, so the last of 6 frames of 4096 samples holds 1570.
    synthesize "$(signal 2 0.002)" stereo 44100 0.5 pcm_s16le "$work/stereo16.wav"
    alac_fixture stereo16_level0 "$work/stereo16.wav" s16le -compression_level 0
    alac_fixture stereo16_level1 "$work/stereo16.wav" s16le -compression_level 1
    alac_fixture stereo16_level2 "$work/stereo16.wav" s16le -compression_level 2
    alac_fixture stereo16_lpc_orders "$work/stereo16.wav" s16le -min_prediction_order 1 -max_prediction_order 30
    store_reference "$alac/stereo16_level1.m4a" s16le stereo16

    # 0.25 s at 96 kHz: 24000 samples, 5.9 frames.
    synthesize "$(signal 2 0.0001)" stereo 96000 0.25 pcm_s24le "$work/stereo24.wav"
    alac_fixture stereo24_level0 "$work/stereo24.wav" s24le -compression_level 0
    alac_fixture stereo24_lpc_orders "$work/stereo24.wav" s24le -min_prediction_order 1 -max_prediction_order 30
    store_reference "$alac/stereo24_level0.m4a" s24le stereo24

    synthesize "$(signal 1 0.002)" mono 44100 0.4 pcm_s16le "$work/mono16.wav"
    alac_fixture mono16 "$work/mono16.wav" s16le
    store_reference "$alac/mono16.m4a" s16le mono16

    synthesize "$(signal 6 0.002)" 5.1 44100 0.2 pcm_s16le "$work/surround16.wav"
    alac_fixture surround51_16 "$work/surround16.wav" s16le
    store_reference "$alac/surround51_16.m4a" s16le surround51_16

    synthesize "$(signal 1 0.002)" mono 8000 0.8 pcm_s16le "$work/rate8000.wav"
    alac_fixture mono16_8khz "$work/rate8000.wav" s16le
    store_reference "$alac/mono16_8khz.m4a" s16le mono16_8khz

    synthesize "$(signal 1 0.002)" mono 192000 0.2 pcm_s16le "$work/rate192000.wav"
    alac_fixture mono16_192khz "$work/rate192000.wav" s16le
    store_reference "$alac/mono16_192khz.m4a" s16le mono16_192khz

    # Full-scale white noise, which costs more coded than as it is, so the encoder writes it uncompressed.
    # (aevalsrc's random() isn't white enough for that: it repeats itself.)
    quiet_ffmpeg -f lavfi -i "anoisesrc=r=44100:d=0.15:c=white:a=0.98:seed=1" \
        -f lavfi -i "anoisesrc=r=44100:d=0.15:c=white:a=0.98:seed=2" \
        -filter_complex "[0][1]join=inputs=2:channel_layout=stereo" -c:a pcm_s16le "${bitexact[@]}" "$work/escape.wav"
    alac_fixture escape_frames "$work/escape.wav" s16le
    store_reference "$alac/escape_frames.m4a" s16le escape_frames

    make_alac_layouts_and_tags
}

# The same 0.15 s with moov after mdat, with moov before it, and with tags: the last as FFmpeg writes the tags it can,
# then mutagen the freeform atoms it can't.
make_alac_layouts_and_tags() {
    synthesize "$(signal 2 0.002)" stereo 44100 0.15 pcm_s16le "$work/short16.wav"
    alac_fixture moov_after_mdat "$work/short16.wav" s16le
    alac_fixture moov_before_mdat "$work/short16.wav" s16le -movflags +faststart
    alac_fixture tagged "$work/short16.wav" s16le -movflags +faststart \
        -metadata title="Test Title" -metadata artist="Test Artist" -metadata album_artist="Test Album Artist" \
        -metadata album="Test Album" -metadata track=3/12 -metadata disc=1/2 -metadata date=2020-05-06 \
        -metadata composer="Test Composer"
    python3 "$tools/tag-fixtures.py" alac "$alac/tagged.m4a"
    store_reference "$alac/moov_after_mdat.m4a" s16le short16
    if [[ $(md5_of_decode "$alac/tagged.m4a" s16le) != "$(md5_of_decode "$alac/moov_before_mdat.m4a" s16le)" ]]; then
        echo "tagging changed the audio" >&2
        exit 1
    fi
}

make_opus_silk() {
    local bandwidth cutoff mono_rate stereo_rate duration
    for bandwidth in nb:4000:10k:16k mb:6000:12k:20k wb:8000:16k:24k; do
        IFS=: read -r bandwidth cutoff mono_rate stereo_rate <<<"$bandwidth"
        opus_fixture "silk_${bandwidth}_mono_20ms" "$work/voice1.wav" -application voip -cutoff "$cutoff" -b:a "$mono_rate" -frame_duration 20
        opus_fixture "silk_${bandwidth}_stereo_20ms" "$work/voice2.wav" -application voip -cutoff "$cutoff" -b:a "$stereo_rate" -frame_duration 20
    done
    for duration in 10 40 60; do
        opus_fixture "silk_nb_mono_${duration}ms" "$work/voice1.wav" -application voip -cutoff 4000 -b:a 10k -frame_duration "$duration"
        opus_fixture "silk_wb_stereo_${duration}ms" "$work/voice2.wav" -application voip -cutoff 8000 -b:a 24k -frame_duration "$duration"
    done
}

make_opus_hybrid() {
    local bandwidth cutoff mono_rate stereo_rate duration
    for bandwidth in swb:12000:24k:32k fb:20000:28k:36k; do
        IFS=: read -r bandwidth cutoff mono_rate stereo_rate <<<"$bandwidth"
        for duration in 10 20; do
            opus_fixture "hybrid_${bandwidth}_mono_${duration}ms" "$work/voice1.wav" -application voip -cutoff "$cutoff" -b:a "$mono_rate" -frame_duration "$duration"
        done
        opus_fixture "hybrid_${bandwidth}_stereo_20ms" "$work/voice2.wav" -application voip -cutoff "$cutoff" -b:a "$stereo_rate" -frame_duration 20
    done
}

make_opus_celt() {
    local duration
    for duration in 2.5 5 10 20; do
        opus_fixture "celt_lowdelay_mono_${duration/./_}ms" "$work/clicks1.wav" -application lowdelay -b:a 64k -frame_duration "$duration"
    done
    for duration in 2.5 20; do
        opus_fixture "celt_lowdelay_stereo_${duration/./_}ms" "$work/clicks2.wav" -application lowdelay -b:a 96k -frame_duration "$duration"
    done
    for duration in 5 20; do
        opus_fixture "celt_audio_mono_${duration}ms" "$work/voice1.wav" -application audio -b:a 128k -frame_duration "$duration"
    done
    opus_fixture celt_audio_stereo_20ms "$work/voice2.wav" -application audio -b:a 192k -frame_duration 20
}

# Packets of more than one frame: 40 ms of CELT as two 20 ms frames at a constant bitrate (code 1) and at a variable one
# (code 2 when the frames' sizes differ), 120 ms as six (code 3), and 120 ms of hybrid. Hybrid frames of 40 ms come out
# as two frames of different sizes (code 2), as do SILK's of 80 ms.
make_opus_multiframe() {
    opus_fixture multiframe_celt_40ms_cbr "$work/clicks1.wav" -application audio -b:a 96k -vbr off -frame_duration 40
    opus_fixture multiframe_celt_40ms_vbr "$work/clicks1.wav" -application audio -b:a 96k -vbr on -frame_duration 40
    opus_fixture multiframe_celt_120ms "$work/clicks1.wav" -application audio -b:a 96k -frame_duration 120
    opus_fixture multiframe_hybrid_120ms "$work/voice1.wav" -application voip -cutoff 12000 -b:a 24k -frame_duration 120
    opus_fixture multiframe_hybrid_40ms "$work/voice1.wav" -application voip -cutoff 12000 -b:a 24k -frame_duration 40
    opus_fixture multiframe_silk_80ms "$work/voice1.wav" -application voip -cutoff 8000 -b:a 16k -frame_duration 80
}

make_opus_special() {
    # The OpusHead gain is FFmpeg's opus_metadata bitstream filter's, in Q7.8 dB: -768 is -3 dB.
    quiet_ffmpeg -i "$opus/silk_wb_mono_20ms.opus" -c copy -bsf:a opus_metadata=gain=-768 "${bitexact[@]}" "$opus/output_gain.opus"
    store_reference "$opus/output_gain.opus" s16le output_gain

    synthesize "$(signal 6 0.01)" 5.1 48000 0.2 pcm_s16le "$work/surround.wav"
    opus_fixture surround51 "$work/surround.wav" -application audio -b:a 160k -mapping_family 1

    # Remuxed from silk_wb_mono_20ms, so these decode as it does.
    quiet_ffmpeg -i "$opus/silk_wb_mono_20ms.opus" -c copy "${bitexact[@]}" "$opus/tagged.opus"
    python3 "$tools/tag-fixtures.py" opus-picard "$opus/tagged.opus"
    quiet_ffmpeg -f lavfi -i "color=c=0x336699:s=16x16:d=1" -frames:v 1 -c:v mjpeg -bitexact "$work/cover.jpg"
    quiet_ffmpeg -i "$opus/silk_wb_mono_20ms.opus" -c copy "${bitexact[@]}" "$opus/r128_picture.opus"
    python3 "$tools/tag-fixtures.py" opus-r128 "$opus/r128_picture.opus" "$work/cover.jpg"
}

make_opus() {
    synthesize "$(signal 1 0.01)" mono 48000 0.24 pcm_s16le "$work/voice1.wav"
    synthesize "$(signal 2 0.01)" stereo 48000 0.24 pcm_s16le "$work/voice2.wav"
    synthesize "$(clicks 1)" mono 48000 0.24 pcm_s16le "$work/clicks1.wav"
    synthesize "$(clicks 2)" stereo 48000 0.24 pcm_s16le "$work/clicks2.wav"
    make_opus_silk
    make_opus_hybrid
    make_opus_celt
    make_opus_multiframe
    make_opus_special
}

# make_fingerprint <codec> <kind> <rate> <channels> <name> <directory> <extension> [encoder options]: 25 s of the chords
# the fingerprint tests use, in a file with fpcalc's fingerprint of it beside the existing ones.
make_fingerprint() {
    local codec=$1 kind=$2 rate=$3 channels=$4 name=$5 directory=$6 extension=$7
    shift 7
    python3 "$tools/gen-chords.py" "$kind" "$rate" "$channels" 25 "$work/$name.wav"
    quiet_ffmpeg -i "$work/$name.wav" -c:a "$codec" "$@" "${bitexact[@]}" "$directory/fingerprint_$name.$extension"
    fpcalc "$directory/fingerprint_$name.$extension" | sed -n 's/^FINGERPRINT=//p' | tr -d '\n' >"$fingerprints/$name.txt"
}

make_fingerprints() {
    make_fingerprint alac chords 8000 1 alac_chords_8000hz_mono "$alac" m4a -f ipod
    make_fingerprint alac noise 8000 1 alac_noise_8000hz_1ch "$alac" m4a -f ipod
    make_fingerprint libopus chords 48000 2 opus_chords_48000hz_stereo "$opus" opus -application audio -b:a 16k
    make_fingerprint libopus noise 48000 1 opus_noise_48000hz_1ch "$opus" opus -application audio -b:a 24k
}

make_alac
make_opus
make_fingerprints
echo "fixtures written"
