package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.osyntra.osyntrainventorymanagement.dtos.CommerceDtos.*;
import org.osyntra.osyntrainventorymanagement.dtos.product.ProductResponse;
import org.osyntra.osyntrainventorymanagement.repositories.*;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/storefront/{business}")
@Validated
public class StorefrontController {
    private final ProductRepository products;
    private final CommerceService commerce;
    private final TenancyService tenancy;
    private final MvpStore db;

    public StorefrontController(ProductRepository products, CommerceService commerce, TenancyService tenancy, MvpStore db) {
        this.products = products;
        this.commerce = commerce;
        this.tenancy = tenancy;
        this.db = db;
    }

    @PostMapping("/cart")
    public Map<String, Object> cart(@PathVariable int business, @Valid @RequestBody Cart request) {
        return commerce.publicCart(business, request);
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products(@PathVariable int business,
                                              @RequestParam(required = false) Integer locationId, @RequestParam(required = false) String search, @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String barcode, @RequestParam(required = false) String make, @RequestParam(required = false) String model,
                                              @RequestParam(required = false) @Min(1886) @Max(2200) Integer year, @RequestParam(required = false) String engine, @RequestParam(required = false) String trim,
                                              @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        db.one("SELECT id FROM businesses WHERE id=? AND active=1", business);
        int location = tenancy.locationId(business, locationId);
        return products.search(business, search, category, barcode, make, model, year, engine, trim, false, limit, offset).stream().map(p -> {
            Map<String, Object> publicProduct = new LinkedHashMap<>();
            publicProduct.put("id", p.itemId());
            publicProduct.put("sku", p.sku());
            publicProduct.put("barcode", p.barcode());
            publicProduct.put("name", p.itemName());
            publicProduct.put("description", p.itemDescription());
            publicProduct.put("price", p.unitPrice());
            publicProduct.put("unitOfMeasure", p.unitOfMeasure());
            publicProduct.put("category", p.category());
            publicProduct.put("available", db.number("SELECT coalesce(sum(quantity_on_hand-quantity_reserved),0) FROM inventory WHERE business_id=? AND location_id=? AND product_id=?", business, location, p.itemId().toString()));
            return publicProduct;
        }).toList();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> checkout(@PathVariable int business, @RequestHeader(value = "X-Order-Token", required = false) String token, @Valid @RequestBody Checkout request) {
        return publicView(commerce.publicCheckout(business, request, token));
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> get(@PathVariable int business, @PathVariable UUID id, @RequestHeader(value = "X-Order-Token", required = false) String token) {
        return publicView(commerce.publicGet(business, id.toString(), token));
    }

    @PostMapping("/orders/{id}/payment")
    public Map<String, Object> pay(@PathVariable int business, @PathVariable UUID id, @RequestHeader(value = "X-Order-Token", required = false) String token, @Valid @RequestBody Payment request) {
        return publicView(commerce.publicPay(business, id.toString(), token, request));
    }

    @PostMapping("/orders/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable int business, @PathVariable UUID id, @RequestHeader(value = "X-Order-Token", required = false) String token, @Valid @RequestBody Action request) {
        return publicView(commerce.publicCancel(business, id.toString(), token, request));
    }

    @GetMapping(value = "/orders/{id}/receipt", produces = "text/plain")
    public ResponseEntity<String> receipt(@PathVariable int business, @PathVariable UUID id, @RequestHeader(value = "X-Order-Token", required = false) String token) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=simulated-receipt-" + id + ".txt")
                .body(commerce.receipt(commerce.publicGet(business, id.toString(), token)));
    }

    private Map<String, Object> publicView(Map<String, Object> result) {
        result.remove("created_by");
        result.remove("events");
        Object lines = result.get("lines");
        if (lines instanceof List<?> list)
            for (Object line : list) if (line instanceof Map<?, ?> map) map.remove("cost_cents");
        return result;
    }
}
