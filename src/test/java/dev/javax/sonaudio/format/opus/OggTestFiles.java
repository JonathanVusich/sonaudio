package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.exception.InvalidFormatException;
import dev.javax.sonaudio.format.opus.NextPage.Found;
import dev.javax.sonaudio.internal.Crc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Makes and changes Ogg files byte by byte, for tests that need a malformed or a particular one.
 */
@UtilityClass
class OggTestFiles {

    static final int CONTINUED = 0x01;
    static final int BEGINNING = 0x02;
    static final int END = 0x04;
    static final long NO_GRANULE = -1L;
    static final int HEADER_SIZE = 27;

    /**
     * @return a page of whole packets, each laced as Ogg does, with a good CRC
     */
    static byte[] packetPage(
            final int flags, final long granule, final int serial, final int sequence, final byte[]... packets) {
        final var lacing = new ByteArrayOutputStream();
        final var body = new ByteArrayOutputStream();
        for (final var packet : packets) {
            var remaining = packet.length;
            while (remaining >= 255) {
                lacing.write(255);
                remaining -= 255;
            }
            lacing.write(remaining);
            body.writeBytes(packet);
        }
        return page(flags, granule, serial, sequence, lacing.toByteArray(), body.toByteArray());
    }

    /**
     * @return a page with the segment table given, with a good CRC
     */
    static byte[] page(
            final int flags,
            final long granule,
            final int serial,
            final int sequence,
            final byte[] lacing,
            final byte[] body) {
        final var page =
                ByteBuffer.allocate(HEADER_SIZE + lacing.length + body.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put(new byte[] {'O', 'g', 'g', 'S', 0, (byte) flags});
        page.putLong(granule).putInt(serial).putInt(sequence).putInt(0);
        page.put((byte) lacing.length).put(lacing).put(body);
        final var bytes = page.array();
        fixCrc(bytes, 0);
        return bytes;
    }

    /**
     * Writes the right CRC-32 into the page at an offset, after its bytes were changed.
     */
    static void fixCrc(final byte[] file, final int pageOffset) {
        final var segments = file[pageOffset + HEADER_SIZE - 1] & 0xFF;
        var size = HEADER_SIZE + segments;
        for (var segment = 0; segment < segments; segment++) {
            size += file[pageOffset + HEADER_SIZE + segment] & 0xFF;
        }
        Arrays.fill(file, pageOffset + 22, pageOffset + 26, (byte) 0);
        final var crc = new Crc(0x04C11DB7L, 32);
        crc.update(file, pageOffset, size);
        ByteBuffer.wrap(file, pageOffset + 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) crc.getValue());
    }

    /**
     * @return where the payload of the page at an offset starts
     */
    static int bodyOffset(final byte[] file, final int pageOffset) {
        return pageOffset + HEADER_SIZE + (file[pageOffset + HEADER_SIZE - 1] & 0xFF);
    }

    /**
     * @return where each page of the file starts
     */
    static List<Integer> pageOffsets(final byte[] file) throws IOException, InvalidFormatException {
        final var offsets = new ArrayList<Integer>();
        final var parser = new OpusParser();
        final var input = new ByteArrayInputStream(file);
        var offset = 0;
        while (parser.readPage(input) instanceof Found) {
            offsets.add(offset);
            offset = file.length - input.available();
        }
        return offsets;
    }

    /**
     * @return an {@code OpusHead} of mapping family 0
     */
    static byte[] opusHead(final int channelCount, final int preSkip, final int outputGain) {
        final var head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put(new byte[] {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, (byte) channelCount});
        head.putShort((short) preSkip)
                .putInt(48000)
                .putShort((short) outputGain)
                .put((byte) 0);
        return head.array();
    }

    /**
     * @return an {@code OpusTags} with no vendor and no fields
     */
    static byte[] opusTags() {
        return new byte[] {'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0};
    }

    /**
     * @return a packet of code 0 that has a frame of zeros of the configuration's length
     */
    static byte[] audioPacket(final int config, final int payloadSize) {
        final var packet = new byte[1 + payloadSize];
        packet[0] = (byte) (config << 3);
        return packet;
    }

    /**
     * Makes a mono Ogg Opus file whose audio packets are each on a page of their own, the last of which ends the
     * stream at the granule position given, and the others at the samples that the packets so far hold.
     */
    static byte[] opusFile(final int serial, final int preSkip, final long endGranule, final byte[]... packets)
            throws InvalidFormatException {
        final var file = new ByteArrayOutputStream();
        file.writeBytes(packetPage(BEGINNING, 0, serial, 0, opusHead(1, preSkip, 0)));
        file.writeBytes(packetPage(0, 0, serial, 1, opusTags()));
        var samples = 0L;
        for (var index = 0; index < packets.length; index++) {
            samples += new OggPacket(packets[index], 0, 0).duration();
            var flags = 0;
            var granule = samples;
            if (index == packets.length - 1) {
                flags = END;
                granule = endGranule;
            }
            file.writeBytes(packetPage(flags, granule, serial, 2 + index, packets[index]));
        }
        return file.toByteArray();
    }

    static byte[] concatenate(final byte[]... parts) {
        final var joined = new ByteArrayOutputStream();
        for (final var part : parts) {
            joined.writeBytes(part);
        }
        return joined.toByteArray();
    }

    /**
     * @return the audio packets of an Ogg Opus file, as the stream gives them
     */
    static List<OggPacket> audioPackets(final byte[] file) throws IOException, InvalidFormatException {
        final var parser = new OpusParser();
        final var input = new ByteArrayInputStream(file);
        final var firstPage = (Found) parser.readPage(input);
        final var info =
                parser.parse(new SeekableInMemoryByteChannel(file)).audio().streamInfo();
        final var stream = new OggStream(
                parser, new ByteArrayInputStream(file), firstPage.page().serial(), info.preSkip());
        stream.nextHeaderPacket();
        stream.nextHeaderPacket();
        final var packets = new ArrayList<OggPacket>();
        while (stream.hasNextPacket()) {
            packets.add(stream.nextAudioPacket());
        }
        return packets;
    }
}
