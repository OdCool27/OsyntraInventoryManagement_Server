package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.osyntra.osyntrainventorymanagement.tax.TaxCalculator;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class TaxCalculatorTests {
    @Test
    void halfUpRoundingUsesExtendedLineNotRoundedUnitTaxes() {
        assertEquals(new TaxCalculator.Amounts(10, 2, 12), TaxCalculator.calculate(1, 10, new BigDecimal("15"), false));
    }

    @Test
    void inclusiveHalfCentRoundsTaxAndKeepsTotalExact() {
        assertEquals(new TaxCalculator.Amounts(1, 2, 3), TaxCalculator.calculate(3, 1, new BigDecimal("100"), true));
    }

    @Test
    void fractionalRatesUseDecimalArithmetic() {
        assertEquals(new TaxCalculator.Amounts(10000, 125, 10125), TaxCalculator.calculate(10000, 1, new BigDecimal("1.25"), false));
    }

    @Test
    void zeroRateDoesNotAlterAmounts() {
        assertEquals(new TaxCalculator.Amounts(999, 0, 999), TaxCalculator.calculate(333, 3, BigDecimal.ZERO, true));
    }

    @Test
    void overflowCannotWrapIntoCheapCheckout() {
        assertThrows(ArithmeticException.class, () -> TaxCalculator.calculate(Long.MAX_VALUE, 2, new BigDecimal("15"), false));
        assertThrows(ArithmeticException.class, () -> TaxCalculator.calculate(Long.MAX_VALUE, 1, new BigDecimal("15"), false));
    }
}
