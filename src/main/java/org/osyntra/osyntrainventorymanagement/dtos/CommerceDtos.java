package org.osyntra.osyntrainventorymanagement.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

public final class CommerceDtos {
    private CommerceDtos() {
    }

    public record Line(@NotNull UUID productId, @Min(0) @Max(100000) Integer quantity,
                       @Pattern(regexp = "[A-Z][A-Z0-9_]{0,19}") String unitCode,
                       @DecimalMin("0.000001") @Digits(integer = 9, fraction = 6) java.math.BigDecimal unitQuantity) {
        public Line(UUID productId, int quantity) {
            this(productId, quantity, null, null);
        }
    }

    public record Checkout(@Positive Integer locationId, @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines,
                           @NotBlank @Size(max = 100) String requestId,
                           @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) java.math.BigDecimal discountPercent,
                           @Size(max = 500) String discountReason) {
        public Checkout(Integer locationId, List<Line> lines, String requestId) {
            this(locationId, lines, requestId, null, null);
        }
    }

    public record Cart(@Positive Integer locationId, @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines,
                       @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) java.math.BigDecimal discountPercent,
                       @Size(max = 500) String discountReason) {
        public Cart(Integer locationId, List<Line> lines) {
            this(locationId, lines, null, null);
        }
    }

    public record Payment(@NotBlank @Pattern(regexp = "CASH|CARD") String method, @NotNull Boolean successful,
                          @NotBlank @Size(max = 100) String requestId) {
    }

    public record Action(@NotBlank @Size(max = 500) String reason, @NotBlank @Size(max = 100) String requestId) {
    }

    public record Return(@NotNull Boolean resellable, @NotBlank @Size(max = 500) String reason,
                         @NotBlank @Size(max = 100) String requestId) {
    }
}
