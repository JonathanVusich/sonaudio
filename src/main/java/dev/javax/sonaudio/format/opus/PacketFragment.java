package dev.javax.sonaudio.format.opus;

/**
 * The bytes of one packet that an Ogg page holds: all of it, or the start or end of it when it crosses pages.
 *
 * @param bytes the fragment's bytes, which may be none
 * @param end whether the packet ends with this fragment
 */
record PacketFragment(byte[] bytes, FragmentEnd end) {

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
