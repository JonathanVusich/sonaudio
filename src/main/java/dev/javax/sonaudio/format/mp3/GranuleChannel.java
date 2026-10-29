package dev.javax.sonaudio.format.mp3;

/**
 * How a granule codes one channel, from the frame's side information (ISO/IEC 11172-3 section 2.4.2.7, ISO/IEC 13818-3
 * section 2.4.2.7), as {@link Mp3Decoder} reads it.
 *
 * @param part23Length how many bits the channel's scale factors and Huffman-coded values take in the main data
 * @param bigValues how many pairs of values the big-value tables code, before the count1 values
 * @param scalefacCompress how many bits the scale factors take, as an index into the spec's tables
 * @param preflag 1 when the pretab is added to the long blocks' scale factors, which MPEG-2 works out from
 *     scalefac_compress
 * @param scalefacScale 0 when scale factors scale in steps of sqrt(2), and 1 in steps of 2
 * @param count1Code the table that codes the values of 0 or 1 after the big values
 * @param scaleFactorSharing MPEG-1's scfsi for the second granule, whose bits 3 to 0 say it reuses the first granule's
 *     scale factors for bands 0 to 5, 6 to 10, 11 to 15 and 16 to 20; 0 otherwise
 */
record GranuleChannel(
        int part23Length,
        int bigValues,
        int globalGain,
        int scalefacCompress,
        Windowing windowing,
        int preflag,
        int scalefacScale,
        HuffmanCode count1Code,
        int scaleFactorSharing) {}
