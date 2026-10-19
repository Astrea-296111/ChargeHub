package com.chargehub.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BearerTokenFilterTest {
    @Test void dedicatedMonitorCannotUseItsOpsRoleOnBusinessEndpoints() throws Exception {
        var tokens = mock(TokenService.class);
        String token = "a".repeat(43);
        when(tokens.resolve(token)).thenReturn(new UserIdentity(99, "__monitor__", Set.of("OPS", "ADMIN")));
        for (String path : new String[]{"/v1/stations", "/v1/admin/users", "/v1/admin/reconciliations"}) {
            SecurityContextHolder.clearContext();
            var request = new MockHttpServletRequest("GET", path);
            request.addHeader("Authorization", "Bearer " + token);
            var response = new MockHttpServletResponse();
            new BearerTokenFilter(tokens).doFilter(request, response, (input, output) -> fail("Monitor escaped scope"));
            assertEquals(403, response.getStatus());
        }
        SecurityContextHolder.clearContext();
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + token);
        var reached = new AtomicBoolean();
        try {
            new BearerTokenFilter(tokens).doFilter(request, new MockHttpServletResponse(), (input, output) -> reached.set(true));
            assertTrue(reached.get());
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void ambiguousAuthorizationHeadersAreRejectedBeforeDatabaseLookup() throws Exception {
        var tokens = mock(TokenService.class);
        var request = new MockHttpServletRequest("GET", "/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + "a".repeat(43));
        request.addHeader("Authorization", "Bearer " + "b".repeat(43));
        var response = new MockHttpServletResponse();
        new BearerTokenFilter(tokens).doFilter(request, response, (input, output) -> fail("Ambiguous identity passed"));
        assertEquals(400, response.getStatus());
        verifyNoInteractions(tokens);
    }
}
