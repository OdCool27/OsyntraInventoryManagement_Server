package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

@Component
public class CommandService {
    private final MvpStore db;

    public CommandService(MvpStore db) {
        this.db = db;
    }

    public String replay(int business, String requestId, Object command) {
        var rows = db.rows("SELECT fingerprint,entity_id FROM command_receipts WHERE business_id=? AND request_id=?", business, requestId);
        if (rows.isEmpty()) return null;
        if (!hash(command.toString()).equals(rows.getFirst().get("fingerprint")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request ID was already used for a different command");
        return rows.getFirst().get("entity_id").toString();
    }

    public String replayCompatible(int business, String requestId, Object command, String legacy) {
        var rows = db.rows("SELECT fingerprint,entity_id FROM command_receipts WHERE business_id=? AND request_id=?", business, requestId);
        if (rows.isEmpty()) return null;
        String stored = rows.getFirst().get("fingerprint").toString();
        if (!stored.equals(hash(command.toString())) && (legacy == null || !stored.equals(hash(legacy))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request ID was already used for a different command");
        return rows.getFirst().get("entity_id").toString();
    }

    public void save(int business, String requestId, Object command, Object entityId) {
        db.update("INSERT INTO command_receipts(business_id,request_id,fingerprint,entity_id) VALUES(?,?,?,?)", business, requestId, hash(command.toString()), entityId.toString());
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
