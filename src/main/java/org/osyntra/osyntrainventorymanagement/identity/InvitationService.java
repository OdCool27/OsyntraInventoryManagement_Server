package org.osyntra.osyntrainventorymanagement.identity;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;
import static org.osyntra.osyntrainventorymanagement.identity.IdentitySupport.*;

@Service
public class InvitationService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final MembershipService members;
    private final PasswordEncoder passwords;
    private final MailOutbox mail;
    private final SessionService sessions;
    private final IdentityAudit audit;
    private final Clock clock;

    public InvitationService(MvpStore db, TenantContext tenant, MembershipService members, PasswordEncoder passwords, MailOutbox mail, SessionService sessions, IdentityAudit audit, Clock clock) {
        this.db = db;
        this.tenant = tenant;
        this.members = members;
        this.passwords = passwords;
        this.mail = mail;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> issue(IdentityDtos.Invite r) {
        tenant.requireRole("OWNER", "ADMINISTRATOR", "MANAGER");
        members.grants(r.role(), r.allBranches(), r.branchIds());
        int business = tenant.businessId(), actor = tenant.actor().getId();
        String email = normalize(r.email());
        if (db.number("SELECT count(*) FROM users u JOIN platform_staff s ON s.user_id=u.id WHERE lower(u.email)=?", email) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Platform identities cannot join tenant businesses");
        if (db.number("SELECT count(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.business_id=? AND lower(u.email)=? AND m.status='ACTIVE'", business, email) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This identity already has an active membership");
        for (var expired : db.rows("SELECT id FROM invitations WHERE business_id=? AND email=? AND status='PENDING' AND expires_ms<=?", business, email, clock.millis())) {
            db.update("UPDATE invitations SET status='EXPIRED' WHERE id=?", expired.get("id"));
            mail.cancel(expired.get("id").toString());
        }
        String id = UUID.randomUUID().toString(), token = randomToken();
        db.update("INSERT INTO invitations(id,business_id,email,role,all_branches,token_hash,created_by,created_ms,expires_ms) VALUES(?,?,?,?,?,?,?,?,?)",
                id, business, email, r.role(), r.allBranches() ? 1 : 0, hash(token), actor, clock.millis(), clock.millis() + 172800000);
        for (int branch : r.branchIds())
            db.update("INSERT INTO invitation_branches(invitation_id,business_id,location_id) VALUES(?,?,?)", id, business, branch);
        var users = db.rows("SELECT id FROM users WHERE lower(email)=?", email);
        Integer user = users.isEmpty() ? null : integer(users.getFirst(), "id");
        mail.enqueue(user, actor, business, "INVITATION", id, email, token);
        audit.event(actor, user, business, "INVITATION_ISSUED", "invitation=" + id + "; role=" + r.role() + "; allBranches=" + r.allBranches() + "; branches=" + r.branchIds());
        return db.one("SELECT id,email,role,all_branches,status,expires_ms FROM invitations WHERE id=? AND business_id=?", id, business);
    }

    public List<Map<String, Object>> list() {
        tenant.requireRole("OWNER", "ADMINISTRATOR", "MANAGER");
        var invitations = db.rows("SELECT id,email,role,all_branches,status,created_by,expires_ms FROM invitations WHERE business_id=? ORDER BY created_ms DESC LIMIT 200", tenant.businessId());
        var own = tenant.allowedLocations();
        return invitations.stream().filter(row -> tenant.actor().hasAllBranches() ||
                        integer(row, "all_branches") == 0 && db.rows("SELECT location_id FROM invitation_branches WHERE invitation_id=?", row.get("id")).stream().allMatch(b -> own.contains(integer(b, "location_id"))))
                .peek(row -> {
                    if (row.get("status").equals("PENDING") && ((Number) row.get("expires_ms")).longValue() <= clock.millis())
                        row.put("status", "EXPIRED");
                }).toList();
    }

    @Transactional
    public void revoke(String id) {
        tenant.requireRole("OWNER", "ADMINISTRATOR", "MANAGER");
        int business = tenant.businessId();
        var row = db.one("SELECT role,all_branches,status FROM invitations WHERE id=? AND business_id=?", id, business);
        var branches = db.rows("SELECT location_id FROM invitation_branches WHERE invitation_id=? AND business_id=?", id, business).stream().map(b -> integer(b, "location_id")).toList();
        members.grants(string(row, "role"), integer(row, "all_branches") == 1, branches);
        if (!row.get("status").equals("PENDING"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation is no longer pending");
        db.update("UPDATE invitations SET status='REVOKED' WHERE id=? AND business_id=?", id, business);
        mail.cancel(id);
        audit.event(tenant.actor().getId(), null, business, "INVITATION_REVOKED", "invitation=" + id);
    }

    private boolean issuerStillAuthorized(Map<String, Object> invitation, List<Integer> branches) {
        var rows = db.rows("""
                SELECT m.id,m.role,m.all_branches FROM memberships m JOIN users u ON u.id=m.user_id
                WHERE m.user_id=? AND m.business_id=? AND m.status='ACTIVE' AND u.status='ACTIVE'
                """, invitation.get("created_by"), invitation.get("business_id"));
        if (rows.isEmpty()) return false;
        var issuer = rows.getFirst();
        String role = string(issuer, "role"), target = string(invitation, "role");
        boolean allowed = role.equals("OWNER") || role.equals("ADMINISTRATOR") && !target.equals("OWNER") || role.equals("MANAGER") && Set.of("CASHIER", "INVENTORY_CLERK").contains(target);
        if (!allowed) return false;
        if (integer(invitation, "all_branches") == 1 && integer(issuer, "all_branches") != 1) return false;
        for (int branch : branches) {
            if (db.number("SELECT count(*) FROM locations WHERE id=? AND business_id=? AND active=1", branch, invitation.get("business_id")) != 1)
                return false;
            if (integer(issuer, "all_branches") != 1 && db.number("SELECT count(*) FROM membership_branches WHERE membership_id=? AND location_id=?", issuer.get("id"), branch) != 1)
                return false;
        }
        return true;
    }

    @Transactional
    public Map<String, Object> accept(IdentityDtos.Accept r) {
        var rows = db.rows("SELECT * FROM invitations WHERE token_hash=? AND status='PENDING' AND expires_ms>?", hash(r.token()), clock.millis());
        if (rows.isEmpty()) throw badToken();
        var invitation = rows.getFirst();
        int business = integer(invitation, "business_id");
        db.one("SELECT id FROM businesses WHERE id=? AND active=1", business);
        var branches = db.rows("SELECT location_id FROM invitation_branches WHERE invitation_id=? AND business_id=?", invitation.get("id"), business).stream().map(b -> integer(b, "location_id")).toList();
        if (!issuerStillAuthorized(invitation, branches)) throw badToken();
        var users = db.rows("SELECT id,email,status FROM users WHERE lower(email)=?", invitation.get("email"));
        int user;
        if (users.isEmpty()) {
            if (r.firstName() == null || r.firstName().isBlank() || r.lastName() == null || r.lastName().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New identities require first and last names");
            password(r.password());
            user = integer(db.one("INSERT INTO users(first_name,last_name,email,password_hash,status,email_verified) VALUES(?,?,?,?,'ACTIVE',1) RETURNING id",
                    r.firstName().trim(), r.lastName().trim(), invitation.get("email"), passwords.encode(r.password())), "id");
        } else {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal) ||
                    principal.userId() != integer(users.getFirst(), "id") || !Set.of("FULL", "VERIFY_EMAIL").contains(principal.purpose()) || !users.getFirst().get("status").equals("ACTIVE"))
                throw denied();
            user = integer(users.getFirst(), "id");
            if (db.number("SELECT count(*) FROM platform_staff WHERE user_id=?", user) > 0) throw badToken();
            db.update("UPDATE users SET email_verified=1 WHERE id=?", user);
        }
        if (db.number("SELECT count(*) FROM memberships WHERE user_id=? AND business_id=? AND status='ACTIVE'", user, business) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Membership is already active");
        int membership = integer(db.one("""
                INSERT INTO memberships(user_id,business_id,role,status,job_title,all_branches) VALUES(?,?,?,'ACTIVE',?,?)
                ON CONFLICT(user_id,business_id) DO UPDATE SET role=excluded.role,status='ACTIVE',job_title=excluded.job_title,all_branches=excluded.all_branches
                WHERE memberships.status='DEACTIVATED' RETURNING id
                """, user, business, invitation.get("role"), invitation.get("role"), invitation.get("all_branches")), "id");
        db.update("DELETE FROM membership_branches WHERE membership_id=?", membership);
        for (int branch : branches)
            db.update("INSERT INTO membership_branches(membership_id,business_id,location_id) VALUES(?,?,?)", membership, business, branch);
        if (db.update("UPDATE invitations SET status='ACCEPTED',accepted_by=? WHERE id=? AND status='PENDING' AND expires_ms>?", user, invitation.get("id"), clock.millis()) != 1)
            throw badToken();
        sessions.revokeAll(user);
        mail.cancel(invitation.get("id").toString());
        audit.event(user, user, business, "INVITATION_ACCEPTED", "invitation=" + invitation.get("id") + "; membership=" + membership);
        return Map.of("membershipId", membership, "businessId", business, "role", invitation.get("role"), "reauthenticationRequired", true);
    }
}
