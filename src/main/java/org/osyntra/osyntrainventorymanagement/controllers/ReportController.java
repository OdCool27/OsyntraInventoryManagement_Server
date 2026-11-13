package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.constraints.*;
import org.osyntra.osyntrainventorymanagement.services.ReportService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/reports")
@Validated
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) Integer locationId) {
        return reports.summary(locationId);
    }

    @GetMapping("/low-stock")
    public List<Map<String, Object>> low(@RequestParam(required = false) Integer locationId,
                                         @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return reports.stock(locationId, true, false, limit, offset);
    }

    @GetMapping("/out-of-stock")
    public List<Map<String, Object>> out(@RequestParam(required = false) Integer locationId,
                                         @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return reports.stock(locationId, false, true, limit, offset);
    }

    @GetMapping("/sales")
    public Map<String, Object> sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) @Pattern(regexp = "POS|ONLINE") String channel) {
        return reports.sales(from, to, channel);
    }

    @GetMapping(value = "/stock.csv", produces = "text/csv")
    public ResponseEntity<String> csv(@RequestParam(required = false) Integer locationId) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=stock-report.csv").body(reports.export(locationId));
    }
}
