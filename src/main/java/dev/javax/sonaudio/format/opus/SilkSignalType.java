package dev.javax.sonaudio.format.opus;

/**
 * What a SILK frame carries, which its first symbols say: nothing (the voice activity detector found it inactive), or
 * speech that is unvoiced or voiced. Voiced speech is the kind with a pitch, which is what the long-term prediction
 * filter follows.
 */
enum SilkSignalType {
    INACTIVE(0),
    UNVOICED(0),
    VOICED(1);

    private final int voicedIndex;

    SilkSignalType(final int voicedIndex) {
        this.voicedIndex = voicedIndex;
    }

    /**
     * @return 1 for voiced and 0 for the others, which is how the tables that only tell voiced from unvoiced are
     *     indexed
     */
    int voicedIndex() {
        return voicedIndex;
    }

    /**
     * @return 0 for inactive, 1 for unvoiced and 2 for voiced, which is how the tables that tell all three apart are
     *     indexed
     */
    int typeIndex() {
        return ordinal();
    }
}
