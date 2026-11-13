package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.EmployeeRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class TenantContext {
    private final EmployeeRepository employees;

    public TenantContext(EmployeeRepository employees) {
        this.employees = employees;
    }

    public Employee actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Employee login required");
        return employees.findByEmail(auth.getName()).filter(e -> e.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("Active employee required"));
    }

    public int businessId() {
        var actor = actor();
        if (actor.isOperator() || actor.getBusinessId() == null)
            throw new AccessDeniedException("Platform operators cannot access tenant business data");
        return actor.getBusinessId();
    }

    public void requireRole(String... roles) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        actor();
        for (String role : roles)
            if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role))) return;
        throw new AccessDeniedException("Action is not permitted for this role");
    }
}
