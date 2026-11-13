package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.osyntra.osyntrainventorymanagement.dtos.product.*;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.osyntra.osyntrainventorymanagement.repositories.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/products")
@Validated
public class ProductController {
    private final ProductService products;
    private final ProductRepository repository;
    private final TenantContext tenant;

    public ProductController(ProductService products, ProductRepository repository, TenantContext tenant) {
        this.products = products;
        this.repository = repository;
        this.tenant = tenant;
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(required = false) String search, @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String barcode, @RequestParam(required = false) String make, @RequestParam(required = false) String model,
                                      @RequestParam(required = false) @Min(1886) @Max(2200) Integer year, @RequestParam(required = false) String engine, @RequestParam(required = false) String trim,
                                      @RequestParam(defaultValue = "false") boolean includeInactive, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
                                      @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return repository.search(tenant.businessId(), search, category, barcode, make, model, year, engine, trim, includeInactive, limit, offset);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable UUID id) {
        return products.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        return products.create(request);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return products.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        products.deactivate(id);
    }
}
