package org.osyntra.osyntrainventorymanagement.repositories;

import org.osyntra.osyntrainventorymanagement.models.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class AuditRepository {
    private final JdbcClient jdbcClient;
    private final org.osyntra.osyntrainventorymanagement.services.TenantContext tenant;

    public AuditRepository(JdbcClient jdbcClient, org.osyntra.osyntrainventorymanagement.services.TenantContext tenant) {
        this.jdbcClient = jdbcClient;
        this.tenant = tenant;
    }

    public void create(int userId, String action, String entityType, String entityId, String oldValue, String newValue, Integer location) {
        jdbcClient.sql("""
                        INSERT INTO audit_logs(user_id, action, entity_type, entity_id, old_value, new_value, business_id, location_id)
                        VALUES (:user, :action, :type, :id, :old, :new, :business, :location)
                        """).param("user", userId).param("action", action).param("type", entityType)
                .param("id", entityId).param("old", oldValue).param("new", newValue).param("business", tenant.businessId()).param("location", location).update();
    }

    public List<AuditLog> findAll(String entityType, String entityId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_logs WHERE business_id = :business");
        if (!tenant.actor().hasAllBranches()) sql.append(tenant.locationPredicate("location_id"));
        Map<String, Object> params = new HashMap<>();
        params.put("business", tenant.businessId());
        if (entityType != null) {
            sql.append(" AND entity_type = :type");
            params.put("type", entityType);
        }
        if (entityId != null) {
            sql.append(" AND entity_id = :id");
            params.put("id", entityId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbcClient.sql(sql.toString()).params(params).query((rs, row) -> new AuditLog(
                rs.getLong("id"), rs.getInt("user_id"), rs.getString("action"), rs.getString("entity_type"),
                rs.getString("entity_id"), rs.getString("old_value"), rs.getString("new_value"),
                DatabaseDialect.utcInstant(rs, "created_at"))).list();
    }
}
