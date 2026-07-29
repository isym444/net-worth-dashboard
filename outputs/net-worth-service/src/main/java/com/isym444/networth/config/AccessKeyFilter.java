package com.isym444.networth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires header X-Api-Key to match DASHBOARD_ACCESS_KEY on every /api/**
 * request. No-ops entirely when DASHBOARD_ACCESS_KEY is blank/unset, so
 * local dev (docker-compose, no key configured) is unaffected. Exempts
 * CORS preflight (OPTIONS) requests and /actuator/**.
 *
 * Uses getServletPath() (decoded/normalized), not getRequestURI() (raw) —
 * the raw path lets a percent-encoded request (e.g. /%61pi/v1/history)
 * bypass a startsWith("/api/") check while Spring MVC still routes the
 * decoded path to the real controller.
 */
@Component
public class AccessKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessKeyFilter.class);

    @Value("${networth.dashboard.access-key}")
    private String accessKey;

    @Override
    protected void initFilterBean() {
        if (accessKey == null || accessKey.isBlank()) {
            log.warn("DASHBOARD_ACCESS_KEY is not set — /api/** is UNPROTECTED. "
                    + "This is expected for local dev only; set it before deploying publicly.");
        }
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean exempt = path.startsWith("/actuator/") || "OPTIONS".equalsIgnoreCase(request.getMethod());

        if (accessKey == null || accessKey.isBlank() || exempt) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader("X-Api-Key");
        if (providedKey == null || !constantTimeEquals(accessKey, providedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid X-Api-Key header\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
