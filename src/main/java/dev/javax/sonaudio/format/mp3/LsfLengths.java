package dev.javax.sonaudio.format.mp3;

/**
 * How many bits each partition of an MPEG-2 granule's scale factors takes, and which table gives how many scale factors
 * each partition holds, both from its scalefac_compress (ISO/IEC 13818-3 section 2.4.3.2), as {@link Mp3Decoder} works
 * them out.
 *
 * @param table from 0 to 5: 0 to 2 for scalefac_compress below 400, below 500, and from 500, and 3 to 5 likewise for
 *     intensity stereo's right channel
 * @param slens how many bits each of the four partitions' scale factors take
 */
record LsfLengths(int table, int[] slens) {

    @Override
    public int[] slens() {
        return slens.clone();
    }

    int slen(final int partition) {
        return slens[partition];
    }
}
