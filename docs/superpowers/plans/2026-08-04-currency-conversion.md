# Multi-Currency Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert each new snapshot's `grand_total` into EUR, USD, MXN, and JPY via fxratesapi.com, storing the results in the four production columns, per `docs/superpowers/specs/2026-08-04-currency-conversion-design.md`.

**Architecture:** A new `ExchangeRateClient` (matching the existing `Trading212Client`/`CryptoComClient` pattern: `@Component`, Spring `RestClient`) fetches rates. `NetWorthSnapshotService.takeSnapshot()` calls it once `grandTotal` is computed for a currency, before that row is saved, and sets four new nullable entity fields. Failure is swallowed (logged at `WARN`, fields left `null`) — this is the one part of the method that doesn't abort the snapshot on error, unlike the Trading212/Crypto.com calls earlier in the same method.

**Tech Stack:** Spring Boot / Spring `RestClient` (no new dependency), Jackson `JsonNode`.

## Global Constraints

- The database migration (column rename `grand_total_MXP` → `grand_total_MXN`, retype all four to `double precision`) is **already complete** on production — confirmed via `information_schema.columns`. Do not attempt it again.
- Column names are quoted mixed-case in Postgres (`"grand_total_EUR"`, `"grand_total_USD"`, `"grand_total_MXN"`, `"grand_total_JPY"`) — `@Column(name = ...)` must use JPA/Hibernate backtick-quoting syntax (a literal backtick character at the start and end of the name string) or Hibernate will emit unquoted SQL that Postgres folds to lowercase, breaking at runtime with a "column does not exist" error that `mvn compile` cannot catch.
- No new HTTP client dependency — use Spring's `RestClient`, matching `Trading212Client`/`CryptoComClient`.
- A failed exchange-rate call must never prevent the snapshot's other data (`grand_total`, Trading212/Crypto.com totals, pension/Barclays carry-forward) from saving — catch and log at `WARN`, leave the four new fields `null`.
- `ChartService.java` is not touched — these values never appear on the chart.
- No frontend changes — this data is not displayed anywhere in the UI.

---

### Task 1: Backend currency conversion

**Files:**
- Create: `net-worth-service/src/main/java/com/isym444/networth/client/ExchangeRateClient.java`
- Modify: `net-worth-service/src/main/java/com/isym444/networth/model/NetWorthSnapshot.java`
- Modify: `net-worth-service/src/main/java/com/isym444/networth/service/NetWorthSnapshotService.java`
- Modify: `net-worth-service/src/main/resources/application.yml`
- Modify: `render.yaml`
- Modify: `net-worth-service/docker-compose.yml`

**Interfaces:**
- Produces: `ExchangeRateClient.fetchRates(String baseCurrency, String targetCurrencies, String apiKey): JsonNode` — throws `IllegalStateException` on a null or `"success": false` response, matching `CryptoComClient.get()`'s error-handling shape.
- Produces: `NetWorthSnapshot.getGrandTotalEur()/setGrandTotalEur(Double)`, `getGrandTotalUsd()/setGrandTotalUsd(Double)`, `getGrandTotalMxn()/setGrandTotalMxn(Double)`, `getGrandTotalJpy()/setGrandTotalJpy(Double)` — consumed by JSON serialization (field names `grandTotalEur` etc. in the API response), though no frontend change consumes them in this plan.
- Consumes: `NetWorthSnapshotService`'s existing `round2(double): double` helper and `repository` field — no changes to either.
- `NetWorthSnapshotService.takeSnapshot()`'s public signature and existing behavior (mandatory Trading212/Crypto.com sources, pension/Barclays carry-forward) are unchanged — this task only adds new logic after `grandTotal` is computed, before `repository.save(snapshot)`.

- [ ] **Step 1: Create `ExchangeRateClient.java`**

```java
package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExchangeRateClient {

    private static final String BASE_URL = "https://api.fxratesapi.com/latest";

    private final RestClient restClient = RestClient.create();

    public JsonNode fetchRates(String baseCurrency, String targetCurrencies, String apiKey) {
        String uri = BASE_URL
                + "?base=" + baseCurrency
                + "&currencies=" + targetCurrencies
                + "&resolution=1m&amount=1&places=6&format=json"
                + "&api_key=" + apiKey;

        JsonNode response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.path("success").asBoolean(false)) {
            String error = response == null ? "empty response" : response.toString();
            throw new IllegalStateException("Exchange rate API error: " + error);
        }
        return response;
    }
}
```

- [ ] **Step 2: Edit `NetWorthSnapshot.java`** — add four fields after `grandTotal`'s field declaration and getter/setter

Find:
```java
    @Column(name = "grand_total", nullable = false)
    private Double grandTotal;

    public Long getId() {
```

Replace with:
```java
    @Column(name = "grand_total", nullable = false)
    private Double grandTotal;

    @Column(name = "`grand_total_EUR`")
    private Double grandTotalEur;

    @Column(name = "`grand_total_USD`")
    private Double grandTotalUsd;

    @Column(name = "`grand_total_MXN`")
    private Double grandTotalMxn;

    @Column(name = "`grand_total_JPY`")
    private Double grandTotalJpy;

    public Long getId() {
```

Find:
```java
    public Double getGrandTotal() {
        return grandTotal;
    }

    public void setGrandTotal(Double grandTotal) {
        this.grandTotal = grandTotal;
    }
}
```

Replace with:
```java
    public Double getGrandTotal() {
        return grandTotal;
    }

    public void setGrandTotal(Double grandTotal) {
        this.grandTotal = grandTotal;
    }

    public Double getGrandTotalEur() {
        return grandTotalEur;
    }

    public void setGrandTotalEur(Double grandTotalEur) {
        this.grandTotalEur = grandTotalEur;
    }

    public Double getGrandTotalUsd() {
        return grandTotalUsd;
    }

    public void setGrandTotalUsd(Double grandTotalUsd) {
        this.grandTotalUsd = grandTotalUsd;
    }

    public Double getGrandTotalMxn() {
        return grandTotalMxn;
    }

    public void setGrandTotalMxn(Double grandTotalMxn) {
        this.grandTotalMxn = grandTotalMxn;
    }

    public Double getGrandTotalJpy() {
        return grandTotalJpy;
    }

    public void setGrandTotalJpy(Double grandTotalJpy) {
        this.grandTotalJpy = grandTotalJpy;
    }
}
```

- [ ] **Step 3: Edit `NetWorthSnapshotService.java`**

Add the import, the client dependency, the API key field, and the target-currencies constant. Find:
```java
import com.fasterxml.jackson.databind.JsonNode;
import com.isym444.networth.client.CryptoComClient;
import com.isym444.networth.client.Trading212Client;
```

Replace with:
```java
import com.fasterxml.jackson.databind.JsonNode;
import com.isym444.networth.client.CryptoComClient;
import com.isym444.networth.client.ExchangeRateClient;
import com.isym444.networth.client.Trading212Client;
```

Find:
```java
    private final Trading212Client trading212Client;
    private final CryptoComClient cryptoComClient;
    private final NetWorthSnapshotRepository repository;
```

Replace with:
```java
    private static final String CONVERSION_TARGET_CURRENCIES = "EUR,USD,MXN,JPY";

    private final Trading212Client trading212Client;
    private final CryptoComClient cryptoComClient;
    private final ExchangeRateClient exchangeRateClient;
    private final NetWorthSnapshotRepository repository;
```

Find:
```java
    @Value("${networth.cryptocom.api-key}")
    private String cryptoComApiKey;

    @Value("${networth.cryptocom.api-secret}")
    private String cryptoComApiSecret;

    public NetWorthSnapshotService(Trading212Client trading212Client,
                                    CryptoComClient cryptoComClient,
                                    NetWorthSnapshotRepository repository) {
        this.trading212Client = trading212Client;
        this.cryptoComClient = cryptoComClient;
        this.repository = repository;
    }
```

Replace with:
```java
    @Value("${networth.cryptocom.api-key}")
    private String cryptoComApiKey;

    @Value("${networth.cryptocom.api-secret}")
    private String cryptoComApiSecret;

    @Value("${networth.exchange-rate.api-key}")
    private String exchangeRateApiKey;

    public NetWorthSnapshotService(Trading212Client trading212Client,
                                    CryptoComClient cryptoComClient,
                                    ExchangeRateClient exchangeRateClient,
                                    NetWorthSnapshotRepository repository) {
        this.trading212Client = trading212Client;
        this.cryptoComClient = cryptoComClient;
        this.exchangeRateClient = exchangeRateClient;
        this.repository = repository;
    }
```

Now wire the conversion into the per-currency loop. Find:
```java
            double pensionTotal = previous != null && previous.getPensionTotal() != null
                    ? previous.getPensionTotal() : 0.0;
            double barclaysSharesTotal = previous != null && previous.getBarclaysSharesTotal() != null
                    ? previous.getBarclaysSharesTotal() : 0.0;

            NetWorthSnapshot snapshot = new NetWorthSnapshot();
            snapshot.setTimestamp(now);
            snapshot.setCurrency(currency);
            snapshot.setT212Total(round2(t212Total));
            snapshot.setCryptoTotal(round2(cryptoTotal));
            snapshot.setPensionTotal(round2(pensionTotal));
            snapshot.setBarclaysSharesTotal(round2(barclaysSharesTotal));
            snapshot.setGrandTotal(round2(t212Total + cryptoTotal + pensionTotal + barclaysSharesTotal));
            rows.add(repository.save(snapshot));
```

Replace with:
```java
            double pensionTotal = previous != null && previous.getPensionTotal() != null
                    ? previous.getPensionTotal() : 0.0;
            double barclaysSharesTotal = previous != null && previous.getBarclaysSharesTotal() != null
                    ? previous.getBarclaysSharesTotal() : 0.0;
            double grandTotal = round2(t212Total + cryptoTotal + pensionTotal + barclaysSharesTotal);

            CurrencyConversions conversions = fetchCurrencyConversions(currency, grandTotal);

            NetWorthSnapshot snapshot = new NetWorthSnapshot();
            snapshot.setTimestamp(now);
            snapshot.setCurrency(currency);
            snapshot.setT212Total(round2(t212Total));
            snapshot.setCryptoTotal(round2(cryptoTotal));
            snapshot.setPensionTotal(round2(pensionTotal));
            snapshot.setBarclaysSharesTotal(round2(barclaysSharesTotal));
            snapshot.setGrandTotal(grandTotal);
            snapshot.setGrandTotalEur(conversions.eur());
            snapshot.setGrandTotalUsd(conversions.usd());
            snapshot.setGrandTotalMxn(conversions.mxn());
            snapshot.setGrandTotalJpy(conversions.jpy());
            rows.add(repository.save(snapshot));
```

Finally, add the two new private helpers and the record. Find:
```java
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
```

Replace with:
```java
    /**
     * Converts a snapshot's grand total into EUR/USD/MXN/JPY via a live exchange-rate
     * call. Deliberately never throws: a failure here (network error, API error, or a
     * blank API key) leaves all four conversions null and logs a warning, rather than
     * blocking the snapshot's core data from saving — unlike the Trading212/Crypto.com
     * calls above, these conversions are auxiliary, not core to grand_total or the chart.
     */
    private CurrencyConversions fetchCurrencyConversions(String baseCurrency, double grandTotal) {
        if (exchangeRateApiKey == null || exchangeRateApiKey.isBlank()) {
            log.warn("Exchange rate conversion skipped (no API key configured)");
            return CurrencyConversions.EMPTY;
        }
        try {
            JsonNode response = exchangeRateClient.fetchRates(baseCurrency, CONVERSION_TARGET_CURRENCIES, exchangeRateApiKey);
            JsonNode rates = response.path("rates");
            return new CurrencyConversions(
                    convert(rates, "EUR", grandTotal),
                    convert(rates, "USD", grandTotal),
                    convert(rates, "MXN", grandTotal),
                    convert(rates, "JPY", grandTotal));
        } catch (Exception e) {
            log.warn("Exchange rate conversion failed for currency={}, leaving converted totals null", baseCurrency, e);
            return CurrencyConversions.EMPTY;
        }
    }

    private Double convert(JsonNode rates, String code, double grandTotal) {
        if (!rates.has(code)) {
            return null;
        }
        return round2(grandTotal * rates.path(code).asDouble());
    }

    private record CurrencyConversions(Double eur, Double usd, Double mxn, Double jpy) {
        static final CurrencyConversions EMPTY = new CurrencyConversions(null, null, null, null);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
```

- [ ] **Step 4: Edit `application.yml`** — add the new credential entry

Find:
```yaml
  cryptocom:
    api-key: ${CRYPTO_COM_KEY:}
    api-secret: ${CRYPTO_COM_SECRET:}
```

Replace with:
```yaml
  cryptocom:
    api-key: ${CRYPTO_COM_KEY:}
    api-secret: ${CRYPTO_COM_SECRET:}
  exchange-rate:
    api-key: ${EXCHANGE_RATE_API_KEY:}
```

- [ ] **Step 5: Edit `render.yaml`** — add the new env var to the `net-worth-service` block

Find:
```yaml
      - key: DASHBOARD_ACCESS_KEY
        sync: false
      - key: FRONTEND_ORIGIN
        value: https://net-worth-frontend-nt7d.onrender.com
```

Replace with:
```yaml
      - key: DASHBOARD_ACCESS_KEY
        sync: false
      - key: EXCHANGE_RATE_API_KEY
        sync: false
      - key: FRONTEND_ORIGIN
        value: https://net-worth-frontend-nt7d.onrender.com
```

- [ ] **Step 6: Edit `net-worth-service/docker-compose.yml`** — add the passthrough var

Find:
```yaml
      DASHBOARD_ACCESS_KEY: ${DASHBOARD_ACCESS_KEY:-}
      FRONTEND_ORIGIN: ${FRONTEND_ORIGIN:-http://localhost:4200}
```

Replace with:
```yaml
      DASHBOARD_ACCESS_KEY: ${DASHBOARD_ACCESS_KEY:-}
      EXCHANGE_RATE_API_KEY: ${EXCHANGE_RATE_API_KEY:-}
      FRONTEND_ORIGIN: ${FRONTEND_ORIGIN:-http://localhost:4200}
```

- [ ] **Step 7: Verify the service compiles**

Run: `cd net-worth-service && mvn -q clean compile`
Expected: `BUILD SUCCESS`, no errors. This is the step most likely to catch a backtick-quoting or record-syntax mistake.

- [ ] **Step 8: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/client/ExchangeRateClient.java \
        net-worth-service/src/main/java/com/isym444/networth/model/NetWorthSnapshot.java \
        net-worth-service/src/main/java/com/isym444/networth/service/NetWorthSnapshotService.java \
        net-worth-service/src/main/resources/application.yml \
        render.yaml \
        net-worth-service/docker-compose.yml
git commit -m "Convert grand total to EUR/USD/MXN/JPY on each snapshot"
```

---

### Task 2: Manual verification

**Files:** none (verification only)

**Interfaces:** none — exercises the backend built by Task 1. Requires `EXCHANGE_RATE_API_KEY` to already be set in the local `.env` (the user has a real fxratesapi.com key) for the positive-path check, and Docker Desktop to be running.

- [ ] **Step 1: Rebuild and restart the backend**

Run: `cd net-worth-service && docker compose up -d --build app`

- [ ] **Step 2: Take a real snapshot and confirm the four columns populate**

Run: `curl -s -X POST http://localhost:8080/api/v1/snapshot` (this hits real Trading212/Crypto.com/fxratesapi.com APIs and writes a real row — same as clicking "Take snapshot now").
Then: `curl -s http://localhost:8080/api/v1/history | tail -c 500` (or query the DB directly) and confirm the newest row has non-null `grandTotalEur`/`grandTotalUsd`/`grandTotalMxn`/`grandTotalJpy`, each a plausible conversion of `grandTotal` (e.g. `grandTotalUsd` should be roughly `grandTotal * 1.3` for a GBP snapshot, not wildly off).

- [ ] **Step 3: Confirm graceful degradation when the API key is blank**

Temporarily comment out or blank `EXCHANGE_RATE_API_KEY` in `.env`, `docker compose up -d --build app` again, take another snapshot, and confirm: the snapshot still saves successfully (all core fields present), the four new columns are `null` on that row, and the app log shows `Exchange rate conversion skipped (no API key configured)` at `WARN` level. Restore the real key and rebuild afterward.

- [ ] **Step 4: Confirm the chart is unaffected**

`GET /api/v1/chart` and visually confirm it still shows exactly three series (Trading212, Crypto.com, Net worth) — no new lines.
