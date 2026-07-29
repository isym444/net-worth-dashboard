package com.isym444.networth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.net.URISyntaxException;

@SpringBootApplication
@EnableScheduling
public class NetWorthServiceApplication {

    public static void main(String[] args) {
        configureDatabaseUrlIfPresent();
        SpringApplication.run(NetWorthServiceApplication.class, args);
    }

    /**
     * Render (and Heroku-style platforms) commonly expose a Postgres
     * connection as a single DATABASE_URL in "postgres://user:pass@host:port/db"
     * form. Spring needs a JDBC URL, so translate it here before startup —
     * unless SPRING_DATASOURCE_URL was set explicitly, which always wins.
     */
    private static void configureDatabaseUrlIfPresent() {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }
        if (System.getenv("SPRING_DATASOURCE_URL") != null) {
            return;
        }
        try {
            URI uri = new URI(databaseUrl);
            String username = "";
            String password = "";
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                username = parts[0];
                password = parts.length > 1 ? parts[1] : "";
            }
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath();
            System.setProperty("spring.datasource.url", jdbcUrl);
            System.setProperty("spring.datasource.username", username);
            System.setProperty("spring.datasource.password", password);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid DATABASE_URL", e);
        }
    }
}
