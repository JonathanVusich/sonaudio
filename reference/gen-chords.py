#!/usr/bin/env python3
"""Writes the audio FingerprintTest's chordAudio and noiseAudio generate, as a 16-bit WAV file: chords of three notes
that change every 0.4 seconds, picked by a linear congruential generator, alone ("chords") or under noise that is a
hash of each sample's position ("noise").
Usage: gen-chords.py <chords|noise> <sample rate> <channels> <seconds> <out.wav>"""
import math
import struct
import sys
import wave

MASK64 = (1 << 64) - 1
# FingerprintTest divides the noise by 16. A lossless file of that much noise is too big to commit: 4 times less.
NOISE_DIVISOR = 64.0


def hash64(value):
    mixed = (value * 0x9E3779B97F4A7C15) & MASK64
    mixed = ((mixed ^ (mixed >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
    mixed = ((mixed ^ (mixed >> 27)) * 0x94D049BB133111EB) & MASK64
    return mixed ^ (mixed >> 31)


def generate(kind, sample_rate, channels, seconds):
    frames = bytearray()
    cache = {}
    for index in range(sample_rate * seconds):
        chord = index * 5 // (2 * sample_rate)
        if chord not in cache:
            if kind == "chords":
                seed = (chord * 1103515245 + 12345) % (1 << 31)
                pitches = [48 + ((seed >> (8 * note)) % 36) for note in range(3)]
            else:
                seed = hash64(chord * 3)
                pitches = [48 + ((seed >> (8 * note)) & 0xFF) % 36 for note in range(3)]
            cache[chord] = [440.0 * 2 ** ((pitch - 69) / 12.0) for pitch in pitches]
        for channel in range(channels):
            total = sum(math.sin(2 * math.pi * frequency * index / sample_rate + channel) for frequency in cache[chord])
            if kind == "chords":
                sample = round(8000 * total)
            else:
                noise = (hash64(2 * index + channel) >> 48) - 32768
                sample = round(5000 * total + noise / NOISE_DIVISOR)
            frames += struct.pack("<h", max(-32768, min(32767, sample)))
    return bytes(frames)


kind, sample_rate, channels, seconds, path = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
with wave.open(path, "wb") as out:
    out.setnchannels(channels)
    out.setsampwidth(2)
    out.setframerate(sample_rate)
    out.writeframes(generate(kind, sample_rate, channels, seconds))
