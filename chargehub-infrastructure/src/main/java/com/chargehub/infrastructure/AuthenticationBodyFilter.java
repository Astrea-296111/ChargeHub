package com.chargehub.infrastructure;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.Locale;

/** Bound raw authentication/callback bytes before MVC allocates or deserializes request bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthenticationBodyFilter extends OncePerRequestFilter {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean integration = path.startsWith("/integrations/");
        boolean identity = path.equals("/v1/auth/logout") || path.matches("/v1/admin/users/[^/]+/(roles|tokens/revoke)");
        boolean login = path.equals("/v1/auth/token");
        if ((!integration && !path.startsWith("/v1/")) || !request.getMethod().matches("POST|PUT|PATCH|DELETE")) {
            chain.doFilter(request, response);
            return;
        }
        String type = request.getContentType();
        if (path.equals("/v1/admin/reconciliations") && request.getMethod().equals("POST")
                && type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            // Servlet multipart limits retain the CSV file10MiB / total11MiB contract.
            chain.doFilter(request, response);
            return;
        }
        int max = login ? 4096 : identity ? 16_384 : 65_536;
        if (request.getContentLengthLong() > max) {
            error(response, 413, "BODY_TOO_LARGE", "请求内容超过大小限制");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            error(response, 413, "BODY_TOO_LARGE", "请求内容超过大小限制");
            return;
        }
        if (body.length == 0 && !integration && !login && !identity) {
            // Existing no-body commands remain valid. DTO endpoints still enforce required bodies in MVC.
            chain.doFilter(new BufferedRequest(request, body), response);
            return;
        }
        if (type == null || !type.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
            error(response, 415, "UNSUPPORTED_MEDIA_TYPE", "请求必须使用 application/json");
            return;
        }
        try {
            JsonNode value = JSON.readTree(body);
            if (value == null || !value.isObject()) throw new IllegalArgumentException();
        } catch (Exception exception) {
            error(response, 400, "INVALID_JSON", "请求必须是无重复字段的 JSON 对象");
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.write(ApiResponse.error(code, message)));
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            var stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return stream.read(); }
                @Override public int read(byte[] data, int offset, int length) { return stream.read(data, offset, length); }
                @Override public boolean isFinished() { return stream.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous request body only");
                }
            };
        }
    }
}
