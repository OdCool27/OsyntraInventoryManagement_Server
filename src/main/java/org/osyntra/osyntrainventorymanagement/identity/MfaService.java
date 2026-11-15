package org.osyntra.osyntrainventorymanagement.identity;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;
import static org.osyntra.osyntrainventorymanagement.identity.IdentitySupport.*;

@Service
public class MfaService {
    private final MvpStore db;
    private final SecretProtection secrets;
    private final SessionService sessions;
    private final PasswordEncoder passwords;
    private final IdentityAudit audit;
    private final Clock clock;

    public MfaService(MvpStore db, SecretProtection secrets, SessionService sessions, PasswordEncoder passwords, IdentityAudit audit, Clock clock) {
        this.db = db;
        this.secrets = secrets;
        this.sessions = sessions;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> setup(String password) {
        var principal = SessionService.current();
        if (!Set.of("MFA_ENROLL", "FULL").contains(principal.purpose()))
            throw new org.springframework.security.access.AccessDeniedException("Verify email or authenticate with MFA first");
        var user = db.one("SELECT mfa_enabled,password_hash FROM users WHERE id=?", principal.userId());
        if (principal.purpose().equals("FULL") && !matches(password, string(user, "password_hash"))) throw denied();
        if (integer(user, "mfa_enabled") == 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MFA is already enabled; use the recovery reset flow to re-enroll");
        String secret = Totp.secret();
        long expires = clock.millis() + 600000;
        db.update("UPDATE users SET mfa_pending_secret=?,mfa_pending_expires_ms=? WHERE id=?", secrets.encrypt("mfa:" + principal.userId(), secret), expires, principal.userId());
        audit.event(principal.userId(), principal.userId(), null, "MFA_ENROLLMENT_STARTED", null);
        String uri = "otpauth://totp/" + URLEncoder.encode("Orvexa:" + principal.email(), StandardCharsets.UTF_8)
                + "?secret=" + secret + "&issuer=Orvexa&algorithm=SHA1&digits=6&period=30";
        return Map.of("secret", secret, "otpauthUri", uri, "expiresAt", java.time.Instant.ofEpochMilli(expires).toString());
    }

    @Transactional
    public Map<String, Object> confirm(String code) {
        var principal = SessionService.current();
        if (!Set.of("MFA_ENROLL", "FULL").contains(principal.purpose())) throw denied();
        var user = db.one("SELECT * FROM users WHERE id=?", principal.userId());
        if (integer(user, "mfa_enabled") == 1 || user.get("mfa_pending_secret") == null ||
                ((Number) user.get("mfa_pending_expires_ms")).longValue() <= clock.millis()) throw badToken();
        String secret = secrets.decrypt("mfa:" + principal.userId(), string(user, "mfa_pending_secret"));
        long step = Totp.matchedStep(secret, code, clock.millis(), -1);
        if (step < 0) throw denied();
        db.update("UPDATE users SET mfa_secret=mfa_pending_secret,mfa_enabled=1,mfa_pending_secret=NULL,mfa_pending_expires_ms=NULL,mfa_last_step=? WHERE id=?", step, principal.userId());
        sessions.revokeAll(principal.userId());
        List<String> recovery = new ArrayList<>();
        db.update("DELETE FROM mfa_recovery_codes WHERE user_id=?", principal.userId());
        for (int i = 0; i < 10; i++) {
            String value = randomToken().substring(0, 22);
            recovery.add(value);
            db.update("INSERT INTO mfa_recovery_codes(user_id,code_hash) VALUES(?,?)", principal.userId(), hash(value));
        }
        audit.event(principal.userId(), principal.userId(), null, "MFA_ENABLED", null);
        var result = sessions.issue(principal.userId(), "FULL", true, principal.membershipId());
        result.put("recoveryCodes", recovery);
        return result;
    }

    @Transactional
    public Map<String, Object> verify(String code, String recoveryCode) {
        var principal = SessionService.current();
        if (!principal.purpose().equals("MFA_CHALLENGE")) throw denied();
        consume(principal.userId(), code, recoveryCode);
        sessions.revoke(principal.userId(), principal.sessionId());
        audit.event(principal.userId(), principal.userId(), null, "MFA_AUTHENTICATED", null);
        return sessions.issue(principal.userId(), "FULL", true, principal.membershipId());
    }

    private void consume(int user, String code, String recovery) {
        if (recovery != null && !recovery.isBlank()) {
            if (db.update("UPDATE mfa_recovery_codes SET used_ms=? WHERE user_id=? AND code_hash=? AND used_ms IS NULL", clock.millis(), user, hash(recovery)) != 1)
                throw denied();
            audit.event(user, user, null, "MFA_RECOVERY_USED", null);
            return;
        }
        var row = db.one("SELECT mfa_secret,mfa_enabled,mfa_last_step FROM users WHERE id=?", user);
        if (integer(row, "mfa_enabled") != 1) throw denied();
        long old = ((Number) row.get("mfa_last_step")).longValue();
        long step = Totp.matchedStep(secrets.decrypt("mfa:" + user, string(row, "mfa_secret")), code, clock.millis(), old);
        if (step < 0 || db.update("UPDATE users SET mfa_last_step=? WHERE id=? AND mfa_last_step=?", step, user, old) != 1)
            throw denied();
    }

    @Transactional
    public Map<String, Object> recover(String password, String recoveryCode) {
        var principal = SessionService.current();
        if (principal.purpose().equals("VERIFY_EMAIL")) throw denied();
        var user = db.one("SELECT password_hash FROM users WHERE id=?", principal.userId());
        if (!matches(password, string(user, "password_hash"))) throw denied();
        consume(principal.userId(), null, recoveryCode);
        db.update("UPDATE users SET mfa_secret=NULL,mfa_enabled=0,mfa_last_step=-1,mfa_pending_secret=NULL,auth_version=auth_version+1 WHERE id=?", principal.userId());
        db.update("DELETE FROM mfa_recovery_codes WHERE user_id=?", principal.userId());
        sessions.revokeAll(principal.userId());
        audit.event(principal.userId(), principal.userId(), null, "MFA_RECOVERY_RESET", null);
        return sessions.issue(principal.userId(), "MFA_ENROLL", false, principal.membershipId());
    }

    @Transactional
    public Map<String, Object> disable(String password, String code) {
        SessionService.full();
        var principal = SessionService.current();
        if (sessions.mfaRequired(principal.userId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MFA is mandatory for owners and platform staff");
        var user = db.one("SELECT password_hash FROM users WHERE id=?", principal.userId());
        if (!matches(password, string(user, "password_hash"))) throw denied();
        consume(principal.userId(), code, null);
        db.update("UPDATE users SET mfa_secret=NULL,mfa_enabled=0,mfa_last_step=-1,auth_version=auth_version+1 WHERE id=?", principal.userId());
        db.update("DELETE FROM mfa_recovery_codes WHERE user_id=?", principal.userId());
        sessions.revokeAll(principal.userId());
        audit.event(principal.userId(), principal.userId(), null, "MFA_DISABLED", null);
        return sessions.issue(principal.userId(), "FULL", false, principal.membershipId());
    }

    private boolean matches(String supplied, String encoded) {
        return supplied != null && supplied.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(supplied, encoded);
    }
}
