package dev.javax.sonaudio.format.opus;

/**
 * The range decoder of RFC 6716 section 4.1, which both SILK and CELT read their symbols from. It decodes one Opus
 * frame at a time, from a frame's bytes: symbols come from the front of the frame, and raw bits, which CELT reads
 * uniformly and unmodelled, from the end of it backwards. The two meet in the middle, and what is read from past
 * either end of the frame is zeros, so a corrupt frame decodes to something rather than failing.
 *
 * <p>One decoder serves a whole frame, SILK's layer then CELT's in a hybrid frame, each continuing where the other
 * stopped. {@link #shrink} cuts a redundancy frame off the end of the frame's bytes once its size is known, and a
 * frame of redundancy gets a decoder of its own, started with {@link #initialise} on its slice.
 *
 * <p>The decoder holds its position in the frame's bytes, which it reads from the array it's given and never changes:
 * the caller keeps the packet's bytes as they are while a frame is decoded from them. It is a working object that is
 * started again for every frame, so decoding allocates nothing.
 *
 * <p>An <em>inverse CDF</em> table, as {@link #decodeIcdf} takes, lists for each symbol {@code k} the probability
 * that the symbol is above {@code k}, as a count out of {@code 1 << totalBits}: so it falls to 0 at its last entry,
 * which is the largest symbol.
 */
final class RangeDecoder {

    private static final long CODE_TOP = 1L << 31;
    private static final long CODE_BOTTOM = CODE_TOP >>> 8;
    private static final int SYMBOL_BITS = 8;
    private static final int SYMBOL_MASK = 0xFF;
    // The bits of the first byte that the value starts from: 7 of 8, so the value is 31 bits wide.
    private static final int FIRST_BYTE_SHIFT = 1;
    // The bits the decoder has taken in at the start, which tell counts from.
    private static final int INITIAL_BITS = 9;
    private static final int FRACTION_BITS = 3;
    private static final int LAPLACE_TOTAL_BITS = 15;
    private static final long LAPLACE_TOTAL = 1L << LAPLACE_TOTAL_BITS;
    // Every symbol of a Laplace distribution has at least this probability, and the 16 values around 0 are coded
    // with a minimum of 1 as well.
    private static final int LAPLACE_MIN_VALUES = 16;
    private static final int LAPLACE_DECAY_HALF = 16384;

    private byte[] data = new byte[0];
    private int start;
    private int length;
    private int frontPosition;
    private int backPosition;
    private long window;
    private int windowBits;
    private int totalBits;
    private long range;
    private long value;
    private long remainder;
    // The size of one unit of the range at the last decode, which the update after it applies.
    private long scale;

    /**
     * Starts decoding a frame (RFC 6716 section 4.1.1), forgetting any frame before it.
     *
     * @param frame the packet's bytes, which are only read
     * @param offset where the frame starts in them
     * @param size how many bytes the frame has, which may be none
     */
    void initialise(final byte[] frame, final int offset, final int size) {
        data = frame;
        start = offset;
        length = size;
        frontPosition = 0;
        backPosition = 0;
        window = 0;
        windowBits = 0;
        totalBits = INITIAL_BITS;
        range = 1L << 7;
        remainder = readFrontByte();
        value = range - 1 - (remainder >> FIRST_BYTE_SHIFT);
        normalize();
    }

    /**
     * Cuts the last bytes off the frame, as the size of a redundancy frame at the end of it does once that is known:
     * what is read from the end and from past it is then relative to the new end.
     *
     * @param newSize how many bytes the frame keeps, from its start
     */
    void shrink(final int newSize) {
        length = Math.min(length, Math.max(0, newSize));
    }

    /**
     * @return how many bits the frame has, which is what CELT's decoding of it is limited to
     */
    int frameBits() {
        return length * Byte.SIZE;
    }

    /**
     * @return how many whole bits of the frame have been used so far, symbols and raw bits together, rounded up to
     *     include the bit being decoded (RFC 6716 section 4.1.6.1)
     */
    int tell() {
        return totalBits - bitLength(range);
    }

    /**
     * @return how many eighths of a bit have been used so far (RFC 6716 section 4.1.6.2), so {@code tellFraction() >> 3}
     *     is within a bit of {@link #tell}
     */
    int tellFraction() {
        final var bufferBits = bitLength(range);
        var remaining = range >>> (bufferBits - 16);
        var buffer = (long) bufferBits;
        for (var step = 0; step < 3; step++) {
            remaining = remaining * remaining >>> 15;
            final var bit = remaining >>> 16;
            buffer = buffer << 1 | bit;
            remaining >>>= bit;
        }
        return (int) (((long) totalBits << FRACTION_BITS) - buffer);
    }

    /**
     * Counts the rest of the frame as used, as CELT does for a silent frame, which codes nothing more.
     */
    void consumeToEnd() {
        totalBits += frameBits() - tell();
    }

    /**
     * Decodes the symbol of a table by its inverse CDF ({@code ec_dec_icdf}).
     *
     * @param icdf the table
     * @param tableBits the base 2 logarithm of the table's total
     * @return the symbol, from 0
     */
    int decodeIcdf(final int[] icdf, final int tableBits) {
        final var previousRange = range;
        final var previousValue = value;
        var symbol = -1;
        var upperBound = previousRange;
        var lowerBound = previousRange;
        do {
            upperBound = lowerBound;
            symbol++;
            lowerBound = (previousRange >>> tableBits) * icdf[symbol];
        } while (previousValue < lowerBound);
        range = upperBound - lowerBound;
        value = previousValue - lowerBound;
        normalize();
        return symbol;
    }

    /**
     * Decodes a bit that is 1 with probability {@code 1 / (1 << logProbability)} ({@code ec_dec_bit_logp}).
     *
     * @param logProbability the base 2 logarithm of the reciprocal of the probability of a 1
     * @return whether the bit is 1
     */
    boolean decodeBit(final int logProbability) {
        final var previousRange = range;
        final var boundary = previousRange >>> logProbability;
        final var isOne = value < boundary;
        if (isOne) {
            range = boundary;
        } else {
            value -= boundary;
            range = previousRange - boundary;
        }
        normalize();
        return isOne;
    }

    /**
     * Starts decoding a symbol of a distribution with a total, which is finished with {@link #update} once its
     * frequencies are known ({@code ec_decode}).
     *
     * @param total the sum of the distribution's frequencies, from 1 to 65535 for RFC 6716's
     * @return the value, from 0 below the total, which falls in the range of the symbol to pass to {@link #update}
     */
    int decode(final int total) {
        scale = range / total;
        final var found = value / scale + 1;
        return (int) (total - Math.min(found, total));
    }

    /**
     * Finishes decoding a symbol ({@code ec_dec_update}).
     *
     * @param low the cumulative frequency below the symbol
     * @param high the cumulative frequency up to and including the symbol
     * @param total the sum of all frequencies, the same as in {@link #decode}
     */
    void update(final int low, final int high, final int total) {
        final var excess = scale * (total - high);
        value -= excess;
        if (low > 0) {
            range = scale * (high - low);
        } else {
            range -= excess;
        }
        normalize();
    }

    /**
     * Decodes an integer that is uniform from 0 to below the size ({@code ec_dec_uint}), where the bits after the 8 or
     * so that the range decoder codes are raw bits.
     *
     * @param size how many values there are, from 2
     * @return the integer
     */
    int decodeUniform(final int size) {
        final var largest = size - 1;
        final var bits = bitLength(largest);
        if (bits <= 8) {
            final var symbol = decode(size);
            update(symbol, symbol + 1, size);
            return symbol;
        }
        final var rawBitCount = bits - 8;
        final var codedTotal = (largest >> rawBitCount) + 1;
        final var codedSymbol = decode(codedTotal);
        update(codedSymbol, codedSymbol + 1, codedTotal);
        final var uniform = codedSymbol << rawBitCount | decodeRawBits(rawBitCount);
        return Math.min(uniform, largest);
    }

    /**
     * Decodes bits that are not modelled ({@code ec_dec_bits}), which are read from the end of the frame backwards,
     * the first of them being the least significant bit of the value.
     *
     * @param count how many bits, from 0 to 32
     * @return the value, which has them as its low bits
     */
    int decodeRawBits(final int count) {
        while (windowBits < count) {
            window |= (long) readBackByte() << windowBits;
            windowBits += SYMBOL_BITS;
        }
        final var bits = (int) (window & ((1L << count) - 1));
        window >>>= count;
        windowBits -= count;
        totalBits += count;
        return bits;
    }

    /**
     * Decodes a value of CELT's Laplace distribution for coarse energy, which is 0 with a given frequency and falls
     * away on both sides by a decay ({@code ec_laplace_decode}, RFC 6716 section 4.3.2.1).
     *
     * @param zeroFrequency the frequency of 0 out of 32768
     * @param decay how quickly the frequencies fall, out of 16384 (so a decay of 16384 is a straight line)
     * @return the value, which is negative or positive
     */
    int decodeLaplace(final int zeroFrequency, final int decay) {
        scale = range >>> LAPLACE_TOTAL_BITS;
        final var found = LAPLACE_TOTAL - Math.min(value / scale + 1, LAPLACE_TOTAL);
        var decoded = 0;
        var low = 0L;
        var frequency = (long) zeroFrequency;
        if (found >= zeroFrequency) {
            decoded = 1;
            low = zeroFrequency;
            frequency =
                    1 + ((LAPLACE_TOTAL - 2 * LAPLACE_MIN_VALUES - zeroFrequency) * (LAPLACE_DECAY_HALF - decay) >> 15);
            while (frequency > 1 && found >= low + 2 * frequency) {
                decoded++;
                frequency *= 2;
                low += frequency;
                frequency = ((frequency - 2) * decay >> 15) + 1;
            }
            if (frequency <= 1) {
                final var distance = (found - low) >> 1;
                decoded += (int) distance;
                low += 2 * distance;
            }
            if (found < low + frequency) {
                decoded = -decoded;
            } else {
                low += frequency;
            }
        }
        update((int) low, (int) Math.min(low + frequency, LAPLACE_TOTAL), (int) LAPLACE_TOTAL);
        return decoded;
    }

    /**
     * Decodes an integer of a step distribution, which CELT codes a stereo angle with: the values from 0 to {@code k0}
     * have a frequency of 3 each, and the {@code k0} values after them, up to {@code 2 * k0}, a frequency of 1.
     *
     * @param k0 the last of the values with a frequency of 3
     * @return the value, from 0 to {@code 2 * k0}
     */
    int decodeStep(final int k0) {
        final var likelier = (k0 + 1) * 3;
        final var total = likelier + k0;
        final var symbol = decode(total);
        var decoded = symbol - (k0 + 1) * 2;
        var low = decoded - 1 - k0 + likelier;
        var high = decoded - k0 + likelier;
        if (symbol < likelier) {
            decoded = symbol / 3;
            low = 3 * decoded;
            high = 3 * (decoded + 1);
        }
        update(low, high, total);
        return decoded;
    }

    /**
     * Decodes an integer that is triangular, from 0 to {@code qn}, which CELT codes a stereo angle with where it is
     * likeliest in the middle: the frequency of {@code k} is {@code k + 1} up to the middle, then falling again.
     *
     * @param qn the largest value
     * @return the value
     */
    int decodeTriangular(final int qn) {
        final var half = (qn >> 1) + 1;
        final var total = half * half;
        final var center = decode(total);
        return finishTriangular(qn, total, center);
    }

    private int finishTriangular(final int qn, final int total, final int center) {
        int value;
        int low;
        int frequency;
        if (center < total >> 1) {
            value = (integerSquareRoot(8L * center + 1) - 1) >> 1;
            low = value * (value + 1) >> 1;
            frequency = value + 1;
        } else {
            value = (2 * (qn + 1) - integerSquareRoot(8L * (total - center - 1) + 1)) >> 1;
            low = total - ((qn + 1 - value) * (qn + 2 - value) >> 1);
            frequency = qn + 1 - value;
        }
        update(low, low + frequency, total);
        return value;
    }

    private int integerSquareRoot(final long number) {
        return (int) Math.sqrt((double) number);
    }

    private int bitLength(final long number) {
        return Long.SIZE - Long.numberOfLeadingZeros(number);
    }

    private void normalize() {
        while (range <= CODE_BOTTOM) {
            totalBits += SYMBOL_BITS;
            range <<= SYMBOL_BITS;
            final var previous = remainder;
            remainder = readFrontByte();
            final var symbol = (previous << SYMBOL_BITS | remainder) >> FIRST_BYTE_SHIFT;
            value = ((value << SYMBOL_BITS) + (SYMBOL_MASK & ~symbol)) & (CODE_TOP - 1);
        }
    }

    private int readFrontByte() {
        if (frontPosition >= length) {
            return 0;
        }
        final var next = data[start + frontPosition] & SYMBOL_MASK;
        frontPosition++;
        return next;
    }

    private int readBackByte() {
        if (backPosition >= length) {
            return 0;
        }
        final var next = data[start + length - 1 - backPosition] & SYMBOL_MASK;
        backPosition++;
        return next;
    }
}
