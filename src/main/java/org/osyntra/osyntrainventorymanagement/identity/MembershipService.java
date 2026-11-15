package org.osyntra.osyntrainventorymanagement.identity;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class MembershipService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final SessionService sessions;
    private final IdentityAudit audit;

    public MembershipService(MvpStore db, TenantContext tenant, SessionService sessions, IdentityAudit audit) {
        this.db = db;
        this.tenant = tenant;
        this.sessions = sessions;
        this.audit = audit;
    }

    public void assignable(String role) {
        String actor = tenant.role();
        if (actor.equals("OWNER")) return;
        if (actor.equals("ADMINISTRATOR") && !role.equals("OWNER")) return;
        if (actor.equals("MANAGER") && Set.of("CASHIER", "INVENTORY_CLERK").contains(role)) return;
        throw new AccessDeniedException("This role cannot grant or edit privileged memberships");
    }

    public void grants(String role, boolean all, List<Integer> branches) {
        assignable(role);
        int business = tenant.businessId();
        if (role.equals("OWNER") && !all)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Owners require all-branch access");
        if (all) {
            if (!tenant.actor().hasAllBranches())
                throw new AccessDeniedException("Cannot grant branches outside your own access");
            if (!branches.isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "All-branch grants must omit explicit branch IDs");
        } else {
            if (branches.isEmpty() || new HashSet<>(branches).size() != branches.size())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose distinct permitted branches");
            for (int branch : branches) {
                db.one("SELECT id FROM locations WHERE id=? AND business_id=? AND active=1", branch, business);
                tenant.requireLocation(branch);
            }
        }
    }

    public List<Map<String, Object>> list() {
        SessionService.full();
        int user = SessionService.current().userId();
        var rows = db.rows("""
                SELECT m.id,m.business_id,b.name AS business_name,m.role,m.status,m.all_branches,m.job_title
                FROM memberships m JOIN businesses b ON b.id=m.business_id WHERE m.user_id=? ORDER BY m.id
                """, user);
        for (var row : rows)
            row.put("branchIds", db.rows("SELECT location_id FROM membership_branches WHERE membership_id=? ORDER BY location_id", row.get("id")).stream().map(r -> r.get("location_id")).toList());
        return rows;
    }

    public List<Map<String, Object>> businessMembers() {
        tenant.requireRole("OWNER", "ADMINISTRATOR", "MANAGER");
        var rows = db.rows("""
                SELECT m.id,m.user_id,u.email,u.first_name,u.last_name,m.role,m.status,m.job_title,m.all_branches
                FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.business_id=? ORDER BY m.id
                """, tenant.businessId());
        // A restricted manager may manage only memberships contained in their own branch scope.
        return rows.stream().filter(this::visible).toList();
    }

    public List<Map<String, Object>> securityEvents(int limit, int offset) {
        tenant.requireRole("OWNER", "ADMINISTRATOR");
        if (!tenant.actor().hasAllBranches())
            throw new AccessDeniedException("All-branch management access is required for the business security audit");
        return db.rows("SELECT id,actor_id,subject_id,business_id,action,details,created_ms FROM identity_audit WHERE business_id=? ORDER BY created_ms DESC,id LIMIT ? OFFSET ?", tenant.businessId(), limit, offset);
    }

    private boolean visible(Map<String, Object> member) {
        if (tenant.actor().hasAllBranches()) return true;
        if (integer(member, "all_branches") == 1) return false;
        var own = tenant.allowedLocations();
        return db.rows("SELECT location_id FROM membership_branches WHERE membership_id=?", member.get("id")).stream()
                .allMatch(r -> own.contains(integer(r, "location_id")));
    }

    @Transactional
    public Map<String, Object> update(int id, IdentityDtos.MembershipUpdate r) {
        tenant.requireRole("OWNER", "ADMINISTRATOR", "MANAGER");
        int business = tenant.businessId();
        int actorId = tenant.actor().getId();
        var old = db.one("SELECT id,user_id,role,status,all_branches FROM memberships WHERE id=? AND business_id=?", id, business);
        if (!visible(old)) throw new AccessDeniedException("Membership is outside your branch-management scope");
        assignable(string(old, "role"));
        grants(r.role(), r.allBranches(), r.branchIds());
        if (old.get("role").equals("OWNER") && old.get("status").equals("ACTIVE") && (!r.role().equals("OWNER") || !r.status().equals("ACTIVE")) &&
                db.number("SELECT count(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.business_id=? AND m.role='OWNER' AND m.status='ACTIVE' AND u.status='ACTIVE'", business) <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A business needs an active owner");
        db.update("UPDATE memberships SET role=?,status=?,job_title=?,all_branches=? WHERE id=? AND business_id=?", r.role(), r.status(), r.jobTitle().trim(), r.allBranches() ? 1 : 0, id, business);
        db.update("DELETE FROM membership_branches WHERE membership_id=? AND business_id=?", id, business);
        for (int branch : r.branchIds())
            db.update("INSERT INTO membership_branches(membership_id,business_id,location_id) VALUES(?,?,?)", id, business, branch);
        int user = integer(old, "user_id");
        sessions.revokeAll(user);
        db.update("UPDATE email_outbox SET status='CANCELLED' WHERE reference_id IN(SELECT id FROM invitations WHERE created_by=? AND business_id=? AND status='PENDING') AND status IN('PENDING','FAILED')", user, business);
        db.update("UPDATE invitations SET status='REVOKED' WHERE created_by=? AND business_id=? AND status='PENDING'", user, business);
        audit.event(actorId, user, business, "MEMBERSHIP_UPDATED", "membership=" + id + "; role=" + r.role() + "; status=" + r.status() + "; allBranches=" + r.allBranches() + "; branches=" + r.branchIds());
        return db.one("SELECT id,user_id,business_id,role,status,job_title,all_branches FROM memberships WHERE id=? AND business_id=?", id, business);
    }
}
