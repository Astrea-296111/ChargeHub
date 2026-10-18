package com.chargehub.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HmacTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final byte[] BODY = "{\"amountFen\":10000}".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = "/integrations/payments/notifications";
    private static final String TIMESTAMP = "1792306800";
    private static final String NONCE = "00000000-0000-4000-8000-000000000001";

    @Test void canonicalSignatureMatchesIndependentVector() {
        assertEquals("iogCbiYIUpOiU/9sxGwy9P+s5HPfSldZ72utjLM9jrk=", Base64.getEncoder().encodeToString(
                Hmac.sign("post", PATH, TIMESTAMP, NONCE, BODY, SECRET)));
    }

    @Test void acceptsValidSignatureAndStoresNonce() {
        var db = mock(Db.class);
        var hmac = hmac(db);
        hmac.verify(request("iogCbiYIUpOiU/9sxGwy9P+s5HPfSldZ72utjLM9jrk=", TIMESTAMP), BODY, "payment");
        verify(db).update(startsWith("INSERT INTO integration_nonce"), eq("payment"), eq(NONCE), any(Instant.class));
    }

    @Test void rejectsTamperedBodyBeforeRecordingNonce() {
        var db = mock(Db.class);
        var error = assertThrows(BusinessException.class, () -> hmac(db).verify(
                request("iogCbiYIUpOiU/9sxGwy9P+s5HPfSldZ72utjLM9jrk=", TIMESTAMP), new byte[0], "payment"));
        assertEquals(401, error.status());
        verifyNoInteractions(db);
    }

    @Test void rejectsReplayUsingPersistentUniqueConstraint() {
        var db = mock(Db.class);
        when(db.update(anyString(), any(), any(), any())).thenThrow(new DuplicateKeyException("duplicate"));
        var error = assertThrows(BusinessException.class, () -> hmac(db).verify(
                request("iogCbiYIUpOiU/9sxGwy9P+s5HPfSldZ72utjLM9jrk=", TIMESTAMP), BODY, "payment"));
        assertEquals("REPLAY_DETECTED", error.code());
    }

    @Test void rejectsExpiredSignatureAndWrongKeyOwnership() {
        var db = mock(Db.class);
        assertThrows(BusinessException.class, () -> hmac(db).verify(request("unused", "1792306400"), BODY, "payment"));
        assertThrows(BusinessException.class, () -> hmac(db).verify(request("unused", TIMESTAMP), BODY, "device"));
        verifyNoInteractions(db);
    }

    private Hmac hmac(Db db) {
        var environment = mock(Environment.class);
        when(environment.getProperty("chargehub.payment-key-id", "payment")).thenReturn("payment");
        when(environment.getProperty("chargehub.payment-key")).thenReturn(SECRET);
        return new Hmac(db, environment, Clock.fixed(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)), ZoneOffset.UTC));
    }

    private HttpServletRequest request(String signature, String timestamp) {
        var request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn(PATH);
        when(request.getHeader("X-Key-Id")).thenReturn("payment");
        when(request.getHeader("X-Timestamp")).thenReturn(timestamp);
        when(request.getHeader("X-Nonce")).thenReturn(NONCE);
        when(request.getHeader("X-Signature")).thenReturn(signature);
        return request;
    }
}
