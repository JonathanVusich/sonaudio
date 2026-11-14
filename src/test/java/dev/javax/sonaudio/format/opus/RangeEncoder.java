package dev.javax.sonaudio.format.opus;

/**
 * The range encoder of RFC 6716 section 5.1, which the decoder's tests encode with, so that they decode what the RFC's
 * own definitions encode. Symbols go to the front of the buffer and raw bits to the end of it, where the two must not
 * meet.
 */
final class RangeEncoder {

    private static final long CODE_TOP = 1L << 31;
    private static final long CODE_BOTTOM = CODE_TOP >>> 8;
    private static final int CODE_SHIFT = 23;

    private final byte[] buffer;
    private int frontOffset;
    private int backOffset;
    private long backWindow;
    private int backBits;
    private int totalBits = 33;
    private long range = CODE_TOP;
    private long value;
    private int remainder = -1;
    private int extension;

    RangeEncoder(final int storage) {
        this.buffer = new byte[storage];
    }

    int tell() {
        return totalBits - bitLength(range);
    }

    // What RFC 6716 section 4.1.6.2 approximates: the bits used, to an eighth, less the bits the range still holds.
    int tellFraction() {
        final var remaining = Math.floor(8 * (Math.log(range) / Math.log(2) + 1));
        return (int) (8L * totalBits - (long) remaining);
    }

    void encode(final int low, final int high, final int total) {
        final var scaled = range / total;
        if (low > 0) {
            value += range - scaled * (total - low);
            range = scaled * (high - low);
        } else {
            range -= scaled * (total - high);
        }
        normalize();
    }

    void encodeBit(final int bit, final int logProbability) {
        final var boundary = range >>> logProbability;
        if (bit == 1) {
            value += range - boundary;
            range = boundary;
        } else {
            range -= boundary;
        }
        normalize();
    }

    void encodeIcdf(final int symbol, final int[] icdf, final int tableBits) {
        final var scaled = range >>> tableBits;
        if (symbol > 0) {
            value += range - scaled * icdf[symbol - 1];
            range = scaled * (icdf[symbol - 1] - icdf[symbol]);
        } else {
            range -= scaled * icdf[symbol];
        }
        normalize();
    }

    void encodeUniform(final int number, final int size) {
        final var largest = size - 1;
        final var bits = bitLength(largest);
        if (bits <= 8) {
            encode(number, number + 1, size);
            return;
        }
        final var rawBits = bits - 8;
        final var total = (largest >> rawBits) + 1;
        final var coded = number >> rawBits;
        encode(coded, coded + 1, total);
        encodeRawBits(number & ((1 << rawBits) - 1), rawBits);
    }

    void encodeRawBits(final int bits, final int count) {
        var window = backWindow;
        var used = backBits;
        if (used + count > 32) {
            do {
                writeBackByte((int) (window & 0xFF));
                window >>>= 8;
                used -= 8;
            } while (used >= 8);
        }
        window |= (long) bits << used;
        backWindow = window;
        backBits = used + count;
        totalBits += count;
    }

    // The encoder of RFC 6716 section 4.3.2.1, with the value it really coded: one past what a Laplace can reach is
    // coded as the nearest that it can.
    int encodeLaplace(final int wanted, final int zeroFrequency, final int decay) {
        final var interval = laplaceInterval(wanted, zeroFrequency, decay);
        encode(interval[0], interval[0] + interval[1], 32768);
        return interval[2];
    }

    // The low end, the length and the value actually coded of the interval of 32768 that a value takes.
    int[] laplaceInterval(final int wanted, final int zeroFrequency, final int decay) {
        var frequency = zeroFrequency;
        var low = 0;
        var coded = wanted;
        if (wanted != 0) {
            var sign = 0;
            if (wanted < 0) {
                sign = -1;
            }
            final var magnitude = (wanted + sign) ^ sign;
            low = frequency;
            frequency = (32768 - 32 - zeroFrequency) * (16384 - decay) >> 15;
            var step = 1;
            while (frequency > 0 && step < magnitude) {
                frequency *= 2;
                low += frequency + 2;
                frequency = frequency * decay >> 15;
                step++;
            }
            if (frequency == 0) {
                final var maxPairs = (32768 - low - sign) >> 1;
                final var distance = Math.min(magnitude - step, maxPairs - 1);
                low += 2 * distance + 1 + sign;
                frequency = Math.min(1, 32768 - low);
                coded = (step + distance + sign) ^ sign;
            } else {
                frequency += 1;
                low += frequency & ~sign;
            }
        }
        return new int[] {low, frequency, coded};
    }

    void encodeStep(final int number, final int k0) {
        final var likelier = (k0 + 1) * 3;
        final var total = likelier + k0;
        if (number <= k0) {
            encode(3 * number, 3 * number + 3, total);
        } else {
            final var low = number - 1 - k0 + likelier;
            encode(low, low + 1, total);
        }
    }

    void encodeTriangular(final int number, final int qn) {
        final var half = (qn >> 1) + 1;
        final var total = half * half;
        if (number <= qn >> 1) {
            final var low = number * (number + 1) >> 1;
            encode(low, low + number + 1, total);
        } else {
            final var low = total - ((qn + 1 - number) * (qn + 2 - number) >> 1);
            encode(low, low + qn + 1 - number, total);
        }
    }

    byte[] finish() {
        var bits = 32 - bitLength(range);
        var mask = (CODE_TOP - 1) >>> bits;
        var end = (value + mask) & ~mask;
        if ((end | mask) >= value + range) {
            bits++;
            mask >>>= 1;
            end = (value + mask) & ~mask;
        }
        while (bits > 0) {
            carryOut((int) (end >>> CODE_SHIFT));
            end = (end << 8) & (CODE_TOP - 1);
            bits -= 8;
        }
        if (remainder >= 0 || extension > 0) {
            carryOut(0);
        }
        flushBackWindow();
        return buffer.clone();
    }

    private void flushBackWindow() {
        var window = backWindow;
        var used = backBits;
        while (used >= 8) {
            writeBackByte((int) (window & 0xFF));
            window >>>= 8;
            used -= 8;
        }
        if (used > 0) {
            buffer[buffer.length - backOffset - 1] |= (byte) window;
        }
    }

    private void normalize() {
        while (range <= CODE_BOTTOM) {
            carryOut((int) (value >>> CODE_SHIFT));
            value = (value << 8) & (CODE_TOP - 1);
            range <<= 8;
            totalBits += 8;
        }
    }

    private void carryOut(final int symbol) {
        if (symbol == 0xFF) {
            extension++;
            return;
        }
        final var carry = symbol >> 8;
        if (remainder >= 0) {
            buffer[frontOffset++] = (byte) (remainder + carry);
        }
        while (extension > 0) {
            buffer[frontOffset++] = (byte) (0xFF + carry);
            extension--;
        }
        remainder = symbol & 0xFF;
    }

    private void writeBackByte(final int next) {
        backOffset++;
        buffer[buffer.length - backOffset] = (byte) next;
    }

    private int bitLength(final long number) {
        return Long.SIZE - Long.numberOfLeadingZeros(number);
    }
}
