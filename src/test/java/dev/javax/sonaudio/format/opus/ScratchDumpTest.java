package dev.javax.sonaudio.format.opus;

import dev.javax.sonaudio.samples.OpusSample;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class ScratchDumpTest {
    @Test
    void dump() throws Exception {
        final var sample = OpusSample.valueOf("CELT_AUDIO_MONO_20MS");
        final var ref = ByteBuffer.wrap(sample.referencePcm().readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        try (final var channel = sample.channel()) {
            final var audio = new OpusParser().parse(channel).audio();
            final var chs = audio.streamInfo().channelCount();
            var pos = 0;
            final var out = new StringBuilder();
            var frameNo = 0;
            while (audio.hasNextFrame()) {
                final var frame = audio.nextFrame();
                var bad = 0;
                for (var i = 0; i < frame.sampleCount(); i++) {
                    for (var c = 0; c < chs; c++) {
                        final var d = (int) Math.clamp(Math.rint(frame.channelSample(c, i) * 32768.0), -32768, 32767);
                        final var e = ref.getShort(pos * 2);
                        if (Math.abs(d - e) > 1) {
                            bad++;
                        }
                        pos++;
                    }
                }
                out.append(String.format("frame %d: %d samples, %d bad%n", frameNo, frame.sampleCount(), bad));
                if (frameNo == 0) {
                    out.append(correlation(frame, ref));
                }
                frameNo++;
            }
            System.out.println(out);
        }
    }

    private CharSequence correlation(final dev.javax.sonaudio.format.FloatPcmFrame frame, final ByteBuffer ref) {
        final var g = new double[frame.sampleCount()];
        final var w = new double[frame.sampleCount()];
        for (var k = 0; k < g.length; k++) {
            g[k] = frame.channelSample(0, k) * 32768.0;
            w[k] = ref.getShort(k * 2);
        }
        final var sb = new StringBuilder();
        for (var lag = -4; lag <= 4; lag++) {
            var s = 0.0;
            var gg = 0.0;
            var ww = 0.0;
            for (var k = 70; k < g.length - 70; k++) {
                s += g[k] * w[k + lag];
                gg += g[k] * g[k];
                ww += w[k + lag] * w[k + lag];
            }
            sb.append(String.format("lag %d corr %.4f gain %.3f%n", lag, s / Math.sqrt(gg * ww), Math.sqrt(gg / ww)));
        }
        final var sb2 = new StringBuilder();
        for (var hz = 100; hz <= 2000; hz += 100) {
            double gr = 0, gi = 0, wr = 0, wi = 0;
            for (var k = 0; k < g.length; k++) {
                final var ph = 2 * Math.PI * hz * k / 48000.0;
                gr += g[k] * Math.cos(ph);
                gi += g[k] * Math.sin(ph);
                wr += w[k] * Math.cos(ph);
                wi += w[k] * Math.sin(ph);
            }
            sb2.append(String.format("hz %d mine %.0f ref %.0f%n", hz, Math.hypot(gr, gi), Math.hypot(wr, wi)));
        }
        sb.append(sb2);
        return sb;
    }
}
