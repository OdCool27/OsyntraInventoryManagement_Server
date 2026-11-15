package org.osyntra.osyntrainventorymanagement.dtos.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(UUID itemId, String sku, String barcode, String itemName, String itemDescription,
                              BigDecimal unitPrice, Integer categoryId, String category, boolean active,
                              Instant addedAt, Instant modifiedAt,
                              BigDecimal cost, String unitOfMeasure, Integer minStockThreshold, String productKind,
                              UUID parentId, String variantKey) {
}
