package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.osyntra.osyntrainventorymanagement.dtos.CommerceDtos.*;
import org.osyntra.osyntrainventorymanagement.services.CommerceService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

import java.util.*;

@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {
    private final CommerceService commerce;

    public OrderController(CommerceService commerce) {
        this.commerce = commerce;
    }

    @PostMapping("/cart")
    public Map<String, Object> cart(@Valid @RequestBody Cart request) {
        return commerce.cart(request);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return commerce.history(limit, offset);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return commerce.get(id.toString());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody Checkout request) {
        return commerce.checkout(request);
    }

    @PostMapping("/{id}/payment")
    public Map<String, Object> pay(@PathVariable UUID id, @Valid @RequestBody Payment request) {
        return commerce.pay(id.toString(), request);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id, @Valid @RequestBody Action request) {
        return commerce.cancel(id.toString(), request);
    }

    @PostMapping("/{id}/fulfill")
    public Map<String, Object> fulfill(@PathVariable UUID id, @Valid @RequestBody Action request) {
        return commerce.fulfill(id.toString(), request);
    }

    @PostMapping("/{id}/return")
    public Map<String, Object> returned(@PathVariable UUID id, @Valid @RequestBody Return request) {
        return commerce.returnOrder(id.toString(), request);
    }

    @PostMapping("/{id}/refund")
    public Map<String, Object> refund(@PathVariable UUID id, @Valid @RequestBody Action request) {
        return commerce.refund(id.toString(), request);
    }

    @GetMapping(value = "/{id}/receipt", produces = "text/plain")
    public ResponseEntity<String> receipt(@PathVariable UUID id) {
        var order = commerce.get(id.toString());
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=simulated-receipt-" + id + ".txt")
                .body(commerce.receipt(order));
    }
}
