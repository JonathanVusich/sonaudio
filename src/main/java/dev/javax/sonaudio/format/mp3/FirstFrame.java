package dev.javax.sonaudio.format.mp3;

/**
 * What an MP3's first frame holds: a VBR header, which encoders put in a frame of silence before the audio so players
 * know its length without reading every frame, or audio.
 */
sealed interface FirstFrame permits FirstFrame.VbrHeader, FirstFrame.AudioFrame {

    /**
     * A Xing (or Info) or VBRI header with the stream's frame count or byte count, either of which makes FFmpeg skip
     * its frame.
     *
     * @param frameCount frames of audio, not counting the one holding this header, or 0 when the header doesn't say
     * @param byteCount bytes of audio, or 0 when the header doesn't say
     * @param encoderTrim from the LAME tag that may follow a Xing header
     */
    record VbrHeader(long frameCount, long byteCount, EncoderTrim encoderTrim) implements FirstFrame {}

    /**
     * A frame of audio, or a VBR header with neither count, so the stream's frames are counted.
     */
    record AudioFrame() implements FirstFrame {}
}
