# The reference environment: the FFmpeg and fpcalc that every sonaudio test reference comes from, built from source at
# pinned versions. Build it with `docker build -t sonaudio-ref reference` (reference/README.md has the rest).
#
#   FFmpeg      9.0.2   ffmpeg-9.0.2.tar.xz       sha256 8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
#   libopus     1.6.1   opus-1.6.1.tar.gz         sha256 6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
#   Chromaprint 1.6.2   chromaprint-1.6.2.tar.gz  sha256 d54ada62c16b2db396d3251ec54fc7a58909c163131a1cfcf7fa6407dbc14360
FROM debian:trixie-slim

ARG FFMPEG_VERSION=9.0.2
ARG FFMPEG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
ARG OPUS_VERSION=1.6.1
ARG OPUS_SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
ARG CHROMAPRINT_VERSION=1.6.2
ARG CHROMAPRINT_SHA256=d54ada62c16b2db396d3251ec54fc7a58909c163131a1cfcf7fa6407dbc14360

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates cmake curl nasm pkg-config python3 xz-utils \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# libopus, which FFmpeg only uses to encode the Opus fixtures: decoding is FFmpeg's native decoder.
RUN curl -fsSLO https://downloads.xiph.org/releases/opus/opus-${OPUS_VERSION}.tar.gz \
    && echo "${OPUS_SHA256}  opus-${OPUS_VERSION}.tar.gz" | sha256sum -c - \
    && tar xzf opus-${OPUS_VERSION}.tar.gz \
    && cd opus-${OPUS_VERSION} \
    && ./configure --prefix=/usr/local --disable-shared --enable-static --disable-doc --disable-extra-programs \
    && make -j"$(nproc)" && make install \
    && cd .. && rm -rf opus-${OPUS_VERSION}*

# FFmpeg's decoders, encoders, muxers and demuxers are all left on (the alac and opus decoders, the alac encoder, the
# mov/ipod, ogg, matroska, wav and flac formats), with libopus for encoding.
RUN curl -fsSLO https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz \
    && echo "${FFMPEG_SHA256}  ffmpeg-${FFMPEG_VERSION}.tar.xz" | sha256sum -c - \
    && tar xJf ffmpeg-${FFMPEG_VERSION}.tar.xz \
    && cd ffmpeg-${FFMPEG_VERSION} \
    && PKG_CONFIG_PATH=/usr/local/lib/pkgconfig ./configure --prefix=/usr/local --disable-autodetect --disable-doc \
        --disable-ffplay --enable-libopus --enable-static --disable-shared --extra-libs=-lm \
    && make -j"$(nproc)" && make install \
    && cd .. && rm -rf ffmpeg-${FFMPEG_VERSION}*

# fpcalc, linked to the FFmpeg above (so with the same decoders), using Chromaprint's default FFT, FFmpeg's libavutil tx.
RUN curl -fsSLO https://github.com/acoustid/chromaprint/releases/download/v${CHROMAPRINT_VERSION}/chromaprint-${CHROMAPRINT_VERSION}.tar.gz \
    && echo "${CHROMAPRINT_SHA256}  chromaprint-${CHROMAPRINT_VERSION}.tar.gz" | sha256sum -c - \
    && tar xzf chromaprint-${CHROMAPRINT_VERSION}.tar.gz \
    && cd chromaprint-${CHROMAPRINT_VERSION} \
    && PKG_CONFIG_PATH=/usr/local/lib/pkgconfig cmake -DCMAKE_BUILD_TYPE=Release -DBUILD_TOOLS=ON -DBUILD_TESTS=OFF \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX=/usr/local -DCMAKE_CXX_STANDARD_LIBRARIES="-L/usr/local/lib -lopus -lm" . \
    && make -j"$(nproc)" && make install \
    && cd .. && rm -rf chromaprint-${CHROMAPRINT_VERSION}*

# Tools that only inspect or tag, from Debian trixie: opusinfo (opus-tools 0.2-1) reads an Ogg Opus file's headers and
# packets, and mutagen (python3-mutagen 1.47.0-1) writes the MP4 freeform atoms FFmpeg doesn't.
RUN apt-get update \
    && apt-get install -y --no-install-recommends opus-tools python3-mutagen \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
