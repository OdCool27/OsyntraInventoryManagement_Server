package org.osyntra.osyntrainventorymanagement.dtos;

import jakarta.validation.constraints.*;

public record FitmentRequest(@NotBlank @Size(max = 60) String make, @NotBlank @Size(max = 60) String model,
                             @Min(1886) @Max(2200) int yearFrom, @Min(1886) @Max(2200) int yearTo,
                             @Size(max = 80) String engine, @Size(max = 80) String trim,
                             @NotBlank @Size(max = 500) String source,
                             @AssertTrue(message = "Fitment must be verified against the supplied source") boolean verified) {
    @AssertTrue(message = "yearTo must be at least yearFrom")
    public boolean isYearRangeValid() {
        return yearTo >= yearFrom;
    }
}
