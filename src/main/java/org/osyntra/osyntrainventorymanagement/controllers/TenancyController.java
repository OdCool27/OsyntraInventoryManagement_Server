package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import org.osyntra.osyntrainventorymanagement.dtos.TenancyDtos.*;
import org.osyntra.osyntrainventorymanagement.services.TenancyService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.*;

@RestController
public class TenancyController {
    private final TenancyService service;

    public TenancyController(TenancyService service) {
        this.service = service;
    }

    @GetMapping("/api/platform/businesses")
    public List<Map<String, Object>> businesses() {
        return service.businesses();
    }

    @PostMapping("/api/platform/businesses")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> provision(@Valid @RequestBody Provision request) {
        return service.provision(request);
    }

    @GetMapping("/api/business")
    public Map<String, Object> business() {
        return service.settings();
    }

    @PutMapping("/api/business")
    public Map<String, Object> settings(@Valid @RequestBody Settings request) {
        return service.settings(request);
    }

    @GetMapping("/api/locations")
    public List<Map<String, Object>> locations() {
        return service.locations();
    }

    @PostMapping("/api/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> location(@Valid @RequestBody Location request) {
        return service.location(request);
    }

    @GetMapping("/api/employees")
    public List<Map<String, Object>> employees() {
        return service.employees();
    }

    @PutMapping("/api/employees/{id}")
    public Map<String, Object> employee(@PathVariable int id, @Valid @RequestBody EmployeeUpdate request) {
        return service.employee(id, request);
    }
}
