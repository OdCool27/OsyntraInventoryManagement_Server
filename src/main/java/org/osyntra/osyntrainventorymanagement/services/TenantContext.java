package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.*;
import org.osyntra.osyntrainventorymanagement.identity.SessionPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Component
public class TenantContext {
    private final EmployeeRepository employees;
    private final MvpStore db;

    public TenantContext(EmployeeRepository employees, MvpStore db) {
        this.employees = employees;
        this.db = db;
    }

    public Employee actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new AccessDeniedException("Identity login required");
        if (auth.getPrincipal() instanceof SessionPrincipal principal && !principal.purpose().equals("FULL"))
            throw new AccessDeniedException("Complete email verification and MFA first");
        return employees.findByEmail(auth.getName()).filter(e -> e.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("Active identity and membership required"));
    }

    public int businessId() {
        var actor = actor();
        if (actor.isOperator() || actor.getBusinessId() == null)
            throw new AccessDeniedException("Select an active tenant membership; platform staff cannot access tenant data");
        db.one("SELECT id FROM businesses WHERE id=? AND active=1", actor.getBusinessId());
        return actor.getBusinessId();
    }

    public String role() {
        var role = actor().getRole();
        return role == null ? "" : role.canonical().name();
    }

    public void requireRole(String... roles) {
        var actor = actor();
        String actual = role();
        for (String role : roles) {
            if (role.equals("OPERATOR") && actor.isOperator() && Set.of("SUPER_ADMIN", "PROVISIONER").contains(actor.getPlatformRole()))
                return;
            if (role.equals("CLERK")) role = "INVENTORY_CLERK";
            if (!actor.isOperator() && (actual.equals(role) || actual.equals("ADMINISTRATOR") && role.equals("MANAGER")))
                return;
        }
        throw new AccessDeniedException("Action is not permitted for this membership role");
    }

    public List<Integer> allowedLocations() {
        var actor = actor();
        int business = businessId();
        if (actor.hasAllBranches())
            return db.rows("SELECT id FROM locations WHERE business_id=? ORDER BY id", business).stream().map(r -> MvpStore.integer(r, "id")).toList();
        return db.rows("SELECT location_id FROM membership_branches WHERE membership_id=? AND business_id=? ORDER BY location_id", actor.getMembershipId(), business)
                .stream().map(r -> MvpStore.integer(r, "location_id")).toList();
    }

    public void requireLocation(int location) {
        db.one("SELECT id FROM locations WHERE id=? AND business_id=?", location, businessId());
        if (!allowedLocations().contains(location))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found");
    }

    public String locationPredicate(String trustedColumn) {
        if (!trustedColumn.matches("[a-z_]+\\.[a-z_]+|[a-z_]+"))
            throw new IllegalArgumentException("Column is not trusted");
        var ids = allowedLocations();
        String values = ids.isEmpty() ? "NULL" : ids.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        // These integer IDs come only from authenticated membership grants, never client SQL.
        return " AND " + trustedColumn + " IN (" + values + ")";
    }
}
