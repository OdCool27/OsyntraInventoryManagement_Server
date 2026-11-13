package org.osyntra.osyntrainventorymanagement.repositories;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

@Repository
public class MvpStore {
    private final JdbcClient jdbc;

    public MvpStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int update(String sql, Object... args) {
        return jdbc.sql(sql).params(args).update();
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query((rs, row) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                result.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
            return result;
        }).list();
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        return rows.getFirst();
    }

    public long number(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(Long.class).single();
    }

    public static int integer(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    public static String string(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }
}
