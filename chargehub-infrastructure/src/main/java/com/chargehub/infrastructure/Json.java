package com.chargehub.infrastructure;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Jackson 2 is deliberately isolated from Boot's HTTP Jackson 3 mapper. */
public final class Json {
    private static final ObjectMapper MAPPER = mapper();
    private Json() {}

    private static ObjectMapper mapper() {
        var module = new SimpleModule();
        for (Class<?> type : List.of(Instant.class, LocalDate.class, LocalDateTime.class, OffsetDateTime.class,
                ZonedDateTime.class, LocalTime.class)) module.addSerializer(type, ToStringSerializer.instance);
        module.addSerializer(java.sql.Timestamp.class, new JsonSerializer<>() {
            @Override public void serialize(java.sql.Timestamp value, JsonGenerator generator, SerializerProvider provider)
                    throws java.io.IOException { generator.writeString(value.toInstant().toString()); }
        });
        return new ObjectMapper().registerModule(module).setTimeZone(TimeZone.getTimeZone("UTC"))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize data", e); }
    }

    public static Map<String, Object> read(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try { return MAPPER.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new BusinessException(400, "INVALID_JSON", "请求内容不是有效 JSON 对象"); }
    }

    public static List<Map<String, Object>> list(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        try { return MAPPER.readValue(value, new TypeReference<ArrayList<Map<String, Object>>>() {}); }
        catch (Exception e) { throw new BusinessException(400, "INVALID_JSON", "请求内容不是有效 JSON 数组"); }
    }

    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
