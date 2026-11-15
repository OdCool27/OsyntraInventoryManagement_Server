package org.osyntra.osyntrainventorymanagement.identity;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

/**
 * Call before the business transaction: rejected authentication must still consume an attempt.
 */
@Service
public class AuthRateLimiter {
    private final MvpStore db;
    private final SecretProtection protection;
    private final Clock clock;

    public AuthRateLimiter(MvpStore db, SecretProtection protection, Clock clock) {
        this.db = db;
        this.protection = protection;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void check(String scope, int limit, long window) {
        String key = protection.fingerprint(scope);
        long now = clock.millis();
        db.update("DELETE FROM auth_rate_limits WHERE window_ms<?", now - 86400000);
        var rows = db.rows("SELECT window_ms,hits FROM auth_rate_limits WHERE bucket_hash=?", key);
        if (rows.isEmpty())
            db.update("INSERT INTO auth_rate_limits(bucket_hash,window_ms,hits) VALUES(?,?,1)", key, now);
        else {
            long start = ((Number) rows.getFirst().get("window_ms")).longValue();
            if (now - start >= window)
                db.update("UPDATE auth_rate_limits SET window_ms=?,hits=1 WHERE bucket_hash=?", now, key);
            else {
                if (integer(rows.getFirst(), "hits") >= limit)
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts; try again later");
                db.update("UPDATE auth_rate_limits SET hits=hits+1 WHERE bucket_hash=?", key);
            }
        }
    }
}
