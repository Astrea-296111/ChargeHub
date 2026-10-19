package com.chargehub.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationBodyFilterTest {
    @Test void rawCallbackBodyReachesVerifierWithoutReencoding() throws Exception {
        byte[] raw = "{\"amountFen\": 10000, \"note\":\"签名原文\"}".getBytes(StandardCharsets.UTF_8);
        var request = request("/integrations/payments/mock/notifications", raw);
        var seen = new AtomicReference<byte[]>();
        new AuthenticationBodyFilter().doFilter(request, new MockHttpServletResponse(),
                (wrapped, response) -> seen.set(wrapped.getInputStream().readAllBytes()));
        assertArrayEquals(raw, seen.get());
    }

    @Test void rejectsDuplicateFieldsTrailingObjectsAndArrays() throws Exception {
        for (String body : new String[]{"{\"username\":\"a\",\"username\":\"b\"}", "{}{}", "[]"}) {
            var response = new MockHttpServletResponse();
            new AuthenticationBodyFilter().doFilter(request("/v1/auth/token", body.getBytes(StandardCharsets.UTF_8)), response,
                    (input, output) -> fail("Invalid JSON must not reach authentication"));
            assertEquals(400, response.getStatus());
        }
    }

    @Test void chunkedCallbacksCannotBypassRawSizeLimit() throws Exception {
        var original = request("/integrations/devices/events", new byte[65_537]);
        var request = new jakarta.servlet.http.HttpServletRequestWrapper(original) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        var response = new MockHttpServletResponse();
        new AuthenticationBodyFilter().doFilter(request, response, (input, output) -> fail("Oversized body passed"));
        assertEquals(413, response.getStatus());
    }

    @Test void financeMultipartKeepsSeparateTenMiBContract() throws Exception {
        var request = new MockHttpServletRequest("POST", "/v1/admin/reconciliations");
        request.setContentType("multipart/form-data; boundary=test");
        var seen = new AtomicReference<HttpServletRequest>();
        new AuthenticationBodyFilter().doFilter(request, new MockHttpServletResponse(),
                (input, output) -> seen.set((HttpServletRequest) input));
        assertSame(request, seen.get());
    }

    @Test void normalBusinessWritesAlsoRejectOversizeAndDuplicateJson() throws Exception {
        var response = new MockHttpServletResponse();
        new AuthenticationBodyFilter().doFilter(request("/v1/charge-orders", new byte[65_537]), response,
                (input, output) -> fail("Oversized business JSON passed"));
        assertEquals(413, response.getStatus());
        response = new MockHttpServletResponse();
        new AuthenticationBodyFilter().doFilter(request("/v1/charge-orders", "{\"connectorId\":1,\"connectorId\":2}".getBytes(StandardCharsets.UTF_8)),
                response, (input, output) -> fail("Duplicate business fields passed"));
        assertEquals(400, response.getStatus());
    }

    @Test void existingNoBodyCommandsRemainValid() throws Exception {
        var request = new MockHttpServletRequest("POST", "/v1/charge-orders/order-no/cancel");
        var seen = new AtomicReference<HttpServletRequest>();
        new AuthenticationBodyFilter().doFilter(request, new MockHttpServletResponse(),
                (input, output) -> seen.set((HttpServletRequest) input));
        assertNotNull(seen.get());
    }

    private static MockHttpServletRequest request(String path, byte[] body) {
        var request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
