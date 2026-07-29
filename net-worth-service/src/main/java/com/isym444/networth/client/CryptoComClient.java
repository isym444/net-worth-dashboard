package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Client for the Crypto.com App API (wapi.crypto.com). Requests are signed
 * with HMAC-SHA256 over "{timestamp}{METHOD}{path}{body}", matching the
 * scheme used by Crypto.com's own SDKs.
 */
@Component
public class CryptoComClient {

    private static final String BASE_URL = "https://wapi.crypto.com";

    private final RestClient restClient = RestClient.create();

    public JsonNode get(String path, String apiKey, String apiSecret) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = sign(timestamp + "GET" + path, apiSecret);

        JsonNode response = restClient.get()
                .uri(BASE_URL + path)
                .header("Cdc-Api-Key", apiKey)
                .header("Cdc-Api-Timestamp", timestamp)
                .header("Cdc-Api-Signature", signature)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.path("ok").asBoolean(false)) {
            String error = response == null ? "empty response" : response.toString();
            throw new IllegalStateException("Crypto.com API error for " + path + ": " + error);
        }
        return response;
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign Crypto.com request", e);
        }
    }
}
