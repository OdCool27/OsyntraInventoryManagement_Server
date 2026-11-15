package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.osyntra.osyntrainventorymanagement.inventory.*;

import java.util.*;

@RestController
@Validated
@RequestMapping("/api/v1")
public class PurchasingController {
    private final PurchasingService purchasing;
    private final TransferService transfers;

    public PurchasingController(PurchasingService purchasing, TransferService transfers) {
        this.purchasing = purchasing;
        this.transfers = transfers;
    }

    @GetMapping("/purchasing/suppliers")
    public List<Map<String, Object>> suppliers(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return purchasing.suppliers(limit, offset);
    }

    @PostMapping("/purchasing/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> supplier(@Valid @RequestBody InventoryDtos.Supplier r) {
        return purchasing.supplier(r);
    }

    @GetMapping("/purchasing/orders")
    public List<Map<String, Object>> orders(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return purchasing.orders(limit, offset);
    }

    @PostMapping("/purchasing/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> order(@Valid @RequestBody InventoryDtos.Purchase r) {
        return purchasing.order(r);
    }

    @GetMapping("/purchasing/orders/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return purchasing.get(id.toString());
    }

    @PostMapping("/purchasing/orders/{id}/receive")
    public Map<String, Object> receive(@PathVariable UUID id, @Valid @RequestBody InventoryDtos.Receive r) {
        return purchasing.receive(id.toString(), r);
    }

    @PostMapping("/purchasing/orders/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id, @Valid @RequestBody InventoryDtos.Command r) {
        return purchasing.cancel(id.toString(), r);
    }

    @PostMapping("/inventory/adjustments")
    public Map<String, Object> adjust(@Valid @RequestBody InventoryDtos.Adjustment r) {
        return purchasing.adjust(r);
    }

    @PostMapping("/inventory/products/{id}/tracking")
    public Map<String, Object> tracking(@PathVariable UUID id, @Valid @RequestBody InventoryDtos.Tracking r) {
        return purchasing.tracking(id.toString(), r);
    }

    @PostMapping("/inventory/batches/{id}/count")
    public Map<String, Object> batchCount(@PathVariable UUID id, @Valid @RequestBody InventoryDtos.BatchCount r) {
        return purchasing.countBatch(id.toString(), r);
    }

    @GetMapping("/inventory/batches")
    public List<Map<String, Object>> batches(@RequestParam @Positive int locationId, @RequestParam(defaultValue = "false") boolean expiring, @RequestParam(defaultValue = "30") @Min(0) @Max(365) int days, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return purchasing.batches(locationId, expiring, days, limit, offset);
    }

    @GetMapping("/inventory/transfers")
    public List<Map<String, Object>> transfers(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return transfers.list(limit, offset);
    }

    @PostMapping("/inventory/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> transfer(@Valid @RequestBody InventoryDtos.Transfer r) {
        return transfers.request(r);
    }

    @GetMapping("/inventory/transfers/{id}")
    public Map<String, Object> transfer(@PathVariable UUID id) {
        return transfers.get(id.toString());
    }

    @PostMapping("/inventory/transfers/{id}/dispatch")
    public Map<String, Object> dispatch(@PathVariable UUID id, @Valid @RequestBody InventoryDtos.Command r) {
        return transfers.dispatch(id.toString(), r);
    }

    @PostMapping("/inventory/transfers/{id}/receive")
    public Map<String, Object> transferReceive(@PathVariable UUID id, @Valid @RequestBody InventoryDtos.TransferReceipt r) {
        return transfers.receive(id.toString(), r);
    }

    @PostMapping("/inventory/transfers/{id}/cancel")
    public Map<String, Object> transferCancel(@PathVariable UUID id, @Valid @RequestBody InventoryDtos.Command r) {
        return transfers.cancel(id.toString(), r);
    }
}
