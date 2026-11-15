package org.osyntra.osyntrainventorymanagement.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class CheckoutDtos {
    public record Price(@Positive int locationId, @NotNull UUID productId,
                        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal price,
                        @NotNull Instant effectiveFrom, @NotBlank @Size(max = 100) String requestId) {
    }

    public record Promotion(@Positive Integer locationId, UUID productId, @NotBlank @Size(max = 120) String name,
                            @NotNull @DecimalMin("0.01") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal percent,
                            @NotNull Instant startsAt, @NotNull Instant endsAt,
                            @NotBlank @Size(max = 100) String requestId) {
    }

    public record Pay(@NotBlank @Pattern(regexp = "CASH|MANUAL_TERMINAL|SIMULATOR") String provider,
                      @Pattern(regexp = "CONFIRMED|DECLINED|UNKNOWN") String simulatorOutcome,
                      @NotBlank @Size(max = 100) String requestId) {
    }

    public record Reconcile(@NotBlank @Pattern(regexp = "CONFIRMED|DECLINED|VOIDED") String outcome,
                            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:/-]{1,100}") String externalReference,
                            @NotBlank @Size(max = 100) String eventId, @NotBlank @Size(max = 100) String requestId) {
    }

    public record ReverseLine(@NotNull UUID productId, @Min(1) int quantity) {
    }

    public record Reverse(@NotBlank @Pattern(regexp = "RETURN|REFUND|CANCEL") String type, boolean resellable,
                          @NotBlank @Size(max = 500) String reason,
                          @NotEmpty @Size(max = 100) List<@NotNull @Valid ReverseLine> lines,
                          @NotBlank @Size(max = 100) String requestId) {
    }

    public record Decision(boolean approve, @NotBlank @Size(max = 100) String requestId) {
    }

    public record Complete(boolean confirmed,
                           @NotBlank @Pattern(regexp = "CASH|EXTERNAL_MANUAL|SIMULATED") String method,
                           @Pattern(regexp = "[A-Za-z0-9._:/-]{1,100}") String externalReference,
                           @NotBlank @Size(max = 100) String requestId) {
    }

    public record ReceiptEmail(@NotBlank @Email @Size(max = 120) String email,
                               @NotBlank @Size(max = 100) String requestId) {
    }
}
