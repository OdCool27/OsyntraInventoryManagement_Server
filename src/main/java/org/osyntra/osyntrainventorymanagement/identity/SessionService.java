package org.osyntra.osyntrainventorymanagement.identity;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.*;
import java.util.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;
import static org.osyntra.osyntrainventorymanagement.identity.IdentitySupport.*;

@Service
public class SessionService {
    private final MvpStore db;
    private final Clock clock;
    private final boolean demoPasswordOnly;

    public SessionService(MvpStore db, Clock clock, org.springframework.core.env.Environment environment) {
        this.db = db;
        this.clock = clock;
        var profiles = Set.of(environment.getActiveProfiles());
        this.demoPasswordOnly = environment.getProperty("osyntra.demo.enabled", Boolean.class, false)
                && (profiles.contains("demo") || profiles.contains("test"))
                && !profiles.contains("prod") && !profiles.contains("production");
    }

    // Only explicitly seeded local fixtures qualify; a demo-looking email alone is insufficient.
    public boolean passwordOnlyDemo(int user) {
        if (!demoPasswordOnly) return false;
        return db.number("""
                SELECT count(*) FROM users u WHERE u.id=? AND (
                    EXISTS(SELECT 1 FROM memberships m JOIN demo_seeds d ON d.business_id=m.business_id
                           WHERE m.user_id=u.id AND m.status='ACTIVE' AND d.seed_key IN('automotive','grocery')
                           AND lower(u.email) IN(d.seed_key || '.owner@demo.osyntra.local',
                               d.seed_key || '.manager@demo.osyntra.local', d.seed_key || '.clerk@demo.osyntra.local',
                               d.seed_key || '.cashier@demo.osyntra.local'))
                    OR (lower(u.email)='operator@demo.osyntra.local'
                        AND EXISTS(SELECT 1 FROM platform_staff p WHERE p.user_id=u.id AND p.active=1)
                        AND (SELECT count(*) FROM demo_seeds)=2))
                """, user) > 0;
    }

    public boolean mfaRequired(int user) {
        if (passwordOnlyDemo(user)) return false;
        return db.number("SELECT count(*) FROM platform_staff WHERE user_id=? AND active=1", user) > 0 ||
                db.number("SELECT count(*) FROM memberships WHERE user_id=? AND status='ACTIVE' AND role='OWNER'", user) > 0;
    }

    public Map<String, Object> issue(int user, String purpose, boolean verified, Integer membership) {
        return issue(user, purpose, verified, membership, clock.millis() + (purpose.equals("FULL") ? 28800000 : 300000));
    }

    private Map<String, Object> issue(int user, String purpose, boolean verified, Integer membership, long expires) {
        var account = db.one("SELECT auth_version FROM users WHERE id=? AND status='ACTIVE'", user);
        String token = randomToken();
        long now = clock.millis();
        db.update("""
                INSERT INTO identity_sessions(id,token_hash,user_id,membership_id,purpose,mfa_verified,auth_version,created_ms,last_seen_ms,expires_ms)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), hash(token), user, membership, purpose, verified ? 1 : 0, account.get("auth_version"), now, now, expires);
        return new LinkedHashMap<>(Map.of("accessToken", token, "tokenType", "Bearer", "purpose", purpose, "expiresAt", Instant.ofEpochMilli(expires).toString()));
    }

    public Authentication authenticate(String token) {
        if (token.length() < 40 || token.length() > 100) throw denied();
        var rows = db.rows("""
                SELECT s.*,u.email,u.status AS user_status,u.email_verified,u.mfa_enabled,u.auth_version AS current_version
                FROM identity_sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=?
                """, hash(token));
        if (rows.isEmpty()) throw denied();
        var session = rows.getFirst();
        long now = clock.millis();
        int user = integer(session, "user_id");
        if (session.get("revoked_ms") != null || ((Number) session.get("expires_ms")).longValue() <= now ||
                now - ((Number) session.get("last_seen_ms")).longValue() > 1800000 ||
                integer(session, "auth_version") != integer(session, "current_version") || !session.get("user_status").equals("ACTIVE"))
            throw denied();
        String purpose = string(session, "purpose");
        boolean mfaVerified = integer(session, "mfa_verified") == 1;
        if (!passwordOnlyDemo(user)) {
            if (integer(session, "email_verified") == 0) purpose = "VERIFY_EMAIL";
            else if (purpose.equals("FULL") && (mfaRequired(user) || integer(session, "mfa_enabled") == 1) && (!mfaVerified || integer(session, "mfa_enabled") == 0))
                purpose = integer(session, "mfa_enabled") == 1 ? "MFA_CHALLENGE" : "MFA_ENROLL";
        }
        Integer membership = session.get("membership_id") == null ? null : integer(session, "membership_id");
        List<String> roles = new ArrayList<>();
        if (!purpose.equals("FULL")) roles.add("ROLE_AUTH_PENDING");
        else {
            roles.add("ROLE_IDENTITY");
            var platform = db.rows("SELECT role FROM platform_staff WHERE user_id=? AND active=1", user);
            if (!platform.isEmpty()) {
                membership = null;
                roles.add("ROLE_PLATFORM_" + platform.getFirst().get("role"));
                if (Set.of("SUPER_ADMIN", "PROVISIONER").contains(platform.getFirst().get("role")))
                    roles.add("ROLE_OPERATOR");
            } else if (membership != null) {
                var member = db.rows("SELECT m.role FROM memberships m JOIN businesses b ON b.id=m.business_id WHERE m.id=? AND m.user_id=? AND m.status='ACTIVE' AND b.active=1", membership, user);
                if (member.isEmpty()) throw denied();
                String role = string(member.getFirst(), "role");
                roles.add("ROLE_" + role);
                if (role.equals("INVENTORY_CLERK")) roles.add("ROLE_CLERK");
                if (role.equals("ADMINISTRATOR")) roles.add("ROLE_MANAGER");
            }
        }
        db.update("UPDATE identity_sessions SET last_seen_ms=? WHERE id=? AND revoked_ms IS NULL", now, session.get("id"));
        var principal = new SessionPrincipal(user, string(session, "email"), string(session, "id"), membership, purpose, mfaVerified);
        return new UsernamePasswordAuthenticationToken(principal, null, AuthorityUtils.createAuthorityList(roles.toArray(String[]::new)));
    }

    public static SessionPrincipal current() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SessionPrincipal principal)) throw denied();
        return principal;
    }

    public static void full() {
        if (!current().purpose().equals("FULL"))
            throw new org.springframework.security.access.AccessDeniedException("Complete email verification and MFA before this action");
    }

    public Integer onlyMembership(int user) {
        var rows = db.rows("SELECT id FROM memberships WHERE user_id=? AND status='ACTIVE' ORDER BY id LIMIT 2", user);
        return rows.size() == 1 ? integer(rows.getFirst(), "id") : null;
    }

    @Transactional
    public Map<String, Object> select(int membership) {
        full();
        var principal = current();
        if (db.number("SELECT count(*) FROM platform_staff WHERE user_id=? AND active=1", principal.userId()) > 0)
            throw new org.springframework.security.access.AccessDeniedException("Platform identities cannot select tenant memberships");
        db.one("SELECT m.id FROM memberships m JOIN businesses b ON b.id=m.business_id WHERE m.id=? AND m.user_id=? AND m.status='ACTIVE' AND b.active=1", membership, principal.userId());
        var old = db.one("SELECT expires_ms FROM identity_sessions WHERE id=? AND user_id=? AND revoked_ms IS NULL", principal.sessionId(), principal.userId());
        revoke(principal.userId(), principal.sessionId());
        return issue(principal.userId(), "FULL", principal.mfaVerified(), membership, ((Number) old.get("expires_ms")).longValue());
    }

    public List<Map<String, Object>> list() {
        full();
        return db.rows("SELECT id,membership_id,created_ms,last_seen_ms,expires_ms FROM identity_sessions WHERE user_id=? AND revoked_ms IS NULL AND expires_ms>? ORDER BY created_ms DESC", current().userId(), clock.millis());
    }

    @Transactional
    public void revoke(int user, String id) {
        if (db.update("UPDATE identity_sessions SET revoked_ms=? WHERE user_id=? AND id=? AND revoked_ms IS NULL", clock.millis(), user, id) != 1)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Session not found");
    }

    public void revokeAll(int user) {
        db.update("UPDATE identity_sessions SET revoked_ms=? WHERE user_id=? AND revoked_ms IS NULL", clock.millis(), user);
    }
}
