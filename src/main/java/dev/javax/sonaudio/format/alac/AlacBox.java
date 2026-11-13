package dev.javax.sonaudio.format.alac;

/**
 * The MPEG-4 boxes the ALAC parser reads, by their four-character types. Every other box is {@link #OTHER}, and is
 * skipped.
 */
enum AlacBox {
    FTYP("ftyp"),
    MOOV("moov"),
    MOOF("moof"),
    MDAT("mdat"),
    MVHD("mvhd"),
    MVEX("mvex"),
    TRAK("trak"),
    EDTS("edts"),
    ELST("elst"),
    MDIA("mdia"),
    MDHD("mdhd"),
    HDLR("hdlr"),
    MINF("minf"),
    STBL("stbl"),
    STSD("stsd"),
    STTS("stts"),
    STSC("stsc"),
    STSZ("stsz"),
    STZ2("stz2"),
    STCO("stco"),
    CO64("co64"),
    ALAC("alac"),
    WAVE("wave"),
    FRMA("frma"),
    UDTA("udta"),
    META("meta"),
    ILST("ilst"),
    TITLE("©nam"),
    ARTIST("©ART"),
    ALBUM_ARTIST("aART"),
    ALBUM("©alb"),
    COMPOSER("©wrt"),
    DATE("©day"),
    TRACK_NUMBER("trkn"),
    DISC_NUMBER("disk"),
    FREEFORM("----"),
    MEAN("mean"),
    NAME("name"),
    DATA("data"),
    OTHER("");

    private final int type;

    AlacBox(final String name) {
        this.type = typeOf(name);
    }

    /**
     * @return the box's type, the bytes of its four characters as a big-endian number
     */
    int type() {
        return type;
    }

    /**
     * @param type a box's type as it's read from the file
     * @return the box it is, or {@link #OTHER} for one the parser doesn't read
     */
    static AlacBox ofType(final int type) {
        for (final var box : values()) {
            if (box.type == type) {
                return box;
            }
        }
        return OTHER;
    }

    /**
     * @param name four characters, each a byte of ISO-8859-1
     * @return the box type they spell
     */
    static int typeOf(final String name) {
        var code = 0;
        for (var i = 0; i < name.length(); i++) {
            code = (code << Byte.SIZE) | (name.charAt(i) & 0xff);
        }
        return code;
    }
}
