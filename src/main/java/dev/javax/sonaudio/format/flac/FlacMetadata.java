package dev.javax.sonaudio.format.flac;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record FlacMetadata(Map<BlockType, List<Block>> metadataBlocks) {

    /**
     * @return the stream info block, or empty if the file has none
     */
    public Optional<StreamInfo> streamInfo() {
        return metadataBlocks.getOrDefault(BlockType.STREAM_INFO, List.of()).stream()
                .<StreamInfo>mapMulti((block, streamInfos) -> {
                    switch (block) {
                        case StreamInfo streamInfo -> streamInfos.accept(streamInfo);
                        default -> {}
                    }
                })
                .findFirst();
    }

    public List<VorbisComment> vorbisComments() {
        return metadataBlocks.getOrDefault(BlockType.VORBIS_COMMENT, List.of()).stream()
                .<VorbisComment>mapMulti((block, comments) -> {
                    switch (block) {
                        case VorbisComment comment -> comments.accept(comment);
                        default -> {}
                    }
                })
                .toList();
    }
}
