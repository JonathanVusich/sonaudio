package dev.javax.sonaudio.format.mp3;

import java.time.Duration;
import dev.javax.sonaudio.format.AudioUtils;

/**
 * What an MP3's frames hold, as its first frame and VBR header give it.
 *
 * @param audioStart where the decoder starts: the first frame of audio, after any ID3v2 tag, VBR header frame, and bytes
 *     before the first frame that FFmpeg skips
 * @param audioEnd where the audio ends: the start of the tags after it, or the end of the file
 * @param channelCount the first frame's channels, which a later frame may change
 * @param samplesPerFrame the first frame's samples per channel
 * @param frameCount how many frames of audio the stream has: as its VBR header says, or as the decoder finds them
 * @param encoderTrim the silence the encoder put before and after the audio, which a LAME tag gives
 * @param freeFormatLength how many bytes each frame of a free-format stream takes but for its padding, which their
 *     headers don't give, otherwise 0
 */
public record Mp3StreamInfo(
        long audioStart,
        long audioEnd,
        int sampleRate,
        int channelCount,
        int samplesPerFrame,
        long frameCount,
        EncoderTrim encoderTrim,
        int freeFormatLength) {

    /**
     * @return samples per channel of the audio that was encoded, without the encoder's delay and padding
     */
    public long sampleCount() {
        return Math.max(paddingStart() - leadingSamples(), 0);
    }

    /**
     * @return how many decoded samples per channel come before the first one that was encoded, which FFmpeg skips: the
     *     encoder's delay, and the 529 samples of the decoder's own, which a LAME tag's delay leaves out, even when
     *     it's 0. A stream without one is played from its first sample.
     */
    public long leadingSamples() {
        return encoderTrim.leadingSamples();
    }

    /**
     * @return the first sample of the encoder's padding, counting every frame's samples from the stream's first, which
     *     FFmpeg drops up to {@link #paddingEnd()}. The padding a LAME tag gives counts the decoder's delay, so the
     *     audio ends that much before the padding starts, but never past the last frame.
     */
    public long paddingStart() {
        return Math.min(paddingEnd() - encoderTrim.padding() + EncoderTrim.DECODER_DELAY, paddingEnd());
    }

    /**
     * @return the sample after the last of the stream's frames, as its VBR header counts them
     */
    public long paddingEnd() {
        return frameCount * samplesPerFrame;
    }

    /**
     * @return how long the audio plays
     */
    public Duration duration() {
        return AudioUtils.duration(sampleCount(), sampleRate);
    }
}
