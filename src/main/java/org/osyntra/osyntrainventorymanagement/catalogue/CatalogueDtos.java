package org.osyntra.osyntrainventorymanagement.catalogue;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.*;

public final class CatalogueDtos {
    private CatalogueDtos() {
    }

    public record Unit(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,19}") String code,
                       @NotNull @DecimalMin("0.000001") @DecimalMax("1000000") @Digits(integer = 7, fraction = 6) BigDecimal factor,
                       boolean purchaseAllowed, boolean saleAllowed, @NotBlank @Size(max = 100) String requestId) {
    }

    public record Convert(@NotBlank String unit,
                          @NotNull @DecimalMin("0.000001") @Digits(integer = 9, fraction = 6) BigDecimal quantity,
                          @NotBlank @Pattern(regexp = "PURCHASE|SALE") String purpose) {
    }

    public record ImportOptions(@NotNull @Size(max = 12) Map<@NotBlank String, @NotBlank String> mapping,
                                @NotBlank @Pattern(regexp = "REJECT|SKIP|UPDATE") String duplicatePolicy,
                                @NotBlank @Size(max = 100) String requestId) {
    }

    public record CsvImport(@NotBlank @Size(max = 1000000) String csv,
                            @NotNull @jakarta.validation.Valid ImportOptions options) {
    }

    public record Commit(@NotBlank @Size(max = 100) String requestId) {
    }
}
