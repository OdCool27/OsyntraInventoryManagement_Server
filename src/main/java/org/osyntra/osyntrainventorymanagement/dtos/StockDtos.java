package org.osyntra.osyntrainventorymanagement.dtos;

import jakarta.validation.constraints.*;

public final class StockDtos {
    private StockDtos() {
    }

    public record Opening(@NotNull @Min(0) Integer quantityOnHand, @Min(0) Integer reorderLevel,
                          @NotBlank @Size(max = 80) String reason, @Size(max = 500) String notes,
                          @NotBlank @Size(max = 100) String requestId) {
    }

    public record Movement(@NotBlank @Pattern(regexp = "RECEIPT|DAMAGE|CORRECTION") String type,
                           @NotNull Integer quantity, @NotBlank @Size(max = 80) String reason,
                           @Size(max = 500) String notes,
                           @NotBlank @Size(max = 100) String requestId) {
    }

    public record CountStart(@Positive Integer locationId) {
    }

    public record CountLine(@NotBlank @Size(max = 255) String code, @NotNull @Min(0) Integer quantity) {
    }

    public record CsvPreview(@Positive Integer locationId, @NotBlank @Size(max = 1000000) String csv) {
    }

    public record Reason(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String code,
                         @NotBlank @Size(max = 120) String label) {
    }
}
