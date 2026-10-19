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
        var limiter = mock(LoginAttemptLimiter.class);
        var result = new TokenService(db, encoder, limiter).login("alice", "secret", "127.0.0.1");
        String token = result.get("accessToken").toString();
        assertEquals(43, token.length());
        assertEquals(Set.of("CUSTOMER"), result.get("roles"));
        verify(db).update(startsWith("INSERT INTO auth_token"), eq(Json.hash(token)), eq(7L), any());
        verify(limiter).check("alice", "127.0.0.1");
    }
    @Test void disabledUserCannotLoginEvenWithCorrectPassword() {
        var db = mock(Db.class);
        var encoder = new BCryptPasswordEncoder(4);
        when(db.one(anyString(), eq("alice"))).thenReturn(Map.of(
                "id", 7L, "password_hash", encoder.encode("secret"), "enabled", 0));
        var error = assertThrows(BusinessException.class,
                () -> new TokenService(db, encoder, mock(LoginAttemptLimiter.class)).login("alice", "secret", "127.0.0.1"));
        assertEquals(401, error.status());
        verify(db, never()).update(anyString(), any(), any(), any());
    }
    @Test void malformedBearerNeverReachesDatabase() {
        var db = mock(Db.class);
        var service = new TokenService(db, new BCryptPasswordEncoder(4), mock(LoginAttemptLimiter.class));
        assertNull(service.resolve("fabricated-token"));
        verifyNoInteractions(db);
    }

    @Test void unknownAndDisabledAccountsReturnSameRejection() {
        var encoder = new BCryptPasswordEncoder(4);
        var missing = new TokenService(mock(Db.class), encoder, mock(LoginAttemptLimiter.class));
        var error = assertThrows(BusinessException.class, () -> missing.login("missing", "secret", "127.0.0.1"));
        assertEquals("INVALID_CREDENTIALS", error.code());
        assertEquals("用户名或密码不正确", error.getMessage());
    }

    @Test void rolesAreReloadedForEveryRequestUsingSameToken() {
        var db = mock(Db.class);
        String token = "a".repeat(43);
        when(db.one(contains("t.revoked_at IS NULL"), eq(Json.hash(token))))
                .thenReturn(Map.of("id", 7L, "login_name", "alice"));
        when(db.rows(startsWith("SELECT role_code"), eq(7L)))
                .thenReturn(List.of(Map.of("role_code", "ADMIN")), List.of(Map.of("role_code", "CUSTOMER")));
        var service = new TokenService(db, new BCryptPasswordEncoder(4), mock(LoginAttemptLimiter.class));
        assertTrue(service.resolve(token).roles().contains("ADMIN"));
        assertFalse(service.resolve(token).roles().contains("ADMIN"));
    }
}
