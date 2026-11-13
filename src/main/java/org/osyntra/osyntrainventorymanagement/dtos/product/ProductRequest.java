package org.osyntra.osyntrainventorymanagement.dtos.product;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank @Size(max = 50) String sku, @Size(max = 255) String barcode,
        @NotBlank @Size(max = 120) String itemName, @NotBlank @Size(max = 255) String itemDescription,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
        @Positive Integer categoryId,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal cost,
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,19}") String unitOfMeasure,
        @Min(0) Integer minStockThreshold) {
    public ProductRequest(String sku, String barcode, String name, String description, BigDecimal price, Integer category) {
        this(sku, barcode, name, description, price, category, BigDecimal.ZERO, "EACH", null);
    }
}
