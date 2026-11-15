package org.osyntra.osyntrainventorymanagement;

import org.junit.jupiter.api.Test;
import org.osyntra.osyntrainventorymanagement.services.ProductService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FoundationTests {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    ProductService products;

    @Test
    void publicHealthAndProbesAreUsefulWithoutRevealingDatabaseDetails() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist())
                    .andExpect(content().string(not(containsString("jdbc:"))))
                    .andExpect(header().exists("X-Request-ID"));
        }
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/configprops").with(user("reader").roles("OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedAndForbiddenRequestsUseTheProblemContract() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(get("/api/products").header("X-Request-ID", requestId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"Orvexa\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").value(requestId));
        mvc.perform(post("/api/products").with(user("cashier").roles("CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void validationAndFrameworkErrorsKeepTheirHttpStatus() throws Exception {
        mvc.perform(post("/api/products").with(user("owner").roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/products?limit=0").with(user("owner").roles("OWNER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/unknown-route").with(user("owner").roles("OWNER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void missingResourcesAndServicePermissionsUseTheSameContract() throws Exception {
        failure(new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"), 404, "RESOURCE_NOT_FOUND");
        failure(new AccessDeniedException("sensitive-diagnostic"), 403, "FORBIDDEN");
    }

    @Test
    void databaseAndUnexpectedErrorsNeverExposeDiagnostics() throws Exception {
        failure(new DataIntegrityViolationException("sensitive-diagnostic"), 409, "CONFLICT");
        failure(new TransientDataAccessResourceException("sensitive-diagnostic"), 503, "SERVICE_UNAVAILABLE")
                .andExpect(header().string("Retry-After", "1"));
        failure(new IllegalStateException("sensitive-diagnostic"), 500, "INTERNAL_ERROR");
    }

    private ResultActions failure(RuntimeException exception, int status, String code) throws Exception {
        UUID product = UUID.randomUUID();
        String requestId = UUID.randomUUID().toString();
        when(products.findById(product)).thenThrow(exception);
        return mvc.perform(get("/api/products/" + product).with(user("owner").roles("OWNER"))
                        .header("X-Request-ID", requestId))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(content().string(not(containsString("sensitive-diagnostic"))));
    }

    @Test
    void correlationIdsAreValidatedAndMdcIsRestored() throws Exception {
        String supplied = UUID.randomUUID().toString();
        MDC.put("requestId", "outer-scope");
        try {
            mvc.perform(get("/actuator/health").header("X-Request-ID", supplied))
                    .andExpect(status().isOk()).andExpect(header().string("X-Request-ID", supplied));
            assertEquals("outer-scope", MDC.get("requestId"));
        } finally {
            MDC.remove("requestId");
        }
        String generated = mvc.perform(get("/actuator/health").header("X-Request-ID", "not-a-safe-uuid"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Request-ID");
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void separateFrontendCanSendAndReadCorrelationHeaders() throws Exception {
        mvc.perform(options("/api/products").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,x-request-id"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Headers", containsString("x-request-id")));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void openApiNamesOrvexaAndOsyntraWithoutRenamingExistingRoutes() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Orvexa API — Osyntra"))
                .andExpect(jsonPath("$.paths['/api/products']").exists())
                .andExpect(jsonPath("$.paths['/api/orders/{id}/payment']").exists());
    }
}
