package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.dtos.TenancyDtos.*;
import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.*;
import org.osyntra.osyntrainventorymanagement.identity.*;
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
    private final MembershipService memberships;
    private final SessionService sessions;
    private final IdentityService identity;
    private final IdentityAudit identityAudit;

    public TenancyService(MvpStore db, TenantContext tenant, EmployeeRepository employees, PasswordEncoder passwords, AuditService audit,
                          MembershipService memberships, SessionService sessions, IdentityService identity, IdentityAudit identityAudit) {
        this.db = db;
        this.tenant = tenant;
        this.employees = employees;
        this.passwords = passwords;
        this.audit = audit;
        this.memberships = memberships;
        this.sessions = sessions;
        this.identity = identity;
        this.identityAudit = identityAudit;
    }

    public List<Map<String, Object>> businesses() {
        tenant.requireRole("OPERATOR");
        return db.rows("SELECT id,name,currency,default_threshold,active FROM businesses ORDER BY id");
    }

    private int initialize(String name, String location, String currency, Integer threshold) {
        int business = integer(db.one("INSERT INTO businesses(name,currency,default_threshold) VALUES(?,?,?) RETURNING id", name.trim(), currency == null ? "JMD" : currency, threshold == null ? 3 : threshold), "id");
        db.update("INSERT INTO locations(business_id,name) VALUES(?,?)", business, location.trim());
        for (String code : List.of("OPENING_COUNT", "RECEIPT", "DAMAGE", "CORRECTION", "COUNT_APPROVAL"))
            db.update("INSERT INTO movement_reasons(business_id,code,label) VALUES(?,?,?)", business, code, code.replace('_', ' '));
        return business;
    }

    @Transactional
    public Map<String, Object> provision(Provision request) {
        tenant.requireRole("OPERATOR");
        if (employees.findByEmail(request.owner().email()).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing identities create their own business or join through an invitation; no password is overwritten");
        int business = initialize(request.name(), request.locationName(), request.currency(), request.defaultThreshold());
        var owner = request.owner();
        Employee employee = new Employee(owner.firstName().trim(), owner.lastName().trim(), IdentitySupport.normalize(owner.email()), passwords.encode(owner.password()), null, owner.jobTitle().trim(), EmployeeRole.OWNER);
        employee.setBusinessId(business);
        employees.save(employee);
        identity.requestEmail(employee.getEmail(), false);
        db.update("INSERT INTO platform_audit(actor_id,business_id,action) VALUES(?,?,'BUSINESS_PROVISIONED')", tenant.actor().getId(), business);
        identityAudit.event(tenant.actor().getId(), employee.getId(), business, "BUSINESS_PROVISIONED", null);
        return db.one("SELECT id,name,currency,default_threshold FROM businesses WHERE id=?", business);
    }

    @Transactional
    public Map<String, Object> createOwned(IdentityDtos.BusinessCreate request) {
        SessionService.full();
        int user = SessionService.current().userId();
        if (db.number("SELECT count(*) FROM platform_staff WHERE user_id=?", user) > 0)
            throw new org.springframework.security.access.AccessDeniedException("Use a separate tenant identity for a merchant business");
        int business = initialize(request.name(), request.locationName(), request.currency(), request.defaultThreshold());
        int membership = integer(db.one("INSERT INTO memberships(user_id,business_id,role,job_title,all_branches) VALUES(?,?,'OWNER','Owner',1) RETURNING id", user, business), "id");
        sessions.revokeAll(user);
        identityAudit.event(user, user, business, "BUSINESS_CREATED", "membership=" + membership);
        return Map.of("businessId", business, "membershipId", membership, "reauthenticationRequired", true);
    }

    public Map<String, Object> settings() {
        return db.one("SELECT id,name,currency,default_threshold FROM businesses WHERE id=?", tenant.businessId());
    }

    @Transactional
    public Map<String, Object> settings(Settings request) {
        tenant.requireRole("OWNER", "ADMINISTRATOR");
        if (!tenant.actor().hasAllBranches())
            throw new org.springframework.security.access.AccessDeniedException("All-branch access required for business-wide settings");
        if (!request.currency().equals(settings().get("currency")) && (db.number("SELECT count(*) FROM orders WHERE business_id=?", tenant.businessId()) > 0 ||
                db.number("SELECT count(*) FROM purchase_orders WHERE business_id=?", tenant.businessId()) > 0 ||
                db.number("SELECT count(*) FROM valuation_adjustments WHERE business_id=?", tenant.businessId()) > 0))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Currency is fixed after stock, purchasing or sales history exists in this single-currency MVP");
        db.update("UPDATE businesses SET name=?,currency=?,default_threshold=? WHERE id=?", request.name().trim(), request.currency(), request.defaultThreshold(), tenant.businessId());
        db.update("UPDATE inventory SET reorder_level=? WHERE business_id=? AND product_id IN(SELECT id FROM products WHERE business_id=? AND min_stock_threshold IS NULL)", request.defaultThreshold(), tenant.businessId(), tenant.businessId());
        audit.log("BUSINESS_UPDATED", "BUSINESS", tenant.businessId(), null, request);
        return settings();
    }

    public List<Map<String, Object>> locations() {
        return db.rows("SELECT id,name,active FROM locations WHERE business_id=?" + tenant.locationPredicate("id") + " ORDER BY id", tenant.businessId());
    }

    @Transactional
    public Map<String, Object> location(Location request) {
        tenant.requireRole("OWNER", "ADMINISTRATOR");
        if (!tenant.actor().hasAllBranches())
            throw new org.springframework.security.access.AccessDeniedException("All-branch management access is required to create a branch");
        var location = db.one("INSERT INTO locations(business_id,name) VALUES(?,?) RETURNING id,name,active", tenant.businessId(), request.name().trim());
        audit.log("LOCATION_CREATED", "LOCATION", location.get("id"), null, request);
        return location;
    }

    @Transactional
    public Map<String, Object> updateBranch(int id, IdentityDtos.BranchUpdate request) {
        tenant.requireRole("OWNER", "ADMINISTRATOR");
        tenant.requireLocation(id);
        int business = tenant.businessId();
        if (!request.active() && (db.number("SELECT count(*) FROM purchase_orders WHERE business_id=? AND location_id=? AND status IN('OPEN','PARTIAL')", business, id) > 0 ||
                db.number("SELECT count(*) FROM stock_transfers WHERE business_id=? AND (source_id=? OR destination_id=?) AND status IN('REQUESTED','DISPATCHED','PARTIAL')", business, id, id) > 0))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reconcile pending purchases and transfers before disabling a branch");
        var previous = db.one("SELECT active FROM locations WHERE id=? AND business_id=?", id, business);
        if (!request.active() && (db.number("SELECT count(*) FROM orders WHERE business_id=? AND location_id=? AND status IN('RESERVED','COMPLETED')", business, id) > 0 ||
                db.number("SELECT count(*) FROM stock_counts WHERE business_id=? AND location_id=? AND status IN('DRAFT','SUBMITTED')", business, id) > 0))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reconcile pending orders and submitted counts before disabling a branch");
        if (!request.active() && integer(previous, "active") == 1 && db.number("SELECT count(*) FROM locations WHERE business_id=? AND active=1", business) <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Keep at least one active branch");
        db.update("UPDATE locations SET name=?,active=? WHERE id=? AND business_id=?", request.name().trim(), request.active() ? 1 : 0, id, business);
        audit.logLocation("BRANCH_UPDATED", "LOCATION", id, null, request, id);
        return db.one("SELECT id,name,active FROM locations WHERE id=? AND business_id=?", id, business);
    }

    public int locationId(Integer requested) {
        if (requested != null) {
            tenant.requireLocation(requested);
            return locationId(tenant.businessId(), requested);
        }
        return integer(db.one("SELECT id FROM locations WHERE business_id=? AND active=1" + tenant.locationPredicate("id") + " ORDER BY id LIMIT 1", tenant.businessId()), "id");
    }

    public int locationId(int business, Integer requested) {
        if (requested == null)
            return integer(db.one("SELECT id FROM locations WHERE business_id=? AND active=1 ORDER BY id LIMIT 1", business), "id");
        return integer(db.one("SELECT id FROM locations WHERE id=? AND business_id=? AND active=1", requested, business), "id");
    }

    public List<Map<String, Object>> employees() {
        return memberships.businessMembers().stream().map(row -> {
            var result = new LinkedHashMap<>(row);
            result.put("membershipId", row.get("id"));
            result.put("id", row.get("user_id"));
            return (Map<String, Object>) result;
        }).toList();
    }

    @Transactional
    public Map<String, Object> employee(int user, EmployeeUpdate request) {
        tenant.requireRole("OWNER", "ADMINISTRATOR", "MANAGER");
        int business = tenant.businessId();
        var old = db.one("SELECT m.id,m.all_branches,u.first_name,u.last_name FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.user_id=? AND m.business_id=?", user, business);
        if (!request.firstName().trim().equals(old.get("first_name")) || !request.lastName().trim().equals(old.get("last_name")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Names belong to the global identity and can only be edited by that user");
        int membership = integer(old, "id");
        var branches = db.rows("SELECT location_id FROM membership_branches WHERE membership_id=?", membership).stream().map(r -> integer(r, "location_id")).toList();
        return memberships.update(membership, new IdentityDtos.MembershipUpdate(request.role().canonical().name(), request.status() == UserStatus.ACTIVE ? "ACTIVE" : "DEACTIVATED", integer(old, "all_branches") == 1, branches, request.jobTitle()));
    }
}
