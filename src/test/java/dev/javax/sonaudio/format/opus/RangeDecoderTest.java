package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RangeDecoderTest {

    private static final int STORAGE = 8192;
    private static final int TABLE_BITS = 8;

    private enum Kind {
        ICDF,
        BIT,
        UNIFORM,
        RAW_BITS,
        LAPLACE,
        STEP,
        TRIANGULAR,
        EXPLICIT_RANGE
    }

    private record Operation(Kind kind, int first, int second, int third, int expected) {}

    @Test
    void startsAtOneBitUsed() {
        final var decoder = decoderOf(new byte[16]);

        assertThat(decoder.tell()).isEqualTo(1);
        assertThat(decoder.frameBits()).isEqualTo(128);
    }

    @Test
    void zerosDecodeToTheUnlikelyBitAndOnesToTheLikelyOne() {
        // The first byte's top 7 bits are inverted into the value, so zeros are the top of the range.
        final var zeros = decoderOf(new byte[16]);
        final var ones = decoderOf(filled(16, (byte) 0xFF));

        assertThat(zeros.decodeBit(1)).isFalse();
        assertThat(ones.decodeBit(1)).isTrue();
    }

    @Test
    void readsRawBitsFromTheEndOfTheFrameLeastSignificantFirst() {
        final var frame = new byte[8];
        frame[7] = (byte) 0b1011_0101;
        frame[6] = (byte) 0b0000_0011;
        final var decoder = decoderOf(frame);

        assertThat(decoder.decodeRawBits(3)).isEqualTo(0b101);
        assertThat(decoder.decodeRawBits(5)).isEqualTo(0b10110);
        assertThat(decoder.decodeRawBits(2)).isEqualTo(0b11);
        assertThat(decoder.tell()).isEqualTo(1 + 10);
    }

    @Test
    void readsZerosPastTheEndOfTheFrame() {
        final var decoder = decoderOf(new byte[0]);

        assertThat(decoder.decodeRawBits(32)).isZero();
        assertThat(decoder.decodeUniform(1000)).isEqualTo(0);
        assertThat(decoder.frameBits()).isZero();
    }

    @Test
    void shrinkMovesTheEndOfTheFrameRawBitsAreReadFrom() {
        final var frame = new byte[8];
        frame[7] = (byte) 0xFF;
        frame[5] = (byte) 0x0F;
        final var decoder = decoderOf(frame);
        decoder.shrink(6);

        assertThat(decoder.frameBits()).isEqualTo(48);
        assertThat(decoder.decodeRawBits(8)).isEqualTo(0x0F);
    }

    @Test
    void consumeToEndCountsTheWholeFrameAsUsed() {
        final var decoder = decoderOf(new byte[10]);
        decoder.decodeBit(3);

        decoder.consumeToEnd();

        assertThat(decoder.tell()).isEqualTo(80);
    }

    @Test
    void startsAgainOnAnotherFrameOfTheSameBytes() {
        final var packet = filled(20, (byte) 0xFF);
        final var decoder = new RangeDecoder();
        decoder.initialise(packet, 0, 10);
        decoder.decodeUniform(1000);

        decoder.initialise(packet, 10, 10);

        assertThat(decoder.tell()).isEqualTo(1);
        assertThat(decoder.frameBits()).isEqualTo(80);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void decodesWhatTheRfcEncoderEncodes(final long seed) {
        final var random = new Random(seed);
        final var tables = tables(random);
        final var encoder = new RangeEncoder(STORAGE);
        final var operations = new ArrayList<Operation>();
        final var tells = new ArrayList<Integer>();
        final var fractions = new ArrayList<Integer>();
        for (var count = 0; count < 300; count++) {
            operations.add(encodeRandomOperation(encoder, random, tables));
            tells.add(encoder.tell());
            fractions.add(encoder.tellFraction());
        }
        final var decoder = decoderOf(encoder.finish());

        for (var index = 0; index < operations.size(); index++) {
            final var operation = operations.get(index);
            assertThat(decode(decoder, operation, tables))
                    .as("operation %d: %s", index, operation)
                    .isEqualTo(operation.expected());
            assertThat(decoder.tell()).as("tell after operation %d", index).isEqualTo(tells.get(index));
            assertThat(decoder.tellFraction())
                    .as("tell fraction after operation %d", index)
                    .isBetween(fractions.get(index) - 1, fractions.get(index) + 1);
        }
    }

    @Test
    void tellFractionIsWithinABitOfTell() {
        final var random = new Random(99);
        final var encoder = new RangeEncoder(STORAGE);
        final var tables = tables(random);
        for (var count = 0; count < 100; count++) {
            encodeRandomOperation(encoder, random, tables);
        }
        final var decoder = decoderOf(encoder.finish());
        for (var count = 0; count < 20; count++) {
            decoder.decodeUniform(1000);
            assertThat(decoder.tellFraction()).isBetween((decoder.tell() - 1) * 8, decoder.tell() * 8);
        }
    }

    @Test
    void laplaceDecodesZeroAtItsFrequencyAndFallsAwayBothSides() {
        final var encoder = new RangeEncoder(STORAGE);
        final var values = new int[] {0, 1, -1, 2, -2, 5, -7, 0, 0, 30, -30, 3};
        final var coded = new int[values.length];
        for (var index = 0; index < values.length; index++) {
            coded[index] = encoder.encodeLaplace(values[index], 15000, 6000);
        }
        final var decoder = decoderOf(encoder.finish());

        for (final var expected : coded) {
            assertThat(decoder.decodeLaplace(15000, 6000)).isEqualTo(expected);
        }
        assertThat(coded).startsWith(0, 1, -1, 2, -2, 5, -7);
    }

    @Test
    void uniformDecodesAcrossTheSplitBetweenCodedAndRawBits() {
        final var encoder = new RangeEncoder(STORAGE);
        final var sizes = new int[] {2, 3, 255, 256, 257, 1000, 65536, 1_000_003};
        for (final var size : sizes) {
            encoder.encodeUniform(size - 1, size);
            encoder.encodeUniform(size / 2, size);
        }
        final var decoder = decoderOf(encoder.finish());

        for (final var size : sizes) {
            assertThat(decoder.decodeUniform(size)).isEqualTo(size - 1);
            assertThat(decoder.decodeUniform(size)).isEqualTo(size / 2);
        }
    }

    private RangeDecoder decoderOf(final byte[] frame) {
        final var decoder = new RangeDecoder();
        decoder.initialise(frame, 0, frame.length);
        return decoder;
    }

    private byte[] filled(final int size, final byte content) {
        final var bytes = new byte[size];
        Arrays.fill(bytes, content);
        return bytes;
    }

    // Tables of 2 to 17 symbols with every symbol possible, as inverse CDFs of 8 bits that fall to 0.
    private List<int[]> tables(final Random random) {
        final var tables = new ArrayList<int[]>();
        for (var table = 0; table < 6; table++) {
            final var symbols = 2 + random.nextInt(16);
            final var icdf = new int[symbols];
            var remaining = 1 << TABLE_BITS;
            for (var symbol = 0; symbol < symbols - 1; symbol++) {
                final var room = remaining - (symbols - 1 - symbol);
                remaining -= 1 + random.nextInt(Math.max(1, room / 2));
                icdf[symbol] = remaining;
            }
            icdf[symbols - 1] = 0;
            tables.add(icdf);
        }
        return tables;
    }

    private Operation encodeRandomOperation(final RangeEncoder encoder, final Random random, final List<int[]> tables) {
        final var kind = Kind.values()[random.nextInt(Kind.values().length)];
        return switch (kind) {
            case ICDF -> encodeIcdf(encoder, random, tables);
            case BIT -> encodeBit(encoder, random);
            case UNIFORM -> encodeUniform(encoder, random);
            case RAW_BITS -> encodeRawBits(encoder, random);
            case LAPLACE -> encodeLaplace(encoder, random);
            case STEP -> encodeStep(encoder, random);
            case TRIANGULAR -> encodeTriangular(encoder, random);
            case EXPLICIT_RANGE -> encodeExplicitRange(encoder, random);
        };
    }

    private Operation encodeIcdf(final RangeEncoder encoder, final Random random, final List<int[]> tables) {
        final var table = random.nextInt(tables.size());
        final var symbol = random.nextInt(tables.get(table).length);
        encoder.encodeIcdf(symbol, tables.get(table), TABLE_BITS);
        return new Operation(Kind.ICDF, table, 0, 0, symbol);
    }

    private Operation encodeBit(final RangeEncoder encoder, final Random random) {
        final var logProbability = 1 + random.nextInt(15);
        final var bit = random.nextInt(2);
        encoder.encodeBit(bit, logProbability);
        return new Operation(Kind.BIT, logProbability, 0, 0, bit);
    }

    private Operation encodeUniform(final RangeEncoder encoder, final Random random) {
        final var size = 2 + random.nextInt(1 << (1 + random.nextInt(24)));
        final var number = random.nextInt(size);
        encoder.encodeUniform(number, size);
        return new Operation(Kind.UNIFORM, size, 0, 0, number);
    }

    private Operation encodeRawBits(final RangeEncoder encoder, final Random random) {
        final var count = 1 + random.nextInt(25);
        final var bits = random.nextInt(1 << count);
        encoder.encodeRawBits(bits, count);
        return new Operation(Kind.RAW_BITS, count, 0, 0, bits);
    }

    private Operation encodeLaplace(final RangeEncoder encoder, final Random random) {
        final var zeroFrequency = 1000 + random.nextInt(30000);
        final var decay = 1000 + random.nextInt(15000);
        final var wanted = random.nextInt(41) - 20;
        final var coded = encoder.encodeLaplace(wanted, zeroFrequency, decay);
        return new Operation(Kind.LAPLACE, zeroFrequency, decay, 0, coded);
    }

    private Operation encodeStep(final RangeEncoder encoder, final Random random) {
        final var k0 = 1 + random.nextInt(60);
        final var number = random.nextInt(2 * k0 + 1);
        encoder.encodeStep(number, k0);
        return new Operation(Kind.STEP, k0, 0, 0, number);
    }

    private Operation encodeTriangular(final RangeEncoder encoder, final Random random) {
        // CELT's quantisation steps are always even, which is when the triangle's frequencies add up to its total.
        final var qn = 2 * (1 + random.nextInt(100));
        final var number = random.nextInt(qn + 1);
        encoder.encodeTriangular(number, qn);
        return new Operation(Kind.TRIANGULAR, qn, 0, 0, number);
    }

    private Operation encodeExplicitRange(final RangeEncoder encoder, final Random random) {
        final var total = 2 + random.nextInt(65534);
        final var low = random.nextInt(total - 1);
        final var high = low + 1 + random.nextInt(Math.min(total - low, 50));
        encoder.encode(low, high, total);
        return new Operation(Kind.EXPLICIT_RANGE, low, high, total, low);
    }

    private int decode(final RangeDecoder decoder, final Operation operation, final List<int[]> tables) {
        return switch (operation.kind()) {
            case ICDF -> decoder.decodeIcdf(tables.get(operation.first()), TABLE_BITS);
            case BIT -> bitValue(decoder.decodeBit(operation.first()));
            case UNIFORM -> decoder.decodeUniform(operation.first());
            case RAW_BITS -> decoder.decodeRawBits(operation.first());
            case LAPLACE -> decoder.decodeLaplace(operation.first(), operation.second());
            case STEP -> decoder.decodeStep(operation.first());
            case TRIANGULAR -> decoder.decodeTriangular(operation.first());
            case EXPLICIT_RANGE -> decodeExplicitRange(decoder, operation);
        };
    }

    private int bitValue(final boolean bit) {
        if (bit) {
            return 1;
        }
        return 0;
    }

    // The decoded value is somewhere in the symbol's frequencies, which the symbol's low end stands for.
    private int decodeExplicitRange(final RangeDecoder decoder, final Operation operation) {
        final var found = decoder.decode(operation.third());
        decoder.update(operation.first(), operation.second(), operation.third());
        assertThat(found).isBetween(operation.first(), operation.second() - 1);
        return operation.first();
    }
}
