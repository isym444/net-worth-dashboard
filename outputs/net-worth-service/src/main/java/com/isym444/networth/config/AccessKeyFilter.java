package com.isym444.networth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires header X-Api-Key to match DASHBOARD_ACCESS_KEY on every /api/**
 * request. No-ops entirely when DASHBOARD_ACCESS_KEY is blank/unset, so
 * local dev (docker-compose, no key configured) is unaffected. Exempts
 * CORS preflight (OPTIONS) requests, which never carry custom headers.
 */
@Component
public class AccessKeyFilter extends OncePerRequestFilter {

    @Value("${networth.dashboard.access-key}")
    private String accessKey;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        boolean protectedPath = request.getRequestURI().startsWith("/api/");
        boolean preflight = "OPTIONS".equalsIgnoreCase(request.getMethod());

        if (accessKey == null || accessKey.isBlank() || !protectedPath || preflight) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader("X-Api-Key");
        if (!accessKey.equals(providedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid X-Api-Key header\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
