package dev.javax.sonaudio.format.mp3;

import dev.javax.bitstream.BitInputStream;
import java.io.IOException;

/**
 * A Huffman code that Layer III codes values with, decoded through lookup tables, as it runs for every value of every
 * granule. The next few bits index a root table, which gives the value of any codeword that short and how many bits it
 * takes; a longer codeword's first bits index a subtable that the bits after them index in turn.
 *
 * <p>The code is given as its values in codeword order, with each codeword's length, from which the codewords follow:
 * each is the one before it plus 1, at its own length. The code must be complete and prefix-free, as the spec's tables
 * are, so that every bit string starts with exactly one codeword.
 *
 * <p>Codewords are looked ahead with {@link BitInputStream#peekBits}, so the stream must hold up to 19 bits after the
 * last one, as the bit reservoir pads the main data with.
 */
final class HuffmanCode {

    // Most of the spec's codewords are 8 bits or fewer, so one lookup decodes them, and the subtables stay small.
    private static final int MAX_ROOT_BITS = 8;
    // Codewords are worked out left-aligned in this many bits, so a longer codeword follows a shorter one with no
    // shift.
    private static final int CODE_SPACE = 32;

    private final int rootBits;
    // Each entry's value; or, for a root entry its codewords are too long for, where its subtable starts.
    private final int[] values;
    // How many bits each entry's codeword takes after the bits that indexed its table; or, for a root entry that links
    // to a subtable, minus how many bits index the subtable.
    private final int[] lengths;
    // How many bits each value's codeword takes, by value: each value has one codeword.
    private final int[] codeLengths;

    /**
     * @param values the values in the order of their codewords, as bit strings
     * @param lengths how many bits each value's codeword takes
     * @throws IllegalArgumentException when the codewords aren't a complete, prefix-free code
     */
    HuffmanCode(final int[] values, final int[] lengths) {
        final var codes = codewords(lengths);
        this.rootBits = Math.min(MAX_ROOT_BITS, maxLength(lengths));
        final var subtableBits = subtableBits(codes, lengths);
        final var size = tableSize(subtableBits);
        this.values = new int[size];
        this.lengths = new int[size];
        linkSubtables(subtableBits);
        for (var index = 0; index < values.length; index++) {
            addCodeword(codes[index], lengths[index], values[index]);
        }
        this.codeLengths = codeLengths(values, lengths);
    }

    /**
     * @return how many bits the value's codeword takes, which a value read with {@link #readValue} took
     */
    int codeLength(final int value) {
        return codeLengths[value];
    }

    /**
     * @return the value whose codeword the bits start with
     */
    int readValue(final BitInputStream bits) throws IOException {
        final var value = peekValue(bits);
        // Table 0's one codeword is empty, and the stream reads no fewer than 1 bit.
        if (codeLengths[value] > 0) {
            bits.readBits(codeLengths[value]);
        }
        return value;
    }

    /**
     * @return the value whose codeword the bits start with, leaving the bits to be read
     */
    int peekValue(final BitInputStream bits) throws IOException {
        if (rootBits == 0) {
            return values[0];
        }
        final var root = (int) bits.peekBits(rootBits);
        final var length = lengths[root];
        if (length > 0) {
            return values[root];
        }
        final var subtableBits = -length;
        final var subtableIndex = (int) bits.peekBits(rootBits + subtableBits) & ((1 << subtableBits) - 1);
        return values[values[root] + subtableIndex];
    }

    // Each codeword is the one before it plus 1, so its bits follow on from the one before's. In a complete,
    // prefix-free code in codeword order, that always leaves a codeword's bits after its length 0, and the last one
    // ends the code space.
    private static long[] codewords(final int[] lengths) {
        final var codes = new long[lengths.length];
        var code = 0L;
        for (var index = 0; index < lengths.length; index++) {
            final var step = 1L << (CODE_SPACE - lengths[index]);
            if (code % step != 0 || code + step > 1L << CODE_SPACE) {
                throw new IllegalArgumentException("Codeword %d starts with another".formatted(index));
            }
            codes[index] = code;
            code += step;
        }
        if (code != 1L << CODE_SPACE) {
            throw new IllegalArgumentException("Some bit strings start with no codeword");
        }
        return codes;
    }

    private static int[] codeLengths(final int[] values, final int[] lengths) {
        var maxValue = 0;
        for (final var value : values) {
            maxValue = Math.max(maxValue, value);
        }
        final var codeLengths = new int[maxValue + 1];
        for (var index = 0; index < values.length; index++) {
            codeLengths[values[index]] = lengths[index];
        }
        return codeLengths;
    }

    private static int maxLength(final int[] lengths) {
        var max = 0;
        for (final var length : lengths) {
            max = Math.max(max, length);
        }
        return max;
    }

    // How many bits index the subtable of each root entry: enough for its longest codeword, or 0 for an entry with
    // none too long for the root table.
    private int[] subtableBits(final long[] codes, final int[] lengths) {
        final var bits = new int[1 << rootBits];
        for (var index = 0; index < codes.length; index++) {
            final var root = rootIndex(codes[index]);
            bits[root] = Math.max(bits[root], lengths[index] - rootBits);
        }
        return bits;
    }

    private int tableSize(final int[] subtableBits) {
        var size = 1 << rootBits;
        for (final var bits : subtableBits) {
            if (bits > 0) {
                size += 1 << bits;
            }
        }
        return size;
    }

    // Each subtable follows the ones before it, after the root table.
    private void linkSubtables(final int[] subtableBits) {
        var start = 1 << rootBits;
        for (var root = 0; root < subtableBits.length; root++) {
            if (subtableBits[root] > 0) {
                values[root] = start;
                lengths[root] = -subtableBits[root];
                start += 1 << subtableBits[root];
            }
        }
    }

    // A codeword shorter than its table's index fills every entry whose bits start with it.
    private void addCodeword(final long code, final int length, final int value) {
        if (length <= rootBits) {
            fillEntries(rootIndex(code), rootBits - length, value, length);
            return;
        }
        final var root = rootIndex(code);
        final var indexBits = -lengths[root];
        final var subtableIndex = (int) (code >>> (CODE_SPACE - rootBits - indexBits)) & ((1 << indexBits) - 1);
        final var subtableLength = length - rootBits;
        fillEntries(values[root] + subtableIndex, indexBits - subtableLength, value, subtableLength);
    }

    private void fillEntries(final int first, final int freeBits, final int value, final int length) {
        for (var entry = first; entry < first + (1 << freeBits); entry++) {
            values[entry] = value;
            lengths[entry] = length;
        }
    }

    private int rootIndex(final long code) {
        return (int) (code >>> (CODE_SPACE - rootBits));
    }
}
