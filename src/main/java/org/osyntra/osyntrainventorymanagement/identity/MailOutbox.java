package org.osyntra.osyntrainventorymanagement.identity;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.util.*;

@Service
public class MailOutbox {
    private final MvpStore db;
    private final SecretProtection secrets;
    private final Clock clock;
    private final String base;
    private final boolean preview;
    private final Environment environment;

    public MailOutbox(MvpStore db, SecretProtection secrets, Clock clock, Environment environment,
                      @Value("${ORVEXA_PUBLIC_BASE_URL:http://localhost:5173}") String base,
                      @Value("${orvexa.identity.local-mail-preview:false}") boolean preview) {
        this.db = db;
        this.secrets = secrets;
        this.clock = clock;
        this.base = base.replaceAll("/+$", "");
        this.preview = preview;
        this.environment = environment;
        URI uri = URI.create(this.base);
        if (uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null ||
                !(uri.getScheme().equals("https") || uri.getScheme().equals("http") && Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost())))
            throw new IllegalStateException("ORVEXA_PUBLIC_BASE_URL must be HTTPS, or loopback HTTP for local development");
        if (preview && !environment.matchesProfiles("demo", "test", "dev"))
            throw new IllegalStateException("Local email preview requires the explicit demo, test or dev profile");
    }

    @Transactional
    public void enqueue(Integer user, Integer actor, Integer business, String kind, String reference, String email, String token) {
        String id = UUID.randomUUID().toString();
        String path = switch (kind) {
            case "INVITATION" -> "/accept-invitation";
            case "VERIFY_EMAIL" -> "/verify-email";
            default -> "/reset-password";
        };
        String subject = switch (kind) {
            case "INVITATION" -> "Your Orvexa business invitation";
            case "VERIFY_EMAIL" -> "Verify your Orvexa email";
            default -> "Reset your Orvexa password";
        };
        String body = subject + "\n\n" + base + path + "?token=" + token + "\n\nThis link is single-use and expires. If you did not request it, ignore it.";
        db.update("""
                INSERT INTO email_outbox(id,user_id,actor_id,business_id,recipient,subject,body_encrypted,kind,reference_id,next_attempt_ms,created_ms)
                VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(kind,reference_id) DO NOTHING
                """, id, user, actor, business, email, subject, secrets.encrypt("mail:" + id, body), kind, reference, clock.millis(), clock.millis());
    }

    @Transactional
    public void enqueueReceipt(int actor, int business, String email, String reference, String body) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO email_outbox(id,actor_id,business_id,recipient,subject,body_encrypted,kind,reference_id,next_attempt_ms,created_ms) VALUES(?,?,?,?,?,?,'SALE_RECEIPT',?,?,?) ON CONFLICT(kind,reference_id) DO NOTHING",
                id, actor, business, email, "Your Orvexa receipt", secrets.encrypt("mail:" + id, body), reference, clock.millis(), clock.millis());
    }

    public List<Map<String, Object>> preview(String remoteAddress) {
        if (!preview || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remoteAddress))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email preview is unavailable");
        int user = SessionService.current().userId();
        var rows = db.rows("SELECT id,recipient,subject,body_encrypted,kind,status,created_ms FROM email_outbox WHERE (user_id=? OR actor_id=?) AND status<>'CANCELLED' ORDER BY created_ms DESC,id LIMIT 50", user, user);
        for (var row : rows)
            row.put("body", secrets.decrypt("mail:" + row.get("id"), row.remove("body_encrypted").toString()));
        return rows;
    }

    public void cancel(String reference) {
        db.update("UPDATE email_outbox SET status='CANCELLED' WHERE reference_id=? AND status IN('PENDING','FAILED')", reference);
    }

    @Transactional
    public Map<String, Object> claim() {
        long now = clock.millis();
        db.update("""
                UPDATE email_outbox SET status='CANCELLED' WHERE status IN('PENDING','FAILED') AND
                 ((kind='INVITATION' AND EXISTS(SELECT 1 FROM invitations i WHERE i.id=email_outbox.reference_id AND (i.status<>'PENDING' OR i.expires_ms<=?)))
                 OR (kind IN('VERIFY_EMAIL','RESET_PASSWORD') AND EXISTS(SELECT 1 FROM identity_tokens t WHERE t.id=email_outbox.reference_id AND (t.consumed_ms IS NOT NULL OR t.expires_ms<=?))))
                """, now, now);
        var rows = db.rows("""
                SELECT * FROM email_outbox WHERE attempts<5 AND
                 ((status IN('PENDING','FAILED') AND next_attempt_ms<=?) OR (status='SENDING' AND lease_until_ms<?))
                ORDER BY created_ms,id LIMIT 1
                """, now, now);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        db.update("UPDATE email_outbox SET status='SENDING',attempts=attempts+1,lease_until_ms=? WHERE id=?", now + 120000, row.get("id"));
        row.put("body", secrets.decrypt("mail:" + row.get("id"), row.remove("body_encrypted").toString()));
        return row;
    }

    @Transactional
    public void delivered(String id) {
        db.update("UPDATE email_outbox SET status='SENT',sent_ms=?,lease_until_ms=NULL,last_error_code=NULL WHERE id=? AND status='SENDING'", clock.millis(), id);
    }

    @Transactional
    public void failed(String id) {
        db.update("UPDATE email_outbox SET status='FAILED',last_error_code='DELIVERY_FAILED',lease_until_ms=NULL,next_attempt_ms=? WHERE id=? AND status='SENDING'", clock.millis() + 60000, id);
    }
}
