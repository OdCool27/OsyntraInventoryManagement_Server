package org.osyntra.osyntrainventorymanagement.identity;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;
import static org.osyntra.osyntrainventorymanagement.identity.IdentitySupport.*;

@Service
public class IdentityService {
    private final MvpStore db;
    private final PasswordEncoder passwords;
    private final SessionService sessions;
    private final MailOutbox mail;
    private final IdentityAudit audit;
    private final Clock clock;
    private final String dummyHash;

    public IdentityService(MvpStore db, PasswordEncoder passwords, SessionService sessions, MailOutbox mail, IdentityAudit audit, Clock clock) {
        this.db = db;
        this.passwords = passwords;
        this.sessions = sessions;
        this.mail = mail;
        this.audit = audit;
        this.clock = clock;
        dummyHash = passwords.encode(randomToken());
    }

    public static Map<String, Object> accepted() {
        return Map.of("message", "If this request is applicable, an email has been queued");
    }

    @Transactional
    public Map<String, Object> register(IdentityDtos.Register r) {
        password(r.password());
        String email = normalize(r.email());
        String encoded = passwords.encode(r.password());
        if (!db.rows("SELECT id FROM users WHERE lower(email)=?", email).isEmpty()) return accepted();
        int user = integer(db.one("INSERT INTO users(first_name,last_name,email,password_hash,status) VALUES(?,?,?,?,'ACTIVE') RETURNING id",
                r.firstName().trim(), r.lastName().trim(), email, encoded), "id");
        emailToken(user, email, "VERIFY_EMAIL", 86400000);
        audit.event(user, user, null, "IDENTITY_REGISTERED", null);
        return accepted();
    }

    @Transactional
    public Map<String, Object> login(IdentityDtos.Login r) {
        var accounts = db.rows("SELECT * FROM users WHERE lower(email)=?", normalize(r.email()));
        String encoded = accounts.isEmpty() ? dummyHash : string(accounts.getFirst(), "password_hash");
        boolean valid = r.password().getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(r.password(), encoded);
        if (accounts.isEmpty() || !valid || !accounts.getFirst().get("status").equals("ACTIVE")) throw denied();
        var account = accounts.getFirst();
        int user = integer(account, "id");
        String purpose = sessions.passwordOnlyDemo(user) ? "FULL" : integer(account, "email_verified") == 0 ? "VERIFY_EMAIL" :
                integer(account, "mfa_enabled") == 1 ? "MFA_CHALLENGE" : sessions.mfaRequired(user) ? "MFA_ENROLL" : "FULL";
        audit.event(user, user, null, "PASSWORD_AUTHENTICATED", "Next step: " + purpose);
        var result = sessions.issue(user, purpose, false, sessions.onlyMembership(user));
        result.put("emailVerified", integer(account, "email_verified") == 1);
        result.put("mfaRequired", sessions.mfaRequired(user));
        return result;
    }

    @Transactional
    public Map<String, Object> requestEmail(String email, boolean reset) {
        var users = db.rows("SELECT id,email,email_verified FROM users WHERE lower(email)=? AND status='ACTIVE'", normalize(email));
        if (!users.isEmpty() && (reset || integer(users.getFirst(), "email_verified") == 0)) {
            int user = integer(users.getFirst(), "id");
            emailToken(user, string(users.getFirst(), "email"), reset ? "RESET_PASSWORD" : "VERIFY_EMAIL", reset ? 1800000 : 86400000);
        }
        return accepted();
    }

    private void emailToken(int user, String email, String purpose, long ttl) {
        for (var previous : db.rows("SELECT id FROM identity_tokens WHERE user_id=? AND purpose=? AND consumed_ms IS NULL", user, purpose))
            mail.cancel(previous.get("id").toString());
        db.update("UPDATE identity_tokens SET consumed_ms=? WHERE user_id=? AND purpose=? AND consumed_ms IS NULL", clock.millis(), user, purpose);
        String token = randomToken(), id = UUID.randomUUID().toString();
        db.update("INSERT INTO identity_tokens(id,token_hash,user_id,purpose,created_ms,expires_ms) VALUES(?,?,?,?,?,?)", id, hash(token), user, purpose, clock.millis(), clock.millis() + ttl);
        mail.enqueue(user, null, null, purpose, id, email, token);
    }

    private Map<String, Object> consume(String token, String purpose) {
        var rows = db.rows("SELECT * FROM identity_tokens WHERE token_hash=? AND purpose=? AND consumed_ms IS NULL AND expires_ms>?", hash(token), purpose, clock.millis());
        if (rows.isEmpty()) throw badToken();
        var row = rows.getFirst();
        if (db.update("UPDATE identity_tokens SET consumed_ms=? WHERE token_hash=? AND consumed_ms IS NULL AND expires_ms>?", clock.millis(), hash(token), clock.millis()) != 1)
            throw badToken();
        mail.cancel(row.get("id").toString());
        return row;
    }

    @Transactional
    public Map<String, Object> verifyEmail(String token) {
        var row = consume(token, "VERIFY_EMAIL");
        int user = integer(row, "user_id");
        db.update("UPDATE users SET email_verified=1 WHERE id=?", user);
        sessions.revokeAll(user);
        audit.event(user, user, null, "EMAIL_VERIFIED", null);
        return Map.of("message", "Email verified; sign in again");
    }

    @Transactional
    public Map<String, Object> reset(IdentityDtos.Reset r) {
        password(r.password());
        var row = consume(r.token(), "RESET_PASSWORD");
        int user = integer(row, "user_id");
        db.update("UPDATE users SET password_hash=?,email_verified=1,auth_version=auth_version+1,modified_at=strftime('%Y-%m-%d %H:%M:%f','now') WHERE id=?",
                passwords.encode(r.password()), user);
        sessions.revokeAll(user);
        db.update("UPDATE identity_tokens SET consumed_ms=? WHERE user_id=? AND consumed_ms IS NULL", clock.millis(), user);
        audit.event(user, user, null, "PASSWORD_RESET", null);
        return Map.of("message", "Password reset; sign in again. MFA remains enabled if previously configured.");
    }

    public List<Map<String, Object>> memberships(int user) {
        return db.rows("""
                SELECT m.id,m.business_id,b.name AS business_name,m.role,m.status,m.job_title,m.all_branches
                FROM memberships m JOIN businesses b ON b.id=m.business_id WHERE m.user_id=? ORDER BY m.id
                """, user);
    }

    public Map<String, Object> self() {
        var principal = SessionService.current();
        var user = db.one("SELECT id,first_name,last_name,email,status,email_verified,mfa_enabled FROM users WHERE id=?", principal.userId());
        Map<String, Object> result = new LinkedHashMap<>(user);
        var platform = db.rows("SELECT role FROM platform_staff WHERE user_id=? AND active=1", principal.userId());
        result.put("operator", !platform.isEmpty());
        result.put("platformRole", platform.isEmpty() ? null : platform.getFirst().get("role"));
        result.put("purpose", principal.purpose());
        result.put("mfaRequired", sessions.mfaRequired(principal.userId()));
        result.put("memberships", memberships(principal.userId()));
        result.put("activeMembershipId", principal.membershipId());
        if (principal.membershipId() != null) {
            var membership = db.one("SELECT business_id,role FROM memberships WHERE id=? AND user_id=?", principal.membershipId(), principal.userId());
            result.put("businessId", membership.get("business_id"));
            result.put("role", membership.get("role"));
        } else {
            result.put("businessId", null);
            result.put("role", platform.isEmpty() ? null : "OPERATOR");
        }
        return result;
    }

    public List<Map<String, Object>> securityEvents(int limit, int offset) {
        SessionService.full();
        int user = SessionService.current().userId();
        return db.rows("SELECT id,actor_id,subject_id,business_id,action,details,created_ms FROM identity_audit WHERE actor_id=? OR subject_id=? ORDER BY created_ms DESC,id LIMIT ? OFFSET ?", user, user, limit, offset);
    }
}
