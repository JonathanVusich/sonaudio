package dev.javax.sonaudio.format.flac;

// https://www.rfc-editor.org/rfc/rfc9639.html#name-picture
public record Picture(
        BlockHeader header,
        PictureType pictureType,
        String mediaType,
        String description,
        int width,
        int height,
        int colorDepth,
        int numberOfColors,
        byte[] data)
        implements Block {

    @Override
    public byte[] data() {
        return data.clone();
    }
}
