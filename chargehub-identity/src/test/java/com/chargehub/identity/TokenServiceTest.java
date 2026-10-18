package com.chargehub.identity;

import com.chargehub.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenServiceTest {
    @Test void loginPersistsHashAndLoadsRolesFromServer() {
        var db = mock(Db.class);
        var encoder = new BCryptPasswordEncoder(4);
        when(db.one(startsWith("SELECT id,login_name"), eq("alice"))).thenReturn(Map.of(
                "id", 7L, "login_name", "alice", "password_hash", encoder.encode("secret"), "enabled", 1));
        when(db.rows(startsWith("SELECT role_code"), eq(7L))).thenReturn(List.of(Map.of("role_code", "CUSTOMER")));
        var result = new TokenService(db, encoder).login("alice", "secret", "127.0.0.1");
        String token = result.get("accessToken").toString();
        assertEquals(43, token.length());
        assertEquals(Set.of("CUSTOMER"), result.get("roles"));
        verify(db).update(startsWith("INSERT INTO auth_token"), eq(Json.hash(token)), eq(7L), any());
    }
    @Test void disabledUserCannotLoginEvenWithCorrectPassword() {
        var db = mock(Db.class);
        var encoder = new BCryptPasswordEncoder(4);
        when(db.one(anyString(), eq("alice"))).thenReturn(Map.of(
                "id", 7L, "password_hash", encoder.encode("secret"), "enabled", 0));
        var error = assertThrows(BusinessException.class,
                () -> new TokenService(db, encoder).login("alice", "secret", "127.0.0.1"));
        assertEquals(401, error.status());
        verify(db, never()).update(anyString(), any(), any(), any());
    }
    @Test void malformedBearerNeverReachesDatabase() {
        var db = mock(Db.class);
        var service = new TokenService(db, new BCryptPasswordEncoder(4));
        assertNull(service.resolve("fabricated-token"));
        verifyNoInteractions(db);
    }
}
