package org.osyntra.osyntrainventorymanagement.identity;

import jakarta.validation.constraints.*;

import java.util.*;

public final class IdentityDtos {
    private IdentityDtos() {
    }

    public record Register(@NotBlank @Size(max = 30) String firstName, @NotBlank @Size(max = 30) String lastName,
                           @NotBlank @Email @Size(max = 50) String email,
                           @NotBlank @Size(min = 12, max = 72) String password) {
    }

    public record Login(@NotBlank @Email @Size(max = 50) String email, @NotBlank @Size(max = 72) String password) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 50) String email) {
    }

    public record Token(@NotBlank @Size(max = 100) String token) {
    }

    public record Reset(@NotBlank @Size(max = 100) String token, @NotBlank @Size(min = 12, max = 72) String password) {
    }

    public record SelectBusiness(@Positive int membershipId) {
    }

    public record MfaCode(@Pattern(regexp = "[0-9]{6}") String code, @Size(max = 100) String recoveryCode) {
    }

    public record MfaSetup(@Size(max = 72) String password) {
    }

    public record Recovery(@NotBlank @Size(max = 72) String password, @NotBlank @Size(max = 100) String recoveryCode) {
    }

    public record DisableMfa(@NotBlank @Size(max = 72) String password,
                             @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
    }

    public record BusinessCreate(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 80) String locationName,
                                 @Pattern(regexp = "[A-Z]{3}") String currency, @Min(0) Integer defaultThreshold) {
    }

    public record Invite(@NotBlank @Email @Size(max = 50) String email,
                         @NotBlank @Pattern(regexp = "OWNER|ADMINISTRATOR|MANAGER|CASHIER|INVENTORY_CLERK") String role,
                         boolean allBranches, @NotNull @Size(max = 100) List<@NotNull @Positive Integer> branchIds) {
    }

    public record Accept(@NotBlank @Size(max = 100) String token, @Size(max = 30) String firstName,
                         @Size(max = 30) String lastName,
                         @Size(min = 12, max = 72) String password) {
    }

    public record MembershipUpdate(
            @NotBlank @Pattern(regexp = "OWNER|ADMINISTRATOR|MANAGER|CASHIER|INVENTORY_CLERK") String role,
            @NotBlank @Pattern(regexp = "ACTIVE|DEACTIVATED") String status, boolean allBranches,
            @NotNull @Size(max = 100) List<@NotNull @Positive Integer> branchIds,
            @NotBlank @Size(max = 100) String jobTitle) {
    }

    public record BranchUpdate(@NotBlank @Size(max = 80) String name, @NotNull Boolean active) {
    }
}
