package dev.javax.sonaudio.fingerprint;

/**
 * How a {@link Classifier}'s filter splits its rectangle of an {@link IntegralImage} into two parts to compare: rows
 * run forward in time, and bands up through the notes of the octave.
 */
public enum FilterShape {
    /** The whole rectangle, against nothing. */
    WHOLE,
    /** Its upper half of bands against its lower half. */
    BAND_HALVES,
    /** Its later half of rows against its earlier half. */
    ROW_HALVES,
    /** Two opposite quarters against the other two. */
    QUARTERS,
    /** Its middle third of bands against the outer two. */
    BAND_THIRDS,
    /** Its middle third of rows against the outer two. */
    ROW_THIRDS;

    /**
     * @return how much the first part outweighs the second, on a log scale
     */
    public double response(final IntegralImage image, final int row, final int band, final int rows, final int bands) {
        final var halfRows = rows / 2;
        final var halfBands = bands / 2;
        return switch (this) {
            case WHOLE -> contrast(image.area(row, band, rows, bands), 0);
            case BAND_HALVES ->
                contrast(
                        image.area(row, band + halfBands, rows, bands - halfBands),
                        image.area(row, band, rows, halfBands));
            case ROW_HALVES ->
                contrast(
                        image.area(row + halfRows, band, rows - halfRows, bands),
                        image.area(row, band, halfRows, bands));
            case QUARTERS ->
                contrast(
                        image.area(row, band + halfBands, halfRows, bands - halfBands)
                                + image.area(row + halfRows, band, rows - halfRows, halfBands),
                        image.area(row, band, halfRows, halfBands)
                                + image.area(row + halfRows, band + halfBands, rows - halfRows, bands - halfBands));
            case BAND_THIRDS -> bandThirdsContrast(image, row, band, rows, bands);
            case ROW_THIRDS -> rowThirdsContrast(image, row, band, rows, bands);
        };
    }

    private static double bandThirdsContrast(
            final IntegralImage image, final int row, final int band, final int rows, final int bands) {
        final var third = bands / 3;
        final var twoThirds = 2 * bands / 3;
        return contrast(
                image.area(row, band + third, rows, twoThirds - third),
                image.area(row, band, rows, third) + image.area(row, band + twoThirds, rows, bands - twoThirds));
    }

    private static double rowThirdsContrast(
            final IntegralImage image, final int row, final int band, final int rows, final int bands) {
        final var third = rows / 3;
        final var twoThirds = 2 * rows / 3;
        return contrast(
                image.area(row + third, band, twoThirds - third, bands),
                image.area(row, band, third, bands) + image.area(row + twoThirds, band, rows - twoThirds, bands));
    }

    private static double contrast(final double first, final double second) {
        return Math.log(1 + first) - Math.log(1 + second);
    }
}
