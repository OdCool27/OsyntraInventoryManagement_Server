package org.osyntra.osyntrainventorymanagement.identity;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

@Component
public class IdentityAudit {
    private final MvpStore db;
    private final Clock clock;

    public IdentityAudit(MvpStore db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void event(Integer actor, Integer subject, Integer business, String action, String details) {
        db.update("INSERT INTO identity_audit(id,actor_id,subject_id,business_id,action,details,created_ms) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), actor, subject, business, action, details, clock.millis());
    }
}
