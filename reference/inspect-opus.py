#!/usr/bin/env python3
"""Prints what an Ogg Opus file really contains: its OpusHead fields and, from the TOC byte of each audio packet
(RFC 6716 section 3.1), a count of the packets of each mode, bandwidth, frame size, channel flag and frame-count code.
Usage: inspect-opus.py <file.opus>..."""
import struct
import sys
from collections import Counter

BANDWIDTHS = ["NB", "MB", "WB", "SWB", "FB"]
SILK_BANDWIDTHS = ["NB", "MB", "WB"]
SILK_MS = [10, 20, 40, 60]
HYBRID_MS = [10, 20]
CELT_MS = [2.5, 5, 10, 20]


def pages(data):
    position = 0
    while position < len(data):
        if data[position:position + 4] != b"OggS":
            raise ValueError("no Ogg page at %d" % position)
        segment_count = data[position + 26]
        lacing = data[position + 27:position + 27 + segment_count]
        body_start = position + 27 + segment_count
        flags = data[position + 5]
        yield flags, lacing, data[body_start:body_start + sum(lacing)]
        position = body_start + sum(lacing)


def packets(data):
    current = b""
    for _flags, lacing, body in pages(data):
        offset = 0
        for size in lacing:
            current += body[offset:offset + size]
            offset += size
            if size < 255:
                yield current
                current = b""


def toc(first_byte):
    config = first_byte >> 3
    stereo = (first_byte >> 2) & 1
    code = first_byte & 3
    if config < 12:
        return "SILK", SILK_BANDWIDTHS[config // 4], SILK_MS[config % 4], stereo, code
    if config < 16:
        return "HYBRID", BANDWIDTHS[3 + (config - 12) // 2], HYBRID_MS[config % 2], stereo, code
    return "CELT", BANDWIDTHS[[0, 2, 3, 4][(config - 16) // 4]], CELT_MS[config % 4], stereo, code


def frame_count(packet, code):
    if code == 0:
        return 1
    if code in (1, 2):
        return 2
    return packet[1] & 0x3F


def inspect(path):
    with open(path, "rb") as file:
        data = file.read()
    counts = Counter()
    audio = 0
    tags_seen = False
    for index, packet in enumerate(packets(data)):
        if index == 0:
            version, channels, pre_skip, rate, gain, family = struct.unpack("<BBHIhB", packet[8:19])
            print("%s: OpusHead version %d, %d channels, pre-skip %d, input rate %d, output gain %d (Q7.8 dB), mapping family %d"
                  % (path, version, channels, pre_skip, rate, gain, family))
            if family:
                print("  streams %d, coupled %d, mapping %s" % (packet[19], packet[20], list(packet[21:21 + channels])))
        elif not tags_seen:
            tags_seen = True
        elif packet:
            mode, bandwidth, ms, stereo, code = toc(packet[0])
            counts[(mode, bandwidth, ms, "stereo" if stereo else "mono", "code %d" % code, frame_count(packet, code))] += 1
            audio += 1
    print("  %d audio packets" % audio)
    for (mode, bandwidth, ms, channels, code, frames), count in sorted(counts.items(), key=str):
        print("  %-6s %-3s %4s ms x %d frame(s)  TOC channels: %-6s %s  packets: %d"
              % (mode, bandwidth, ms, frames, channels, code, count))


for argument in sys.argv[1:]:
    inspect(argument)
