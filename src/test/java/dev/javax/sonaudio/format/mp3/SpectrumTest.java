package dev.javax.sonaudio.format.mp3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.javax.bitstream.BitInputStream;
import dev.javax.bitstream.BitOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import dev.javax.sonaudio.exception.InvalidFormatException;

class SpectrumTest {

    // At 44.1 kHz, short band 0 is 4 lines wide in each window: coded as each window's 4 lines in turn, and taken by
    // the IMDCT as each line's 3 windows in turn.
    @Test
    void reordersShortBands() throws IOException, InvalidFormatException {
        final var spectrum = new Spectrum();
        for (var line = 0; line < Spectrum.LINES; line++) {
            spectrum.setLine(line, line);
        }

        // MPEG-1 at 44.1 kHz.
        spectrum.reorderShortBands(Mp3Fixtures.header("FFFB9000"), Mp3Fixtures.shortBlock());

        final var firstBand = new double[12];
        for (var line = 0; line < firstBand.length; line++) {
            firstBand[line] = spectrum.line(line);
        }
        assertThat(firstBand).containsExactly(0, 4, 8, 1, 5, 9, 2, 6, 10, 3, 7, 11);
    }

    // FFmpeg drops a count1 quadruple that runs past the channel's bits only when it goes on to another, which it
    // doesn't after the quadruple at line 570, as 574 is past the last line a quadruple starts at.
    @Test
    void keepsLastCount1QuadrupleThatRunsPastBits() throws IOException, InvalidFormatException {
        final var bytes = new ByteArrayOutputStream();
        final var writer = BitOutputStream.wrap(bytes, ByteOrder.BIG_ENDIAN);
        // Count1 table B codes a quadruple in four inverted bits: 1111 for four 0s, and 0111 for 1, 0, 0, 0.
        for (var quadruple = 0; quadruple < 71; quadruple++) {
            writer.writeBits(0b1111, 4);
        }
        writer.writeBits(0b0111, 4);
        writer.writeBits(0, 1);
        writer.writeBits(0, 4 * Byte.SIZE);
        writer.flush();
        // 143 pairs of big values, coded with table 0, which takes no bits, so the quadruples start at line 286.
        final var granuleChannel =
                new GranuleChannel(71 * 4 + 2, 143, 0, 0, Mp3Fixtures.longBlock(), 0, 0, HuffmanTables.COUNT1_B, 0);
        final var spectrum = new Spectrum();

        spectrum.read(
                BitInputStream.wrap(new ByteArrayInputStream(bytes.toByteArray()), ByteOrder.BIG_ENDIAN),
                Mp3Fixtures.header("FFFB9000"),
                granuleChannel,
                ScaleFactors.NONE,
                Integer.MAX_VALUE);

        assertThat(spectrum.line(570)).isGreaterThan(0);
        assertThat(spectrum.nonZeroEnd()).isEqualTo(574);
    }

    // The first butterfly mixes the last line of subband 0 with the first of subband 1.
    @Test
    void reducesAliasingAcrossSubbands() throws IOException {
        final var spectrum = new Spectrum();
        spectrum.setLine(17, 1);
        spectrum.setLine(18, 2);

        spectrum.reduceAliasing(Mp3Fixtures.longBlock());

        final var cs = AliasCoefficients.floatCs(0);
        final var ca = AliasCoefficients.floatCa(0);
        assertThat(spectrum.line(17)).isCloseTo(cs - 2 * ca, within(1e-6f));
        assertThat(spectrum.line(18)).isCloseTo(2 * cs + ca, within(1e-6f));
    }

    @Test
    void leavesShortBlocksAliased() throws IOException, InvalidFormatException {
        final var spectrum = new Spectrum();
        spectrum.setLine(17, 1);

        spectrum.reduceAliasing(Mp3Fixtures.shortBlock());

        assertThat(spectrum.line(17)).isEqualTo(1);
    }
}
