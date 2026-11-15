package org.osyntra.osyntrainventorymanagement.tax;

import java.math.*;

/**
 * Round tax once per extended line to currency cents, HALF_UP; net + tax always equals gross.
 */
public final class TaxCalculator {
    private TaxCalculator() {
    }

    public record Amounts(long net, long tax, long total) {
    }

    public static Amounts calculate(long unitCents, int quantity, BigDecimal percent, boolean inclusive) {
        if (unitCents < 0 || quantity <= 0 || percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Invalid tax calculation inputs");
        BigDecimal extended = BigDecimal.valueOf(unitCents).multiply(BigDecimal.valueOf(quantity));
        BigDecimal tax = extended.multiply(percent).divide(inclusive ? new BigDecimal("100").add(percent) : new BigDecimal("100"), 0, RoundingMode.HALF_UP);
        long base = extended.longValueExact(), taxCents = tax.longValueExact();
        return inclusive ? new Amounts(Math.subtractExact(base, taxCents), taxCents, base) : new Amounts(base, taxCents, Math.addExact(base, taxCents));
    }
}
