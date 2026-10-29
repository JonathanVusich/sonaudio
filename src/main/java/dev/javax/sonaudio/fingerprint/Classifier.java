package dev.javax.sonaudio.fingerprint;

/**
 * One of the filters Chromaprint runs over an {@link IntegralImage}, and the thresholds that quantise its response to
 * two bits of a sub-fingerprint.
 *
 * @param firstBand the lowest band the filter covers
 * @param bands how many bands the filter covers
 * @param rows how many rows the filter covers, from the row it's applied at
 * @param lowThreshold the response below which the filter gives 0
 * @param middleThreshold the response from which the filter gives 2
 * @param highThreshold the response from which the filter gives 3
 */
public record Classifier(
        FilterShape shape,
        int firstBand,
        int bands,
        int rows,
        double lowThreshold,
        double middleThreshold,
        double highThreshold) {

    /**
     * @return 0 to 3, as the filter's response at that row rises
     */
    public int classify(final IntegralImage image, final int row) {
        final var response = shape.response(image, row, firstBand, rows, bands);
        if (response < lowThreshold) {
            return 0;
        }
        if (response < middleThreshold) {
            return 1;
        }
        if (response < highThreshold) {
            return 2;
        }
        return 3;
    }
}
