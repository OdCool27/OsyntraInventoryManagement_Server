package org.osyntra.osyntrainventorymanagement.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class InventoryDtos {
    public record Supplier(@NotBlank @Size(max = 120) String name, @Email @Size(max = 120) String email,
                           @NotBlank @Size(max = 100) String requestId) {
    }

    public record PurchaseLine(@NotNull UUID productId, @NotBlank @Size(max = 20) String unit,
                               @NotNull @DecimalMin("0.000001") @Digits(integer = 9, fraction = 6) BigDecimal quantity,
                               @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 6) BigDecimal unitCost) {
    }

    public record Purchase(@NotNull UUID supplierId, @Positive int locationId,
                           @NotEmpty @Size(max = 100) List<@NotNull @Valid PurchaseLine> lines,
                           @NotBlank @Size(max = 100) String requestId) {
    }

    public record ReceiveLine(@NotNull UUID productId,
                              @NotNull @DecimalMin("0.000001") @Digits(integer = 9, fraction = 6) BigDecimal quantity,
                              @Size(max = 80) String batchCode, LocalDate expiresOn) {
    }

    public record Receive(@NotEmpty @Size(max = 100) List<@NotNull @Valid ReceiveLine> lines,
                          @NotBlank @Size(max = 100) String requestId) {
    }

    public record Adjustment(@Positive int locationId, @NotNull UUID productId, @NotBlank @Size(max = 20) String unit,
                             @NotNull @DecimalMin("0.000001") @Digits(integer = 9, fraction = 6) BigDecimal quantity,
                             @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 6) BigDecimal unitCost,
                             @NotBlank @Pattern(regexp = "RECEIPT|DAMAGE|CORRECTION_IN|CORRECTION_OUT") String type,
                             @NotBlank @Size(max = 500) String reason, @Size(max = 80) String batchCode,
                             LocalDate expiresOn,
                             @NotBlank @Size(max = 100) String requestId) {
    }

    public record BatchCount(@Min(0) int expectedQuantity, @Min(0) int countedQuantity,
                             @NotBlank @Size(max = 500) String reason, @NotBlank @Size(max = 100) String requestId) {
    }

    public record Tracking(boolean enabled, @NotBlank @Size(max = 100) String requestId) {
    }

    public record TransferLine(@NotNull UUID productId, @Min(1) int quantity, @Size(max = 80) String batchCode) {
    }

    public record Transfer(@Positive int sourceId, @Positive int destinationId,
                           @NotEmpty @Size(max = 100) List<@NotNull @Valid TransferLine> lines,
                           @NotBlank @Size(max = 100) String requestId) {
    }

    public record TransferReceipt(@NotNull @Size(max = 100) Map<@NotNull UUID, @NotNull @Min(1) Integer> quantities,
                                  @NotBlank @Size(max = 100) String requestId) {
    }

    public record Command(@NotBlank @Size(max = 100) String requestId) {
    }
}
