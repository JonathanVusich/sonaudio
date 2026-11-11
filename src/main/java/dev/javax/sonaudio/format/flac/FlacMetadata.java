package dev.javax.sonaudio.format.flac;

import java.util.List;
import java.util.Map;

/**
 * @param streamInfo the STREAMINFO block, which every FLAC file starts with
 * @param metadataBlocks every metadata block, STREAMINFO's included, by type
 */
record FlacMetadata(StreamInfo streamInfo, Map<BlockType, List<Block>> metadataBlocks) {

    public List<VorbisComment> vorbisComments() {
        final var blocks = metadataBlocks.getOrDefault(BlockType.VORBIS_COMMENT, List.of());
        return blocks.stream()
                .<VorbisComment>mapMulti((block, comments) -> {
                    switch (block) {
                        case VorbisComment comment -> comments.accept(comment);
                        // Blocks of the other kinds are filed under their own types.
                        case Application _, CueSheet _, Padding _, Picture _, SeekTable _, StreamInfo _ -> {}
                    }
                })
                .toList();
    }
}
