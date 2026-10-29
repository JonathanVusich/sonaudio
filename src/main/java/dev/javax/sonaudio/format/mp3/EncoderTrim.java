package dev.javax.sonaudio.format.mp3;

/**
 * The silence an encoder put before and after an MP3's audio, which FFmpeg trims when a LAME tag (or FFmpeg's, which
 * it writes the same) gives it, and otherwise plays.
 */
public sealed interface EncoderTrim permits EncoderTrim.Lame, EncoderTrim.None {

    /**
     * The delay of the synthesis filterbank and the IMDCT's overlap, in samples.
     */
    int DECODER_DELAY = 529;

    /**
     * @return how many decoded samples per channel come before the first one that was encoded, which FFmpeg skips
     */
    long leadingSamples();

    /**
     * @return samples of silence the encoder put after the audio to fill the last frame
     */
    int padding();

    /**
     * @return the same trim without its padding, for a stream whose length can't be trusted
     */
    EncoderTrim withoutPadding();

    /**
     * A LAME tag's delay and padding. Its delay leaves out the decoder's own, even when it's 0.
     *
     * @param delay samples of silence the encoder put before the audio
     * @param padding samples of silence the encoder put after the audio, which count the decoder's delay
     */
    record Lame(int delay, int padding) implements EncoderTrim {

        @Override
        public long leadingSamples() {
            return delay + DECODER_DELAY;
        }

        @Override
        public EncoderTrim withoutPadding() {
            return new Lame(delay, 0);
        }
    }

    /**
     * No LAME tag, so the stream is played from its first sample to its last.
     */
    record None() implements EncoderTrim {

        @Override
        public long leadingSamples() {
            return 0;
        }

        @Override
        public int padding() {
            return 0;
        }

        @Override
        public EncoderTrim withoutPadding() {
            return this;
        }
    }
}
