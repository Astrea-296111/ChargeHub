package com.chargehub.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Small parameterized JDBC boundary; all services participate in Spring transactions. */
@Component
public class Db {
    private final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, parameters(args));
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int update(String sql, Object... args) { return jdbc.update(sql, parameters(args)); }

    public long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        var values = parameters(args);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, holder);
        var key = holder.getKey();
        if (key == null) throw new IllegalStateException("Insert did not produce an ID");
        return key.longValue();
    }

    private static Object[] parameters(Object[] values) {
        return Arrays.stream(values).map(value -> value instanceof Instant instant ? Timestamp.from(instant)
                : value instanceof Enum<?> e ? e.name() : value).toArray();
    }

    private static Object value(Map<String, Object> row, String key) {
        if (row == null) return null;
        if (row.containsKey(key)) return row.get(key);
        return row.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public static String str(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value == null) return null;
        if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return value.toString();
    }

    public static long num(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? 0 : value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    public static Instant instant(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        return Instant.parse(value.toString());
    }
}
