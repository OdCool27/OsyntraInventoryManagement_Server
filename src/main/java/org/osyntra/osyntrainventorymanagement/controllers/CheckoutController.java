package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.*;
import org.osyntra.osyntrainventorymanagement.payments.*;

import java.util.*;

@RestController
@Validated
@RequestMapping("/api/v1/checkout")
public class CheckoutController {
    private final PaymentService payments;
    private final ReversalService reversals;
    private final ReceiptService receipts;
    private final PricingService pricing;

    public CheckoutController(PaymentService payments, ReversalService reversals, ReceiptService receipts, PricingService pricing) {
        this.payments = payments;
        this.reversals = reversals;
        this.receipts = receipts;
        this.pricing = pricing;
    }

    @GetMapping("/catalogue")
    public List<Map<String, Object>> catalogue(@RequestParam @Positive int locationId, @RequestParam(required = false) @Size(max = 120) String search, @RequestParam(required = false) @Size(max = 255) String code, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return pricing.catalogue(locationId, search, code, limit, offset);
    }

    @PostMapping("/prices")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> price(@Valid @RequestBody CheckoutDtos.Price r) {
        return pricing.price(r);
    }

    @GetMapping("/prices")
    public List<Map<String, Object>> prices(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return pricing.prices(limit, offset);
    }

    @PostMapping("/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> promotion(@Valid @RequestBody CheckoutDtos.Promotion r) {
        return pricing.promotion(r);
    }

    @PostMapping("/promotions/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable UUID id, @Valid @RequestBody org.osyntra.osyntrainventorymanagement.dtos.CommerceDtos.Action r) {
        return pricing.revoke(id.toString(), r);
    }

    @GetMapping("/promotions")
    public List<Map<String, Object>> promotions(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return pricing.promotions(limit, offset);
    }

    @GetMapping("/orders/{id}/payments")
    public List<Map<String, Object>> payments(@PathVariable UUID id) {
        return payments.list(id.toString());
    }

    @PostMapping("/orders/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> payment(@PathVariable UUID id, @Valid @RequestBody CheckoutDtos.Pay r) {
        return payments.start(id.toString(), r);
    }

    @PostMapping("/payments/{id}/reconcile")
    public Map<String, Object> reconcile(@PathVariable UUID id, @Valid @RequestBody CheckoutDtos.Reconcile r) {
        return payments.reconcile(id.toString(), r);
    }

    @GetMapping("/orders/{id}/reversals")
    public List<Map<String, Object>> reversals(@PathVariable UUID id) {
        return reversals.list(id.toString());
    }

    @PostMapping("/orders/{id}/reversals")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> reversal(@PathVariable UUID id, @Valid @RequestBody CheckoutDtos.Reverse r) {
        return reversals.request(id.toString(), r);
    }

    @GetMapping("/reversals/{id}")
    public Map<String, Object> reversal(@PathVariable UUID id) {
        return reversals.get(id.toString());
    }

    @PostMapping("/reversals/{id}/review")
    public Map<String, Object> review(@PathVariable UUID id, @Valid @RequestBody CheckoutDtos.Decision r) {
        return reversals.decide(id.toString(), r);
    }

    @PostMapping("/reversals/{id}/complete")
    public Map<String, Object> complete(@PathVariable UUID id, @Valid @RequestBody CheckoutDtos.Complete r) {
        return reversals.complete(id.toString(), r);
    }

    @GetMapping("/orders/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable UUID id, @RequestParam(defaultValue = "text") @Pattern(regexp = "text|html|pdf") String format) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(switch (format) {
                    case "html" -> "text/html;charset=UTF-8";
                    case "pdf" -> "application/pdf";
                    default -> "text/plain;charset=UTF-8";
                }))
                .header("Content-Disposition", "attachment; filename=receipt-" + id + "." + (format.equals("text") ? "txt" : format))
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'").body(receipts.render(id.toString(), format));
    }

    @PostMapping("/orders/{id}/receipt-email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> email(@PathVariable UUID id, @Valid @RequestBody CheckoutDtos.ReceiptEmail r) {
        receipts.emailLimit();
        return receipts.email(id.toString(), r);
    }
}
