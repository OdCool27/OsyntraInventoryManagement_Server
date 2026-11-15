package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.*;
import org.osyntra.osyntrainventorymanagement.catalogue.*;

import java.util.*;

@RestController
@Validated
@RequestMapping("/api/v1/catalogue")
public class CatalogueController {
    private final UnitService units;
    private final LabelService labels;
    private final CatalogueImportService imports;

    public CatalogueController(UnitService units, LabelService labels, CatalogueImportService imports) {
        this.units = units;
        this.labels = labels;
        this.imports = imports;
    }

    @GetMapping("/products/{id}/units")
    public List<Map<String, Object>> units(@PathVariable UUID id) {
        return units.list(id.toString());
    }

    @PostMapping("/products/{id}/units")
    public List<Map<String, Object>> unit(@PathVariable UUID id, @Valid @RequestBody CatalogueDtos.Unit r) {
        return units.add(id.toString(), r);
    }

    @PostMapping("/products/{id}/convert")
    public Map<String, Object> convert(@PathVariable UUID id, @Valid @RequestBody CatalogueDtos.Convert r) {
        return units.convert(id.toString(), r);
    }

    @PostMapping("/products/{id}/barcode")
    public Map<String, Object> barcode(@PathVariable UUID id) {
        return labels.generate(id.toString());
    }

    @GetMapping("/products/{id}/label")
    public ResponseEntity<byte[]> label(@PathVariable UUID id, @RequestParam(defaultValue = "svg") @Pattern(regexp = "svg|png|pdf") String format, @RequestParam(defaultValue = "100") int widthMm, @RequestParam(defaultValue = "30") int heightMm) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(switch (format) {
                    case "svg" -> "image/svg+xml";
                    case "png" -> "image/png";
                    default -> "application/pdf";
                }))
                .header("Content-Disposition", "attachment; filename=label-" + id + "." + format).body(labels.label(id.toString(), format, widthMm, heightMm));
    }

    @GetMapping("/imports/template")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "csv") @Pattern(regexp = "csv|xlsx") String format) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(format.equals("csv") ? "text/csv" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header("Content-Disposition", "attachment; filename=catalogue-template." + format).body(format.equals("csv") ? imports.template().getBytes(java.nio.charset.StandardCharsets.UTF_8) : imports.templateXlsx());
    }

    @PostMapping("/imports/csv")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> csv(@Valid @RequestBody CatalogueDtos.CsvImport r) throws Exception {
        return imports.csv(r);
    }

    @PostMapping(value = "/imports/xlsx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> xlsx(@RequestPart MultipartFile file, @Valid @RequestPart CatalogueDtos.ImportOptions options) throws Exception {
        if (file.getSize() > 1000000)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload limit is 1 MB");
        return imports.workbook(file.getBytes(), options);
    }

    @GetMapping("/imports/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return imports.get(id.toString());
    }

    @PostMapping("/imports/{id}/commit")
    public Map<String, Object> commit(@PathVariable UUID id, @Valid @RequestBody CatalogueDtos.Commit r) throws Exception {
        return imports.commit(id.toString(), r);
    }
}
