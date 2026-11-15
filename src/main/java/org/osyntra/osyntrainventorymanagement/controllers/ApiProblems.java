package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.osyntra.osyntrainventorymanagement.config.RequestCorrelationFilter;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Shared ProblemDetail contract for MVC exceptions and failures rejected by Spring Security.
 */
@Component
public class ApiProblems {
    private final ObjectMapper mapper;

    public ApiProblems(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ProblemDetail create(HttpStatusCode status, String detail, HttpServletRequest request) {
        String code = switch (status.value()) {
            case 400 -> "VALIDATION_FAILED";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 500 -> "INTERNAL_ERROR";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "HTTP_" + status.value();
        };
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:orvexa:error:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getAttribute(RequestCorrelationFilter.ATTRIBUTE));
        return problem;
    }

    public void write(HttpStatus status, String detail, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), create(status, detail, request));
    }
}
