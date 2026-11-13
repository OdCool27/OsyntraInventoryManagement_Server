package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import org.osyntra.osyntrainventorymanagement.dtos.FitmentRequest;
import org.osyntra.osyntrainventorymanagement.services.FitmentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.*;

@RestController
public class FitmentController {
    private final FitmentService service;

    public FitmentController(FitmentService service) {
        this.service = service;
    }

    @PostMapping("/api/fitments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody FitmentRequest request) {
        return service.create(request);
    }

    @GetMapping("/api/products/{id}/fitments")
    public List<Map<String, Object>> list(@PathVariable UUID id) {
        return service.list(id);
    }

    @PostMapping("/api/products/{id}/fitments/{fitment}")
    public void link(@PathVariable UUID id, @PathVariable UUID fitment) {
        service.link(id, fitment, false);
    }

    @DeleteMapping("/api/products/{id}/fitments/{fitment}")
    public void unlink(@PathVariable UUID id, @PathVariable UUID fitment) {
        service.link(id, fitment, true);
    }
}
