package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.AudioUtils;
import dev.javax.sonaudio.samples.AlacSample;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;

/**
 * Checks how the file's edit list, its duration and iTunes' gapless tag trim the audio, as FFmpeg's MOV demuxer and
 * decoder do. The file is {@code STEREO16_LEVEL1}, whose movie and media time scales are its sample rate, with six
 * packets of 4096 samples and one of 1570, and whose references are the source's samples.
 */
class AlacEditListTest {

    private static final int SAMPLE_BYTES = 2 * 2;

    private final AlacParser parser = new AlacParser();

    @Test
    void startsAtTheEditsMediaTimeAndEndsWhereItsDurationDoes() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{10000, 1000}});

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(1000, 11000));
        assertThat(audio.streamInfo().sampleCount()).isEqualTo(10000);
    }

    // An empty edit puts silence before the audio on a timeline, which FFmpeg doesn't add to what it decodes.
    @Test
    void ignoresAnEmptyEditBeforeTheAudio() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{5000, -1}, {22050, 0}});

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(0, 22050));
        assertThat(audio.streamInfo().sampleCount()).isEqualTo(22050);
    }

    @Test
    void playsSeveralEditsOneAfterTheOther() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{4096, 8192}, {4096, 0}});

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(concatenated(samples(8192, 12288), samples(0, 4096)));
    }

    @Test
    void doesNotPlayPastTheMediaWhenAnEditIsLonger() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{30000, 0}});

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(0, 22050));
    }

    // Only the first edit starts in the middle of a packet. FFmpeg drops the packet that a later edit starts in, and
    // keeps the whole of the one that it ends in.
    @Test
    void startsALaterEditAtThePacketAfterTheOneItStartsIn() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{5000, 6000}, {3000, 17000}});

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(6000, 12288));
    }

    @Test
    void skipsTheStartOfTheAudioWhenAnEditStartsInsideIt() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{22050, 4096}});

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(4096, 22050));
    }

    @Test
    void trimsTheLastPacketToTheDurationOfTheTrack() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withMediaDuration(file(), 21000);

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(0, 21000));
        assertThat(audio.streamInfo().sampleCount()).isEqualTo(21000);
    }

    @Test
    void doesNotAddToTheLastPacketWhenTheTrackIsLonger() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withMediaDuration(file(), 30000);

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(0, 22050));
    }

    // iTunes' gapless tag is "<ignored> <priming> <remainder> <samples>" in hexadecimal.
    @Test
    void dropsTheRemainderOfItunesGaplessTagFromTheEnd() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withFreeformItem(
                file(), "iTunSMPB", " 00000000 00000000 00000100 0000000000005400 00000000");

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(0, 22050 - 0x100));
        assertThat(audio.streamInfo().sampleCount()).isEqualTo(22050 - 0x100);
    }

    @Test
    void ignoresAGaplessTagThatIsNotMadeOfNumbers() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withFreeformItem(file(), "iTunSMPB", " 00000000 zz 00000100 00005400");

        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        assertThat(AlacPcm.decodeToBytes(audio)).isEqualTo(samples(0, 22050));
    }

    @Test
    void measuresTheSameDurationAsTheHeaderGivesAfterTrimming() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{10000, 1000}});
        final var parsed = parser.parse(new SeekableInMemoryByteChannel(file));

        final var measured = AudioUtils.measureDuration(parsed.audio());

        assertThat(measured).isEqualTo(parsed.metadata().duration());
    }

    private static byte[] file() throws IOException {
        return AlacTestFiles.bytes(AlacSample.STEREO16_LEVEL1);
    }

    // The samples from the first up to the last, not including it.
    private static byte[] samples(final int first, final int last) throws IOException {
        try (final var reference = AlacSample.STEREO16_LEVEL1.referencePcm()) {
            final var all = reference.readAllBytes();
            return Arrays.copyOfRange(all, first * SAMPLE_BYTES, last * SAMPLE_BYTES);
        }
    }

    private static byte[] concatenated(final byte[] first, final byte[] second) throws IOException {
        final var out = new ByteArrayOutputStream();
        out.write(first);
        out.write(second);
        return out.toByteArray();
    }
}
