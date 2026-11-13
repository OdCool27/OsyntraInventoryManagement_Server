package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.osyntra.osyntrainventorymanagement.dtos.StockDtos.*;
import org.osyntra.osyntrainventorymanagement.dtos.inventory.ReorderRequest;
import org.osyntra.osyntrainventorymanagement.services.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/inventory")
@Validated
public class InventoryController {
    private final InventoryService stock;

    public InventoryController(InventoryService stock) {
        this.stock = stock;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Integer locationId, @RequestParam(required = false) String search,
                                          @RequestParam(required = false) String category, @RequestParam(defaultValue = "false") boolean lowStock, @RequestParam(defaultValue = "false") boolean outOfStock,
                                          @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return stock.list(locationId, search, category, lowStock, outOfStock, limit, offset);
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(required = false) Integer locationId) {
        return stock.list(locationId, null, null, true, false, 200, 0);
    }

    @GetMapping("/movements")
    public List<Map<String, Object>> movements(@RequestParam(required = false) Integer locationId, @RequestParam(required = false) UUID productId,
                                               @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return stock.movements(locationId, productId, limit, offset);
    }

    @GetMapping("/reasons")
    public List<Map<String, Object>> reasons() {
        return stock.reasons();
    }

    @PostMapping("/reasons")
    public void reason(@Valid @RequestBody Reason request) {
        stock.reason(request);
    }

    @GetMapping("/{productId}")
    public Map<String, Object> get(@PathVariable UUID productId, @RequestParam(required = false) Integer locationId) {
        return stock.get(productId, locationId);
    }

    @PostMapping("/{productId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> open(@PathVariable UUID productId, @RequestParam(required = false) Integer locationId, @Valid @RequestBody Opening request) {
        return stock.open(productId, locationId, request);
    }

    @PostMapping("/{productId}/movements")
    public Map<String, Object> move(@PathVariable UUID productId, @RequestParam(required = false) Integer locationId,
                                    @Valid @RequestBody Movement request) {
        return stock.move(productId, locationId, request);
    }

    @PutMapping("/{productId}/reorder-level")
    public Map<String, Object> threshold(@PathVariable UUID productId, @RequestParam(required = false) Integer locationId,
                                         @Valid @RequestBody ReorderRequest request) {
        return stock.threshold(productId, locationId, request.reorderLevel());
    }
}
