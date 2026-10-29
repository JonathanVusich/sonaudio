package dev.javax.sonaudio.fingerprint;

/**
 * Sums a {@link ChromaImage} up to each row and band, so that the sum of any rectangle of it takes four lookups.
 */
public final class IntegralImage {

    // sums[row][band] is the sum of every value up to that row and band, both included.
    private final double[][] sums;

    public IntegralImage(final ChromaImage image) {
        this.sums = new double[image.rowCount()][ChromaImage.BANDS];
        for (var row = 0; row < sums.length; row++) {
            var rowSum = 0.0;
            for (var band = 0; band < ChromaImage.BANDS; band++) {
                rowSum += image.value(row, band);
                sums[row][band] = rowSum + sumAbove(row, band);
            }
        }
    }

    public int rowCount() {
        return sums.length;
    }

    /**
     * @return the sum of the values in {@code rows} rows from {@code firstRow} and {@code bands} bands from
     * {@code firstBand}, or 0 when either is 0
     */
    public double area(final int firstRow, final int firstBand, final int rows, final int bands) {
        if (rows == 0 || bands == 0) {
            return 0;
        }
        final var lastRow = firstRow + rows - 1;
        final var lastBand = firstBand + bands - 1;
        return sums[lastRow][lastBand]
                - sumAbove(firstRow, lastBand)
                - sumBefore(lastRow, firstBand)
                + sumBefore(firstRow - 1, firstBand);
    }

    // The sum of every value in the rows before this one, up to this band.
    private double sumAbove(final int row, final int band) {
        if (row == 0) {
            return 0;
        }
        return sums[row - 1][band];
    }

    // The sum of every value up to this row, in the bands before this one.
    private double sumBefore(final int row, final int band) {
        if (row < 0 || band == 0) {
            return 0;
        }
        return sums[row][band - 1];
    }
}
