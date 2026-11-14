package dev.javax.sonaudio.format.alac;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.AudioUtils;
import dev.javax.sonaudio.samples.AlacSample;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;

/**
 * Checks how the file's edit list, its duration and iTunes' gapless tag trim the audio. The file is
 * {@code STEREO16_LEVEL1} with its edit list, {@code mdhd} duration or tags changed as each test says (by
 * {@link AlacTestFiles}), whose movie and media time scales are its sample rate, with six packets of 4096 samples and
 * one of 1570.
 *
 * <p>The references, under {@code samples/alac/edits}, are what FFmpeg 9.0.2 decodes each changed file to, in the
 * reference image: {@code ffmpeg -v error -i <file>.m4a -f s16le <name>.s16le}, gzipped. The exception is
 * {@link #dropsTheSecondEditsPacketsAndTheStartSkipOfTheFirstWhenItStartsInTheSecondPacket}, whose file is decoded with
 * {@code -threads 1}: with the default threads FFmpeg's frame threading loses the start skip that a dropped first
 * packet leaves over (8192 samples, the second and third packets as they are), as each thread keeps its own count.
 */
class AlacEditListTest {

    private final AlacParser parser = new AlacParser();

    @Test
    void startsAtTheEditsMediaTimeAndEndsWhereItsDurationDoes() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{10000, 1000}});

        assertDecodesTo(file, "edit_start_and_end");
    }

    // An empty edit puts silence before the audio on a timeline, which FFmpeg doesn't add to what it decodes.
    @Test
    void ignoresAnEmptyEditBeforeTheAudio() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{5000, -1}, {22050, 0}});

        assertDecodesTo(file, "empty_edit_first");
    }

    @Test
    void playsSeveralEditsOneAfterTheOther() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{4096, 8192}, {4096, 0}});

        assertDecodesTo(file, "two_edits_swapped");
    }

    @Test
    void doesNotPlayPastTheMediaWhenAnEditIsLonger() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{30000, 0}});

        assertDecodesTo(file, "edit_longer_than_media");
    }

    // Only the first edit starts in the middle of a packet. FFmpeg drops the packet that a later edit starts in, and
    // keeps the whole of the one that the first ends in.
    @Test
    void dropsTheSecondEditsPacketsAndTheStartSkipOfTheFirstWhenItStartsInTheSecondPacket()
            throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{5000, 6000}, {3000, 17000}});

        assertDecodesTo(file, "second_edit_dropped");
    }

    @Test
    void skipsTheStartOfTheAudioWhenAnEditStartsInsideIt() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{22050, 4096}});

        assertDecodesTo(file, "edit_from_second_packet");
    }

    @Test
    void trimsTheLastPacketToTheDurationOfTheTrack() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withMediaDuration(file(), 21000);

        assertDecodesTo(file, "media_duration_in_last_packet");
    }

    @Test
    void doesNotAddToTheLastPacketWhenTheTrackIsLonger() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withMediaDuration(file(), 30000);

        assertDecodesTo(file, "media_duration_longer");
    }

    // The last packet starts after the track's duration, so FFmpeg trims nothing.
    @Test
    void doesNotTrimWhenTheTrackEndsBeforeTheLastPacketStarts() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withMediaDuration(file(), 20000);

        assertDecodesTo(file, "media_duration_before_last_packet");
    }

    // iTunes' gapless tag is "<ignored> <priming> <remainder> <samples>" in hexadecimal.
    @Test
    void dropsTheRemainderOfItunesGaplessTagFromTheEnd() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withFreeformItem(file(), "iTunSMPB", gaplessTag(0x100));

        assertDecodesTo(file, "smpb_remainder");
    }

    // A tag that has no remainder doesn't set where the audio ends, so the last packet is trimmed to the duration.
    @Test
    void trimsToTheTracksDurationWhenTheGaplessTagHasNoRemainder() throws IOException, InvalidFormatException {
        final var tagged = AlacTestFiles.withFreeformItem(file(), "iTunSMPB", gaplessTag(0));
        final var file = AlacTestFiles.withMediaDuration(tagged, 21000);

        assertDecodesTo(file, "smpb_zero_remainder");
    }

    // Each tag that fits the track sets the end again, so the last wins.
    @Test
    void usesTheLastGaplessTagThatFitsTheTrack() throws IOException, InvalidFormatException {
        final var first = AlacTestFiles.withFreeformItem(file(), "iTunSMPB", gaplessTag(0x100));
        final var file = AlacTestFiles.withFreeformItem(first, "iTunSMPB", gaplessTag(0x200));

        assertDecodesTo(file, "smpb_last_wins");
    }

    @Test
    void readsTheGaplessTagByItsNameInTheCaseItIsWritten() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withFreeformItem(file(), "ITUNSMPB", gaplessTag(0x100));

        assertDecodesTo(file, "smpb_name_case");
    }

    @Test
    void appliesAnEditListAndAGaplessTagTogether() throws IOException, InvalidFormatException {
        final var tagged = AlacTestFiles.withFreeformItem(file(), "iTunSMPB", gaplessTag(0x100));
        final var file = AlacTestFiles.withEditList(tagged, new long[][] {{10000, 1000}});

        assertDecodesTo(file, "edit_with_smpb");
    }

    @Test
    void ignoresAGaplessTagThatIsNotMadeOfNumbers() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withFreeformItem(file(), "iTunSMPB", " 00000000 zz 00000100 00005400");

        assertDecodesTo(file, "media_duration_longer");
    }

    @Test
    void measuresTheSameDurationAsTheHeaderGivesAfterTrimming() throws IOException, InvalidFormatException {
        final var file = AlacTestFiles.withEditList(file(), new long[][] {{10000, 1000}});
        final var parsed = parser.parse(new SeekableInMemoryByteChannel(file));

        final var measured = AudioUtils.measureDuration(parsed.audio());

        assertThat(measured).isEqualTo(parsed.metadata().duration());
        assertThat(parsed.audio().streamInfo().sampleCount()).isEqualTo(10000);
    }

    private static String gaplessTag(final int remainder) {
        return " 00000000 00000000 %08X 0000000000005400 00000000".formatted(remainder);
    }

    private void assertDecodesTo(final byte[] file, final String reference) throws IOException, InvalidFormatException {
        final var audio = parser.parse(new SeekableInMemoryByteChannel(file)).audio();

        final var decoded = AlacPcm.decodeToBytes(audio);

        AlacPcm.assertSamePcm(reference(reference), decoded, 2, 2);
        assertThat(audio.streamInfo().sampleCount()).isEqualTo(decoded.length / 4);
    }

    private static byte[] reference(final String name) throws IOException {
        final var resource = AlacEditListTest.class.getResourceAsStream(
                "/dev/javax/sonaudio/samples/alac/edits/" + name + ".s16le.gz");
        try (final var inputStream = new GZIPInputStream(resource)) {
            return inputStream.readAllBytes();
        }
    }

    private static byte[] file() throws IOException {
        return AlacTestFiles.bytes(AlacSample.STEREO16_LEVEL1);
    }
}
