package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.dtos.TenancyDtos.*;
import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class TenancyService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final EmployeeRepository employees;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public TenancyService(MvpStore db, TenantContext tenant, EmployeeRepository employees, PasswordEncoder passwords, AuditService audit) {
        this.db = db;
        this.tenant = tenant;
        this.employees = employees;
        this.passwords = passwords;
        this.audit = audit;
    }

    public List<Map<String, Object>> businesses() {
        tenant.requireRole("OPERATOR");
        return db.rows("SELECT id,name,currency,default_threshold,active FROM businesses ORDER BY id");
    }

    @Transactional
    public Map<String, Object> provision(Provision request) {
        tenant.requireRole("OPERATOR");
        int business = integer(db.one("INSERT INTO businesses(name,currency,default_threshold) VALUES(?,?,?) RETURNING id",
                request.name().trim(), request.currency() == null ? "JMD" : request.currency(),
                request.defaultThreshold() == null ? 3 : request.defaultThreshold()), "id");
        db.update("INSERT INTO locations(business_id,name) VALUES(?,?)", business, request.locationName().trim());
        for (String code : List.of("OPENING_COUNT", "RECEIPT", "DAMAGE", "CORRECTION", "COUNT_APPROVAL"))
            db.update("INSERT INTO movement_reasons(business_id,code,label) VALUES(?,?,?)", business, code, code.replace('_', ' '));
        var owner = request.owner();
        Employee employee = new Employee(owner.firstName().trim(), owner.lastName().trim(), owner.email().trim().toLowerCase(Locale.ROOT),
                passwords.encode(owner.password()), null, owner.jobTitle().trim(), EmployeeRole.OWNER);
        employee.setBusinessId(business);
        employees.save(employee);
        db.update("INSERT INTO platform_audit(actor_id,business_id,action) VALUES(?,?,'BUSINESS_PROVISIONED')",
                tenant.actor().getId(), business);
        return db.one("SELECT id,name,currency,default_threshold FROM businesses WHERE id=?", business);
    }

    public Map<String, Object> settings() {
        return db.one("SELECT id,name,currency,default_threshold FROM businesses WHERE id=?", tenant.businessId());
    }

    @Transactional
    public Map<String, Object> settings(Settings request) {
        tenant.requireRole("OWNER");
        if (!request.currency().equals(settings().get("currency")) &&
                db.number("SELECT count(*) FROM orders WHERE business_id=?", tenant.businessId()) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Currency is fixed after the first order in this single-currency MVP");
        db.update("UPDATE businesses SET name=?,currency=?,default_threshold=? WHERE id=?",
                request.name().trim(), request.currency(), request.defaultThreshold(), tenant.businessId());
        audit.log("BUSINESS_UPDATED", "BUSINESS", tenant.businessId(), null, request);
        return settings();
    }

    public List<Map<String, Object>> locations() {
        return db.rows("SELECT id,name,active FROM locations WHERE business_id=? ORDER BY id", tenant.businessId());
    }

    @Transactional
    public Map<String, Object> location(Location request) {
        tenant.requireRole("OWNER", "MANAGER");
        var location = db.one("INSERT INTO locations(business_id,name) VALUES(?,?) RETURNING id,name,active", tenant.businessId(), request.name().trim());
        audit.log("LOCATION_CREATED", "LOCATION", location.get("id"), null, request);
        return location;
    }

    public int locationId(Integer requested) {
        int business = tenant.businessId();
        return locationId(business, requested);
    }

    public int locationId(int business, Integer requested) {
        if (requested == null)
            return integer(db.one("SELECT id FROM locations WHERE business_id=? AND active=1 ORDER BY id LIMIT 1", business), "id");
        return integer(db.one("SELECT id FROM locations WHERE id=? AND business_id=? AND active=1", requested, business), "id");
    }

    public List<Map<String, Object>> employees() {
        tenant.requireRole("OWNER", "MANAGER");
        return db.rows("""
                SELECT u.id,u.first_name,u.last_name,u.email,u.status,e.job_title,e.employee_role AS role
                FROM users u JOIN employees e ON e.user_id=u.id WHERE e.business_id=? ORDER BY u.id
                """, tenant.businessId());
    }

    @Transactional
    public Map<String, Object> employee(int id, EmployeeUpdate request) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var old = db.one("SELECT e.employee_role,u.status FROM employees e JOIN users u ON u.id=e.user_id WHERE u.id=? AND e.business_id=?", id, business);
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean owner = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"));
        if (!owner && (Set.of("OWNER", "MANAGER").contains(string(old, "employee_role"))
                || Set.of(EmployeeRole.OWNER, EmployeeRole.MANAGER).contains(request.role())))
            throw new org.springframework.security.access.AccessDeniedException("Managers cannot edit privileged accounts");
        if ("OWNER".equals(string(old, "employee_role")) && "ACTIVE".equals(string(old, "status"))
                && (request.role() != EmployeeRole.OWNER || request.status() != UserStatus.ACTIVE)
                && db.number("SELECT count(*) FROM employees e JOIN users u ON u.id=e.user_id WHERE e.business_id=? AND e.employee_role='OWNER' AND u.status='ACTIVE'", business) <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A business needs at least one active owner");
        db.update("UPDATE users SET first_name=?,last_name=?,status=?,modified_at=strftime('%Y-%m-%d %H:%M:%f','now') WHERE id=?",
                request.firstName().trim(), request.lastName().trim(), request.status().name(), id);
        db.update("UPDATE employees SET job_title=?,employee_role=? WHERE user_id=? AND business_id=?", request.jobTitle().trim(), request.role().name(), id, business);
        audit.log("EMPLOYEE_UPDATED", "EMPLOYEE", id, old, request);
        return db.one("SELECT id,first_name,last_name,email,status FROM users WHERE id=?", id);
    }
}
