package org.osyntra.osyntrainventorymanagement.services;

import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Small RFC 4180 reader: quoted commas, escaped quotes and CRLF are preserved.
 */
public final class CsvSupport {
    private CsvSupport() {
    }

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else field.append(c);
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(field.toString());
                field.setLength(0);
                closed = false;
                if (c != ',') {
                    if (!(row.size() == 1 && row.getFirst().isBlank())) rows.add(row);
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
            } else if (c == '"' && field.isEmpty() && !closed) quoted = true;
            else {
                if (closed || c == '"') invalid();
                field.append(c);
            }
        }
        if (quoted) invalid();
        if (!field.isEmpty() || closed || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        if (rows.size() > 1001)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 1000 CSV data rows are supported");
        return rows;
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed CSV quoting");
    }

    public static String cell(Object value) {
        String text = Objects.toString(value, "");
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
