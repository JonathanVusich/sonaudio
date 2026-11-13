#!/usr/bin/env python3
"""Counts, for each ALAC track in an MP4 file, its frames and those stored uncompressed (the escape frames: a frame
header's isnotcompressed bit). Reads the sample tables, so it works wherever the moov box is.
Usage: inspect-alac.py <file.m4a>..."""
import struct
import sys

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl"}


def boxes(data, start, end):
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack(">I4s", data[position:position + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[position + 8:position + 16])[0]
            header = 16
        elif size == 0:
            size = end - position
        yield kind, position + header, position + size
        position += size


def find_tracks(data):
    tracks = []

    def walk(start, end, track):
        for kind, body, box_end in boxes(data, start, end):
            if kind == b"trak":
                track = {}
                tracks.append(track)
                walk(body, box_end, track)
            elif kind in CONTAINERS:
                walk(body, box_end, track)
            elif track is not None:
                track[kind] = data[body:box_end]

    walk(0, len(data), None)
    return tracks


def table(payload, count_at, entry_format):
    count = struct.unpack(">I", payload[count_at:count_at + 4])[0]
    size = struct.calcsize(entry_format)
    return [struct.unpack(entry_format, payload[count_at + 4 + i * size:count_at + 4 + (i + 1) * size]) for i in range(count)]


def inspect(path):
    with open(path, "rb") as file:
        data = file.read()
    order = [kind for kind, _s, _e in boxes(data, 0, len(data))]
    print("%s: top-level boxes %s" % (path, [k.decode("latin1") for k in order]))
    for track in find_tracks(data):
        if b"stsd" not in track or b"alac" not in track[b"stsd"][:16 + 16]:
            continue
        sizes = [size for (size,) in table(track[b"stsz"], 8, ">I")] if struct.unpack(">I", track[b"stsz"][4:8])[0] == 0 \
            else [struct.unpack(">I", track[b"stsz"][4:8])[0]] * struct.unpack(">I", track[b"stsz"][8:12])[0]
        offsets_format = ">I" if b"stco" in track else ">Q"
        offsets = [o for (o,) in table(track.get(b"stco", track.get(b"co64")), 4, offsets_format)]
        chunks = table(track[b"stsc"], 4, ">III")
        sample = 0
        escape = 0
        for chunk_index, offset in enumerate(offsets):
            per_chunk = [c for c in chunks if c[0] <= chunk_index + 1][-1][1]
            for _ in range(per_chunk):
                if sample >= len(sizes):
                    break
                frame = data[offset:offset + 4]
                # 3 bits element tag, 4 bits instance tag, 12 unused bits, then has-size, 2 shift bits, not-compressed.
                bits = int.from_bytes(frame, "big")
                if (bits >> (32 - 23)) & 1:
                    escape += 1
                offset += sizes[sample]
                sample += 1
        print("  ALAC track: %d frames, %d uncompressed (escape) frames" % (sample, escape))


for argument in sys.argv[1:]:
    inspect(argument)
