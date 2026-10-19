package com.chargehub.identity;

import com.chargehub.infrastructure.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;

final class BearerTokenFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    BearerTokenFilter(TokenService tokens) { this.tokens = tokens; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String token = token(request);
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                var user = tokens.resolve(token);
                if (user == null) throw new BusinessException(401, "UNAUTHENTICATED", "令牌无效或已过期");
                if ("__monitor__".equalsIgnoreCase(user.username()) && !monitorPath(request))
                    throw new BusinessException(403, "MONITOR_SCOPE_REQUIRED", "监控身份只能读取指标或管理自己的会话");
                var authorities = user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
            }
        } catch (BusinessException exception) {
            error(response, exception.status(), exception.code(), exception.getMessage());
            return;
        } catch (DataAccessException exception) {
            error(response, 503, "AUTH_UNAVAILABLE", "认证服务暂时不可用");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean monitorPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod()) && (path.equals("/actuator/prometheus") || path.equals("/v1/auth/me"))
                || "POST".equals(request.getMethod()) && path.equals("/v1/auth/logout");
    }

    static String token(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) return null;
        if (headers.size() != 1) throw new BusinessException(400, "INVALID_AUTHORIZATION", "认证头格式不正确");
        String header = headers.getFirst();
        if (!header.matches("(?i:Bearer) [A-Za-z0-9_-]{43}"))
            throw new BusinessException(401, "UNAUTHENTICATED", "认证头格式不正确");
        return header.substring(7);
    }

    private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.write(ApiResponse.error(code, message)));
    }
}
