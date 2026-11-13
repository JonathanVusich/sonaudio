package dev.javax.sonaudio.format.opus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the integer line spectral frequency arithmetic against the values FFmpeg's {@code silk.c} gives: its static
 * functions, compiled on their own in the reference image and run on these inputs, which cover typical, crowded,
 * peaky and degenerate frequencies, with the range limiting and bandwidth expansion that the last ones need.
 */
class SilkLpcConverterTest {

    private static final int MAX_ORDER = 16;

    private final SilkLpcConverter converter = new SilkLpcConverter();
    private static final float Q12_ONE = 4096.0f;

    private enum Stability {
        STABLE,
        UNSTABLE
    }

    private record LpcCase(int order, int[] nlsf, int[] expectedQ12) {}

    private record StabilisationCase(int order, int[] nlsf, int[] expected) {}

    private record StabilityCase(int order, int[] coefficientsQ12, Stability stability) {}

    private static final List<LpcCase> LPC_CASES = List.of(
            new LpcCase(10, new int[] {2978, 5957, 8936, 11915, 14894, 17873, 20852, 23831, 26810, 29789}, new int[] {
                1, 3, 0, 2, 0, -1, 0, 1, 0, 1
            }),
            new LpcCase(
                    16,
                    new int[] {
                        1927, 3855, 5782, 7710, 9637, 11565, 13492, 15420, 17347, 19275, 21202, 23130, 25057, 26985,
                        28912, 30840
                    },
                    new int[] {2, -1, 1, -1, 0, -1, 0, -3, 0, -5, 0, -3, 0, -5, 0, 0}),
            new LpcCase(10, new int[] {500, 1126, 2368, 5690, 6051, 6644, 6887, 8854, 9154, 9870}, new int[] {
                9453, -10215, 6796, -3079, 991, -230, 38, -4, 0, 0
            }),
            new LpcCase(
                    16,
                    new int[] {
                        2844, 4357, 8149, 9485, 11141, 11795, 14684, 17379, 20030, 20960, 24316, 24763, 27973, 31116,
                        32767, 32767
                    },
                    new int[] {
                        -7477, -4497, -3112, -3714, -3122, -561, 1288, 330, -671, -1209, -936, -1624, -1979, -1410,
                        -1606, -838
                    }),
            new LpcCase(10, new int[] {968, 3931, 6631, 9727, 13480, 17105, 17971, 19315, 20596, 22721}, new int[] {
                8761, -13401, 19034, -20780, 18803, -14815, 9831, -4913, 1764, -318
            }),
            new LpcCase(
                    16,
                    new int[] {
                        466, 3987, 6264, 8620, 10859, 12505, 15105, 17745, 19134, 21050, 23960, 25775, 26287, 29773,
                        30600, 31509
                    },
                    new int[] {-6529, -2136, 1240, 1395, 1133, 1190, 1596, 1746, 329, -88, 1378, 1172, 598, 166, 16, 263
                    }),
            new LpcCase(10, new int[] {4764, 9507, 12623, 13355, 14921, 17127, 18418, 20116, 20341, 25249}, new int[] {
                2262, -9765, 5629, -9593, 5209, -3696, 1522, 213, -328, 408
            }),
            new LpcCase(
                    16,
                    new int[] {
                        3111, 5017, 5618, 10609, 10858, 12690, 17804, 22461, 23803, 28092, 30797, 32767, 32767, 32767,
                        32767, 32767
                    },
                    new int[] {-9803, -7516, -855, 961, -360, -484, 53, 87, -61, -58, -6, 8, 6, 3, 1, 0}),
            new LpcCase(10, new int[] {683, 3412, 3682, 7878, 11089, 15641, 17590, 22638, 23675, 24572}, new int[] {
                8759, -8252, 8249, -9495, 8532, -7144, 5085, -1964, -463, 772
            }),
            new LpcCase(
                    16,
                    new int[] {
                        1288, 4680, 8895, 10176, 14549, 15669, 17377, 21884, 24980, 26761, 28529, 32540, 32767, 32767,
                        32767, 32767
                    },
                    new int[] {-11199, -12002, -6154, -1312, 259, 510, 419, 231, 123, 71, 22, -7, -10, -4, -1, 0}),
            new LpcCase(10, new int[] {500, 3000, 6000, 9000, 12000, 15000, 15004, 24000, 28000, 31000}, new int[] {
                6428, -1395, -4792, 7045, -2543, -3889, 4906, -423, -2869, 1604
            }),
            new LpcCase(
                    16,
                    new int[] {
                        200, 300, 4000, 8000, 12000, 16000, 20000, 20003, 24000, 24100, 28000, 30000, 30500, 31000,
                        32000, 32700
                    },
                    new int[] {
                        -11833, -10349, 2906, 12035, 8266, 52, -3044, -1619, -339, -247, -105, 238, 218, -16, -74, -21
                    }),
            new LpcCase(10, new int[] {110, 400, 900, 5000, 9000, 9010, 20000, 31000, 32500, 32760}, new int[] {
                6696, 4616, -14532, 4153, 10043, -7603, -1842, 3086, -256, -278
            }),
            new LpcCase(10, new int[] {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000}, new int[] {
                9451, -9817, 6044, -2443, 677, -130, 17, -1, 0, 0
            }),
            new LpcCase(
                    16,
                    new int[] {260, 270, 280, 290, 300, 310, 320, 330, 340, 350, 360, 370, 380, 390, 400, 410},
                    new int[] {9444, -10208, 6866, -3216, 1113, -294, 61, -10, 1, 0, 0, 0, 0, 0, 0, 0}),
            new LpcCase(10, new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, new int[] {
                9451, -9813, 6038, -2438, 675, -130, 17, -1, 0, 0
            }),
            new LpcCase(16, new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, new int[] {
                9444, -10207, 6864, -3215, 1112, -294, 60, -10, 1, 0, 0, 0, 0, 0, 0, 0
            }),
            new LpcCase(
                    10,
                    new int[] {32758, 32759, 32760, 32761, 32762, 32763, 32764, 32765, 32766, 32767},
                    new int[] {-9451, -9813, -6038, -2438, -675, -130, -17, -1, 0, 0}),
            new LpcCase(10, new int[] {40, 80, 120, 160, 200, 240, 280, 320, 360, 400}, new int[] {
                9451, -9814, 6039, -2439, 676, -130, 17, -1, 0, 0
            }));

    private static final List<StabilisationCase> STABILISATION_CASES = List.of(
            new StabilisationCase(
                    10,
                    new int[] {2978, 5957, 8936, 11915, 14894, 17873, 20852, 23831, 26810, 29789},
                    new int[] {2978, 5957, 8936, 11915, 14894, 17873, 20852, 23831, 26810, 29789}),
            new StabilisationCase(
                    16,
                    new int[] {
                        1927, 3855, 5782, 7710, 9637, 11565, 13492, 15420, 17347, 19275, 21202, 23130, 25057, 26985,
                        28912, 30840
                    },
                    new int[] {
                        1927, 3855, 5782, 7710, 9637, 11565, 13492, 15420, 17347, 19275, 21202, 23130, 25057, 26985,
                        28912, 30840
                    }),
            new StabilisationCase(10, new int[] {500, 1126, 2368, 5690, 6051, 6644, 6887, 8854, 9154, 9870}, new int[] {
                500, 1126, 2368, 5690, 6051, 6644, 6887, 8854, 9154, 9870
            }),
            new StabilisationCase(
                    16,
                    new int[] {
                        2844, 4357, 8149, 9485, 11141, 11795, 14684, 17379, 20030, 20960, 24316, 24763, 27973, 31116,
                        32767, 32767
                    },
                    new int[] {
                        2844, 4357, 8149, 9485, 11141, 11795, 14684, 17379, 20030, 20960, 24316, 24763, 27973, 31116,
                        32418, 32421
                    }),
            new StabilisationCase(
                    10,
                    new int[] {968, 3931, 6631, 9727, 13480, 17105, 17971, 19315, 20596, 22721},
                    new int[] {968, 3931, 6631, 9727, 13480, 17105, 17971, 19315, 20596, 22721}),
            new StabilisationCase(
                    16,
                    new int[] {
                        466, 3987, 6264, 8620, 10859, 12505, 15105, 17745, 19134, 21050, 23960, 25775, 26287, 29773,
                        30600, 31509
                    },
                    new int[] {
                        466, 3987, 6264, 8620, 10859, 12505, 15105, 17745, 19134, 21050, 23960, 25775, 26287, 29773,
                        30600, 31509
                    }),
            new StabilisationCase(
                    10,
                    new int[] {4764, 9507, 12623, 13355, 14921, 17127, 18418, 20116, 20341, 25249},
                    new int[] {4764, 9507, 12623, 13355, 14921, 17127, 18418, 20116, 20341, 25249}),
            new StabilisationCase(
                    16,
                    new int[] {
                        3111, 5017, 5618, 10609, 10858, 12690, 17804, 22461, 23803, 28092, 30797, 32767, 32767, 32767,
                        32767, 32767
                    },
                    new int[] {
                        3111, 5017, 5618, 10609, 10858, 12690, 17804, 22461, 23803, 28092, 30797, 32394, 32402, 32411,
                        32418, 32421
                    }),
            new StabilisationCase(
                    10,
                    new int[] {683, 3412, 3682, 7878, 11089, 15641, 17590, 22638, 23675, 24572},
                    new int[] {683, 3412, 3682, 7878, 11089, 15641, 17590, 22638, 23675, 24572}),
            new StabilisationCase(
                    16,
                    new int[] {
                        1288, 4680, 8895, 10176, 14549, 15669, 17377, 21884, 24980, 26761, 28529, 32540, 32767, 32767,
                        32767, 32767
                    },
                    new int[] {
                        1288, 4680, 8895, 10176, 14549, 15669, 17377, 21884, 24980, 26761, 28529, 32394, 32402, 32411,
                        32418, 32421
                    }),
            new StabilisationCase(
                    10,
                    new int[] {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000},
                    new int[] {998, 1001, 1007, 1010, 1013, 1016, 1020, 1023, 1026, 1029}),
            new StabilisationCase(
                    10,
                    new int[] {30000, 27000, 24000, 21000, 18000, 15000, 12000, 9000, 6000, 3000},
                    new int[] {7469, 7472, 10423, 12619, 15144, 15147, 20440, 23064, 25498, 27748}),
            new StabilisationCase(
                    10,
                    new int[] {2000, 4000, 6000, 8000, 10000, 12000, 14000, 16000, 32700, 32767},
                    new int[] {2000, 4000, 6000, 8000, 10000, 12000, 14000, 16000, 32304, 32307}),
            new StabilisationCase(10, new int[] {0, 0, 5, 10, 4000, 8000, 12000, 16000, 20000, 24000}, new int[] {
                250, 253, 259, 262, 4000, 8000, 12000, 16000, 20000, 24000
            }),
            new StabilisationCase(16, new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, new int[] {
                100, 103, 143, 146, 149, 152, 157, 171, 185, 195, 206, 209, 217, 226, 233, 236
            }),
            new StabilisationCase(
                    16,
                    new int[] {
                        2000, 4000, 6000, 8000, 10000, 12000, 14000, 16000, 18000, 20000, 22000, 24000, 26000, 28000,
                        32767, 32767
                    },
                    new int[] {
                        2000, 4000, 6000, 8000, 10000, 12000, 14000, 16000, 18000, 20000, 22000, 24000, 26000, 28000,
                        32418, 32421
                    }),
            new StabilisationCase(
                    10,
                    new int[] {5000, 4000, 12000, 11990, 13000, 20000, 19000, 25000, 26000, 27000},
                    new int[] {4499, 4502, 11994, 11997, 13000, 19498, 19502, 25000, 26000, 27000}));

    private static final List<StabilityCase> STABILITY_CASES = List.of(
            new StabilityCase(10, new int[] {1000, -500, 300, -200, 100, -50, 25, -10, 5, -2}, Stability.STABLE),
            new StabilityCase(10, new int[] {2000, 1500, 600, 0, 0, 0, 0, 0, 0, 0}, Stability.UNSTABLE),
            new StabilityCase(
                    10, new int[] {4000, -3900, 3800, -3700, 3600, -3500, 3400, -3300, 3200, -3100}, Stability.STABLE),
            new StabilityCase(10, new int[] {4090, -3000, 2000, -1000, 500, -200, 100, -50, 20, -10}, Stability.STABLE),
            new StabilityCase(
                    16,
                    new int[] {3000, -2500, 2000, -1500, 1000, -500, 250, -125, 60, -30, 15, -7, 3, -1, 1, 0},
                    Stability.STABLE),
            new StabilityCase(10, new int[] {32767, 0, 0, 0, 0, 0, 0, 0, 0, 0}, Stability.UNSTABLE));

    static Stream<LpcCase> lpcCases() {
        return LPC_CASES.stream();
    }

    static Stream<StabilisationCase> stabilisationCases() {
        return STABILISATION_CASES.stream();
    }

    static Stream<StabilityCase> stabilityCases() {
        return STABILITY_CASES.stream();
    }

    @ParameterizedTest
    @MethodSource("lpcCases")
    void convertsFrequenciesToTheCoefficientsFfmpegGives(final LpcCase lpcCase) {
        final var lpc = converter.nlsfToLpc(Arrays.copyOf(lpcCase.nlsf(), MAX_ORDER), lpcCase.order());

        assertThat(lpc).hasSize(MAX_ORDER);
        for (var index = 0; index < lpcCase.order(); index++) {
            assertThat(Math.round(lpc[index] * Q12_ONE))
                    .as("coefficient %d", index)
                    .isEqualTo(lpcCase.expectedQ12()[index]);
        }
        for (var index = lpcCase.order(); index < MAX_ORDER; index++) {
            assertThat(lpc[index]).as("coefficient %d", index).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource("stabilisationCases")
    void stabilisesFrequenciesAsFfmpegDoes(final StabilisationCase stabilisation) {
        final var minimumSpacing = minimumSpacing(stabilisation.order());
        final var input = Arrays.copyOf(stabilisation.nlsf(), MAX_ORDER);

        final var stabilised = converter.stabilisedNlsf(input, stabilisation.order(), minimumSpacing);

        assertThat(Arrays.copyOf(stabilised, stabilisation.order())).isEqualTo(stabilisation.expected());
        assertThat(input).as("the input is left alone").isEqualTo(Arrays.copyOf(stabilisation.nlsf(), MAX_ORDER));
    }

    private int[] minimumSpacing(final int order) {
        if (order == 10) {
            return SilkTables.LSF_MIN_SPACING_NBMB;
        }
        return SilkTables.LSF_MIN_SPACING_WB;
    }

    @ParameterizedTest
    @MethodSource("stabilityCases")
    void judgesStabilityAsFfmpegDoes(final StabilityCase stability) {
        final var coefficients = Arrays.copyOf(stability.coefficientsQ12(), MAX_ORDER);

        assertThat(converter.isStable(coefficients, stability.order()))
                .isEqualTo(stability.stability() == Stability.STABLE);
    }
}
