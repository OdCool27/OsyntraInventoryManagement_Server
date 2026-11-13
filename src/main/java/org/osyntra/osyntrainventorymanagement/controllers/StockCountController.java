package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import org.osyntra.osyntrainventorymanagement.dtos.StockDtos.*;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

import java.util.*;

@RestController
public class StockCountController {
    private final StockCountService counts;
    private final OpeningImportService imports;

    public StockCountController(StockCountService counts, OpeningImportService imports) {
        this.counts = counts;
        this.imports = imports;
    }

    @GetMapping("/api/counts")
    public List<Map<String, Object>> list() {
        return counts.list();
    }

    @PostMapping("/api/counts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> start(@Valid @RequestBody CountStart request) {
        return counts.start(request);
    }

    @GetMapping("/api/counts/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return counts.get(id.toString());
    }

    @PutMapping("/api/counts/{id}/lines")
    public Map<String, Object> line(@PathVariable UUID id, @Valid @RequestBody CountLine request) {
        return counts.line(id.toString(), request);
    }

    @PostMapping("/api/counts/{id}/submit")
    public Map<String, Object> submit(@PathVariable UUID id) {
        return counts.submit(id.toString());
    }

    @PostMapping("/api/counts/{id}/reopen")
    public Map<String, Object> reopen(@PathVariable UUID id) {
        return counts.reopen(id.toString());
    }

    @PostMapping("/api/counts/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id) {
        return counts.approve(id.toString());
    }

    @GetMapping(value = "/api/imports/opening/template", produces = "text/csv")
    public ResponseEntity<String> template() {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=opening-stock.csv").body("sku,quantity,reorderLevel\nSAMPLE-SKU,10,3\n");
    }

    @PostMapping("/api/imports/opening/preview")
    public Map<String, Object> preview(@Valid @RequestBody CsvPreview request) {
        return imports.preview(request);
    }

    @GetMapping("/api/imports/opening/{id}")
    public Map<String, Object> preview(@PathVariable UUID id) {
        return imports.get(id.toString());
    }

    @PostMapping("/api/imports/opening/{id}/commit")
    public Map<String, Object> commit(@PathVariable UUID id) {
        return imports.commit(id.toString());
    }
}
