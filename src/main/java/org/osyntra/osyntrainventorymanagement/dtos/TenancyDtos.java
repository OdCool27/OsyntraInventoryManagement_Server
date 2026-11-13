package org.osyntra.osyntrainventorymanagement.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.osyntra.osyntrainventorymanagement.dtos.user.CreateEmployeeRequest;
import org.osyntra.osyntrainventorymanagement.models.*;

public final class TenancyDtos {
    private TenancyDtos() {
    }

    public record Provision(@NotBlank @Size(max = 120) String name,
                            @NotBlank @Size(max = 80) String locationName,
                            @Pattern(regexp = "[A-Z]{3}") String currency,
                            @Min(0) Integer defaultThreshold, @NotNull @Valid CreateEmployeeRequest owner) {
    }

    public record Settings(@NotBlank @Size(max = 120) String name,
                           @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency, @Min(0) int defaultThreshold) {
    }

    public record Location(@NotBlank @Size(max = 80) String name) {
    }

    public record EmployeeUpdate(@NotBlank @Size(max = 30) String firstName,
                                 @NotBlank @Size(max = 30) String lastName, @NotBlank @Size(max = 100) String jobTitle,
                                 @NotNull EmployeeRole role, @NotNull UserStatus status) {
    }
}
