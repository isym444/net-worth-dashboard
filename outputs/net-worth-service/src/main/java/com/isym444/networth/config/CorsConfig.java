package com.isym444.networth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Angular frontend's origin to call /api/**. Origin(s) come from
 * FRONTEND_ORIGIN (comma-separated if more than one), defaulting to the
 * local `ng serve` dev origin.
 */
@Configuration
public class CorsConfig {

    @Value("${networth.cors.frontend-origin}")
    private String frontendOrigin;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        String[] origins = frontendOrigin.split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST")
                        .allowedHeaders("X-Api-Key", "Content-Type")
                        .maxAge(3600);
            }
        };
    }
}
