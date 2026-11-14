#!/usr/bin/env python3
"""Tags fixtures with mutagen, for what FFmpeg doesn't write: MusicBrainz Picard's freeform atoms in an MP4 file, and a
Vorbis comment block with a METADATA_BLOCK_PICTURE (a FLAC picture block, as Picard embeds cover art) in an Ogg Opus one.
Usage: tag-fixtures.py alac <file.m4a> | opus-picard <file.opus> | opus-r128 <file.opus> <picture.jpg>"""
import base64
import sys

import mutagen.flac
import mutagen.mp4
import mutagen.oggopus

MUSICBRAINZ = {
    "MusicBrainz Track Id": "0c9a6f2e-4f7d-4a3b-9f55-5d3a3c1b7e01",
    "MusicBrainz Release Track Id": "6a1d0e0b-2c4e-4d0f-8a32-7b5c8e9d1f02",
    "MusicBrainz Album Id": "b3c2a1d4-5e6f-4789-a0b1-c2d3e4f50603",
    "MusicBrainz Artist Id": "11111111-2222-4333-8444-555555555504",
    "MusicBrainz Album Artist Id": "66666666-7777-4888-9999-aaaaaaaaaa05",
    "MusicBrainz Release Group Id": "bbbbbbbb-cccc-4ddd-8eee-ffffffff0006",
    "MusicBrainz Album Type": "album",
    "MusicBrainz Album Status": "official",
    "MusicBrainz Album Release Country": "XW",
    "ARTISTS": "Test Artist",
    "SCRIPT": "Latn",
    "LABEL": "Test Label",
    "CATALOGNUMBER": "TEST-001",
    "BARCODE": "0000000000000",
    "originaldate": "2019-11-01",
}


def tag_alac(path):
    tags = mutagen.mp4.MP4(path)
    for name, value in MUSICBRAINZ.items():
        tags["----:com.apple.iTunes:" + name] = [mutagen.mp4.MP4FreeForm(value.encode("utf-8"))]
    tags.save()


def vorbis_picard(tags):
    tags["TITLE"] = ["Test Title"]
    tags["ARTIST"] = ["Test Artist"]
    tags["ALBUMARTIST"] = ["Test Album Artist"]
    tags["ALBUM"] = ["Test Album"]
    tags["TRACKNUMBER"] = ["3"]
    tags["TOTALTRACKS"] = ["12"]
    tags["DISCNUMBER"] = ["1"]
    tags["TOTALDISCS"] = ["2"]
    tags["DATE"] = ["2020-05-06"]
    tags["COMPOSER"] = ["Test Composer"]
    tags["MUSICBRAINZ_TRACKID"] = [MUSICBRAINZ["MusicBrainz Track Id"]]
    tags["MUSICBRAINZ_RELEASETRACKID"] = [MUSICBRAINZ["MusicBrainz Release Track Id"]]
    tags["MUSICBRAINZ_ALBUMID"] = [MUSICBRAINZ["MusicBrainz Album Id"]]
    tags["MUSICBRAINZ_ARTISTID"] = [MUSICBRAINZ["MusicBrainz Artist Id"]]
    tags["MUSICBRAINZ_ALBUMARTISTID"] = [MUSICBRAINZ["MusicBrainz Album Artist Id"]]
    tags["MUSICBRAINZ_RELEASEGROUPID"] = [MUSICBRAINZ["MusicBrainz Release Group Id"]]
    tags["RELEASETYPE"] = ["album"]
    tags["RELEASESTATUS"] = ["official"]
    tags["RELEASECOUNTRY"] = ["XW"]


def tag_opus_picard(path):
    opus = mutagen.oggopus.OggOpus(path)
    vorbis_picard(opus.tags)
    opus.save()


def tag_opus_r128(path, picture_path):
    opus = mutagen.oggopus.OggOpus(path)
    opus.tags["TITLE"] = ["R128 Title"]
    opus.tags["ARTIST"] = ["R128 Artist"]
    opus.tags["R128_TRACK_GAIN"] = ["-1536"]
    opus.tags["R128_ALBUM_GAIN"] = ["512"]
    opus.tags["REPLAYGAIN_TRACK_GAIN"] = ["-6.00 dB"]
    opus.tags["REPLAYGAIN_TRACK_PEAK"] = ["0.891251"]
    with open(picture_path, "rb") as file:
        picture = mutagen.flac.Picture()
        picture.type = 3
        picture.mime = "image/jpeg"
        picture.desc = "Front cover"
        picture.width = 16
        picture.height = 16
        picture.depth = 24
        picture.data = file.read()
    opus.tags["METADATA_BLOCK_PICTURE"] = [base64.b64encode(picture.write()).decode("ascii")]
    opus.save()


command = sys.argv[1]
if command == "alac":
    tag_alac(sys.argv[2])
elif command == "opus-picard":
    tag_opus_picard(sys.argv[2])
elif command == "opus-r128":
    tag_opus_r128(sys.argv[2], sys.argv[3])
