package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.osyntra.osyntrainventorymanagement.tax.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;

import java.util.*;

@RestController
@Validated
@RequestMapping("/api/v1")
public class SettingsTaxController {
    private final SettingsService settings;
    private final TaxService tax;
    private final OnboardingService onboarding;

    public SettingsTaxController(SettingsService settings, TaxService tax, OnboardingService onboarding) {
        this.settings = settings;
        this.tax = tax;
        this.onboarding = onboarding;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return settings.get();
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(@Valid @RequestBody TaxDtos.Preferences r) {
        return settings.update(r);
    }

    @GetMapping("/branches/{id}/settings")
    public Map<String, Object> branch(@PathVariable @Positive int id) {
        return settings.branch(id);
    }

    @PutMapping("/branches/{id}/settings")
    public Map<String, Object> branch(@PathVariable @Positive int id, @Valid @RequestBody TaxDtos.BranchPreferences r) {
        return settings.branch(id, r);
    }

    @GetMapping("/tax/templates")
    public List<Map<String, Object>> templates() {
        return tax.templates();
    }

    @GetMapping("/tax/policies")
    public List<Map<String, Object>> policies(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return tax.policies(limit, offset);
    }

    @PostMapping("/tax/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> policy(@Valid @RequestBody TaxDtos.Policy r) {
        return tax.policy(r);
    }

    @GetMapping("/tax/profiles")
    public List<Map<String, Object>> rates(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return tax.rates(limit, offset);
    }

    @PostMapping("/tax/profiles/{code}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> rate(
            @PathVariable @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String code, @Valid @RequestBody TaxDtos.Rate r) {
        return tax.rate(code, r);
    }

    @GetMapping("/products/{id}/tax")
    public List<Map<String, Object>> assignments(@PathVariable UUID id, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return tax.assignments(id.toString(), limit, offset);
    }

    @PostMapping("/products/{id}/tax")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assign(@PathVariable UUID id, @Valid @RequestBody TaxDtos.Assignment r) {
        return tax.assign(id.toString(), r);
    }

    @GetMapping("/onboarding")
    public List<Map<String, Object>> onboarding() {
        return onboarding.get();
    }

    @PutMapping("/onboarding/{step}")
    public Map<String, Object> step(@PathVariable String step, @Valid @RequestBody TaxDtos.Onboarding r) {
        return onboarding.update(step, r);
    }
}
