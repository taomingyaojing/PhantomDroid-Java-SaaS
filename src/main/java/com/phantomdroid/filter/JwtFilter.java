package com.phantomdroid.filter;

import com.phantomdroid.util.JwtUtil;
import com.phantomdroid.util.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Lightweight JWT authentication filter.
 * Whitelist: /api/auth/** (login/register)
 * Protect: /api/device/** (all device operations)
 * Reads Authorization: Bearer {token}, extracts userId into ThreadLocal UserContext.
 *
 * No Spring Security dependency — pure Servlet Filter.
 */
@Component
@Order(1)
public class JwtFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_PATH_PREFIX = "/api/auth/";
    private static final String DEVICE_PATH_PREFIX = "/api/device/";
    private static final String STATUS_PATH = "/api/device/status";

    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String path = request.getRequestURI();

        // Whitelist: auth endpoints (login/register)
        if (path.startsWith(AUTH_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // Only intercept /api/device/** paths
        if (!path.startsWith(DEVICE_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // Extract Authorization header
        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            sendUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        Claims claims = jwtUtil.validateToken(token);
        if (claims == null) {
            sendUnauthorized(response, "Token expired, invalid, or malformed");
            return;
        }

        Long userId = jwtUtil.getUserIdFromClaims(claims);
        String username = jwtUtil.getUsernameFromClaims(claims);

        // Set ThreadLocal context for the duration of this request
        UserContext.set(userId, username);

        try {
            chain.doFilter(request, response);
        } finally {
            // Always clear ThreadLocal, prevent memory leak
            UserContext.clear();
        }
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
    }
}
