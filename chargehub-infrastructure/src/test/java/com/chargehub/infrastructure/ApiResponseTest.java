package com.chargehub.infrastructure;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {
    @Test void preservesLargeDatabaseIdsAsStringsAndMoneyAsNumbers() {
        var response = ApiResponse.ok(Map.of("id", 9007199254740993L, "user_id", 42L, "amount_fen", 10000L));
        var data = (Map<?, ?>) response.data();
        assertEquals("9007199254740993", data.get("id"));
        assertEquals("42", data.get("userId"));
        assertEquals(10000L, data.get("amountFen"));
        var actors=(Map<?, ?>)ApiResponse.sanitize(Map.of("requested_by",9007199254740993L,"decided_by",0L));
        assertEquals("9007199254740993",actors.get("requestedBy"));
        assertEquals("0",actors.get("decidedBy"));
    }
    @Test void javaTimeSnapshotRoundTripsAsUtcString() {
        String json = Json.write(Map.of("at", Instant.parse("2026-10-18T07:00:00Z")));
        assertEquals("2026-10-18T07:00:00Z", Json.read(json).get("at"));
    }
}
