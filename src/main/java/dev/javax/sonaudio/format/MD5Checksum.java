package dev.javax.sonaudio.format;

import java.util.HexFormat;
import lombok.Value;

@Value
public class MD5Checksum {

    byte[] checksum;

    @Override
    public String toString() {
        return HexFormat.of().formatHex(checksum);
    }
}
