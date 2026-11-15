package org.osyntra.osyntrainventorymanagement.tax;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public final class TaxDtos {
    private TaxDtos() {
    }

    public record Preferences(@Min(0) long revision, @NotNull @Size(max = 120) String receiptName,
                              @NotNull @Size(max = 500) String receiptFooter, boolean lowStockNotifications,
                              boolean allowBranchPriceMode) {
    }

    public record BranchPreferences(@Min(0) long revision, @Size(max = 120) String receiptName,
                                    @Size(max = 500) String receiptFooter, Boolean lowStockNotifications,
                                    @Pattern(regexp = "INCLUSIVE|EXCLUSIVE") String priceMode) {
    }

    public record Policy(@NotNull Instant effectiveFrom,
                         @NotBlank @Pattern(regexp = "NOT_REGISTERED|REGISTERED") String registrationStatus,
                         @NotNull @Size(max = 80) String registrationNumber,
                         @NotBlank @Pattern(regexp = "INCLUSIVE|EXCLUSIVE") String priceMode, boolean verified,
                         @NotNull @Size(max = 500) String verificationNote,
                         @NotBlank @Size(max = 100) String requestId) {
    }

    public record Rate(@NotNull Instant effectiveFrom, @NotBlank @Size(max = 120) String label,
                       @NotBlank @Pattern(regexp = "TAXABLE|ZERO_RATED|EXEMPT") String classification,
                       @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal ratePercent,
                       boolean illustrative, boolean verified, @NotNull @Size(max = 500) String verificationNote,
                       @NotBlank @Size(max = 100) String requestId) {
    }

    public record Assignment(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String code,
                             @NotNull Instant effectiveFrom, @NotBlank @Size(max = 100) String requestId) {
    }

    public record Onboarding(@Min(0) long revision,
                             @NotBlank @Pattern(regexp = "NOT_STARTED|IN_PROGRESS|COMPLETE|SKIPPED") String status,
                             @NotNull @Size(max = 500) String notes) {
    }
}
