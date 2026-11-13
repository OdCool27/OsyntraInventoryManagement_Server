package org.osyntra.osyntrainventorymanagement.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

public final class CommerceDtos {
    private CommerceDtos() {
    }

    public record Line(@NotNull UUID productId, @Min(1) @Max(100000) int quantity) {
    }

    public record Checkout(@Positive Integer locationId, @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines,
                           @NotBlank @Size(max = 100) String requestId) {
    }

    public record Cart(@Positive Integer locationId, @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines) {
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
