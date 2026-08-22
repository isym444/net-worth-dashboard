# Amex AI Spending Insights Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Gemini-backed endpoint that analyzes recent Amex transactions and persists the commentary onto the net worth snapshot history, plus a dashboard button to trigger it on demand.

**Architecture:** A new `GeminiClient` (RestClient-based, mirrors `ExchangeRateClient`) calls Google's Gemini `generateContent` API. A new `AmexInsightService` builds the prompt from `YnabTransactionService`'s existing transaction fetch and calls `GeminiClient`. `NetWorthSnapshotService.takeSnapshot()` gains a `regenerateCommentary` flag so the cron job always calls the LLM fresh while every other snapshot event copies the previous row's commentary forward. A new `POST /api/v1/amex/insights` endpoint calls the LLM independently and overwrites `ai_commentary` on the current latest row per currency, without creating a new snapshot. The Angular dashboard gets a new card with a "Refresh insights" button.

**Tech Stack:** Java 21 / Spring Boot 3.3 (Spring Web `RestClient`, Data JPA, Scheduling), Postgres via `ddl-auto: update`, Angular 18+ (standalone components, signals), Google Gemini `generateContent` REST API.

**Spec:** `docs/superpowers/specs/2026-08-22-amex-ai-insights-design.md`

## Global Constraints

- Java 21 / Spring Boot 3.3. Postgres schema changes go through `ddl-auto: update` — never write a manual migration for this project.
- No automated test suite exists in this codebase (neither Java nor Angular — confirmed absent, consistent with every prior spec in `docs/superpowers/specs/`). Per-task verification is a compile/build check (`mvn compile` / `npm run build`), not a test run. A full manual end-to-end pass is the final task.
- Never log a raw exception or request URI from `GeminiClient` — the API key is a query parameter, and default SLF4J/Logback exception logging would print it verbatim. Log a generic message only (mirrors `ExchangeRateClient.java:44-49`).
- LLM commentary must be plain text (no markdown syntax) — the frontend has no markdown renderer, and this is deliberately out of scope.
- Local dev's Postgres (via `docker compose up` in `net-worth-service/`) is a **shared Supabase instance** when `SPRING_DATASOURCE_URL` is set in the repo-root `.env` (it is, for this project) — always run `docker compose stop` after a manual test session in Task 11 to avoid leaking idle connections against a low-connection-budget shared DB.
- Default Gemini model: `gemini-2.5-flash`, overridable via `GEMINI_MODEL`.

---

### Task 1: Backend configuration — Gemini API key and model

**Files:**
- Modify: `net-worth-service/src/main/resources/application.yml`
- Modify: `net-worth-service/docker-compose.yml`
- Modify: `render.yaml`
- Modify: `net-worth-service/README.md`
- Modify (manual, local-only, gitignored): `.env`

**Interfaces:**
- Produces: Spring properties `networth.llm.gemini.api-key` and `networth.llm.gemini.model`, resolvable via `@Value("${networth.llm.gemini.api-key}")` / `@Value("${networth.llm.gemini.model}")` in later tasks.

- [ ] **Step 1: Add the new property block to `application.yml`**

In `net-worth-service/src/main/resources/application.yml`, add a new `llm` block under the existing `networth:` section (after the `exchange-rate` block at the end of that section):

```yaml
  exchange-rate:
    api-key: ${EXCHANGE_RATE_API_KEY:}
  llm:
    gemini:
      api-key: ${GEMINI_API_KEY:}
      model: ${GEMINI_MODEL:gemini-2.5-flash}
```

- [ ] **Step 2: Verify the YAML was added correctly**

Run: `grep -n "GEMINI\|networth.llm\|llm:" -r net-worth-service/src/main/resources/application.yml`
Expected: shows the new `llm:`, `gemini:`, `api-key:`, and `model:` lines nested correctly under `networth:`.

- [ ] **Step 3: Add the env var passthrough to `docker-compose.yml`**

In `net-worth-service/docker-compose.yml`, add two lines to the `app.environment` block (after the `EXCHANGE_RATE_API_KEY` line):

```yaml
      EXCHANGE_RATE_API_KEY: ${EXCHANGE_RATE_API_KEY:-}
      GEMINI_API_KEY: ${GEMINI_API_KEY:-}
      GEMINI_MODEL: ${GEMINI_MODEL:-gemini-2.5-flash}
      FRONTEND_ORIGIN: ${FRONTEND_ORIGIN:-http://localhost:4200}
```

(The `EXCHANGE_RATE_API_KEY` and `FRONTEND_ORIGIN` lines already exist — just insert the two new `GEMINI_*` lines between them, in that position, so the file still ends with `FRONTEND_ORIGIN` last as before.)

- [ ] **Step 4: Add the Render env vars to `render.yaml`**

In the repo-root `render.yaml`, under `services[0].envVars` (the `net-worth-service` block), add two entries after `EXCHANGE_RATE_API_KEY`:

```yaml
      - key: EXCHANGE_RATE_API_KEY
        sync: false
      - key: GEMINI_API_KEY
        sync: false
      - key: GEMINI_MODEL
        value: gemini-2.5-flash
      - key: FRONTEND_ORIGIN
        value: https://net-worth-frontend-nt7d.onrender.com
```

- [ ] **Step 5: Update `net-worth-service/README.md`'s configuration table**

Add a new row to the environment variable table (after the `EXCHANGE_RATE_API_KEY` row is not present today — add after the `DASHBOARD_ACCESS_KEY` row and before `FRONTEND_ORIGIN`):

```markdown
| `GEMINI_API_KEY` | no | Google Gemini API key, used to generate Amex spending insights (`POST /api/v1/amex/insights` and the daily cron snapshot). Get one at [Google AI Studio](https://aistudio.google.com/apikey). Leave unset to disable insight generation (manual refresh returns an error; snapshots still save, just without fresh commentary). |
| `GEMINI_MODEL` | no | Gemini model id used for insight generation. Default `gemini-2.5-flash` |
```

- [ ] **Step 6: Add your own key to the local `.env` file**

This step is manual and not committed to git (`.env` is gitignored). Open the repo-root `.env` file and add a line:

```
GEMINI_API_KEY=your-real-key-here
```

Get a key from Google AI Studio (https://aistudio.google.com/apikey) if you don't have one yet. `GEMINI_MODEL` doesn't need to be set locally — it defaults to `gemini-2.5-flash`.

- [ ] **Step 7: Commit the config changes**

```bash
git add net-worth-service/src/main/resources/application.yml net-worth-service/docker-compose.yml render.yaml net-worth-service/README.md
git commit -m "Add Gemini API config for Amex insights feature"
```

(`.env` is gitignored and is never staged.)

---

### Task 2: Entity — add `ai_commentary` column to `NetWorthSnapshot`

**Files:**
- Modify: `net-worth-service/src/main/java/com/isym444/networth/model/NetWorthSnapshot.java`

**Interfaces:**
- Produces: `NetWorthSnapshot.getAiCommentary(): String`, `NetWorthSnapshot.setAiCommentary(String): void`.

- [ ] **Step 1: Add the field**

In `NetWorthSnapshot.java`, add this field after the `grandTotalJpy` field (line 64, right before the `getId()` getter block):

```java
    @Column(name = "ai_commentary", columnDefinition = "text")
    private String aiCommentary;
```

- [ ] **Step 2: Add the getter/setter**

Add this pair at the end of the class, after `setGrandTotalJpy` (before the closing `}` of the class):

```java

    public String getAiCommentary() {
        return aiCommentary;
    }

    public void setAiCommentary(String aiCommentary) {
        this.aiCommentary = aiCommentary;
    }
```

- [ ] **Step 3: Compile to verify**

Run: `cd net-worth-service && mvn -q compile`
Expected: builds with no errors.

- [ ] **Step 4: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/model/NetWorthSnapshot.java
git commit -m "Add ai_commentary column to NetWorthSnapshot"
```

---

### Task 3: DTO — `AmexInsightsResponse`

**Files:**
- Create: `net-worth-service/src/main/java/com/isym444/networth/dto/AmexInsightsResponse.java`

**Interfaces:**
- Produces: `record AmexInsightsResponse(String commentary, Instant generatedAt, int transactionCount)` in package `com.isym444.networth.dto`.

- [ ] **Step 1: Create the DTO**

```java
package com.isym444.networth.dto;

import java.time.Instant;

public record AmexInsightsResponse(String commentary, Instant generatedAt, int transactionCount) {
}
```

- [ ] **Step 2: Compile to verify**

Run: `cd net-worth-service && mvn -q compile`
Expected: builds with no errors.

- [ ] **Step 3: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/dto/AmexInsightsResponse.java
git commit -m "Add AmexInsightsResponse DTO"
```

---

### Task 4: `GeminiClient`

**Files:**
- Create: `net-worth-service/src/main/java/com/isym444/networth/client/GeminiClient.java`

**Interfaces:**
- Consumes: nothing project-specific (only Spring's `RestClient`, Jackson's `JsonNode`).
- Produces: `GeminiClient.generateContent(String model, String prompt, String apiKey): String` — throws `IllegalStateException` if the key is blank, the HTTP call fails, or the response has no usable text.

- [ ] **Step 1: Create the client**

```java
package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    public String generateContent(String model, String prompt, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini is not configured (missing API key) — set GEMINI_API_KEY.");
        }

        String uri = BASE_URL + model + ":generateContent?key=" + apiKey;
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

        JsonNode response;
        try {
            response = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // Do not log/rethrow the original exception or its message: it typically
            // embeds the full request URI (including the API key query parameter), and
            // standard SLF4J/Logback exception logging would print that verbatim.
            log.error("Gemini request failed (details suppressed to avoid logging credential in URL)");
            throw new IllegalStateException("Gemini API request failed");
        }

        String text = response == null
                ? null
                : response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini API returned no usable content");
        }
        return text.trim();
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `cd net-worth-service && mvn -q compile`
Expected: builds with no errors.

- [ ] **Step 3: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/client/GeminiClient.java
git commit -m "Add GeminiClient for calling the Gemini generateContent API"
```

---

### Task 5: `AmexInsightService`

**Files:**
- Create: `net-worth-service/src/main/java/com/isym444/networth/service/AmexInsightService.java`

**Interfaces:**
- Consumes: `YnabTransactionService.getRecentAmexTransactions(int days): List<AmexTransaction>` (existing, `service/YnabTransactionService.java:43`), `GeminiClient.generateContent(String, String, String): String` (Task 4), `NetWorthSnapshotRepository.findDistinctCurrencies(): List<String>` and `.findFirstByCurrencyOrderByTimestampDescIdDesc(String): Optional<NetWorthSnapshot>` (existing, `repository/NetWorthSnapshotRepository.java`), `NetWorthSnapshot.setAiCommentary(String)` (Task 2), `AmexInsightsResponse` (Task 3).
- Produces: `AmexInsightService.generateCommentary(): String` (no persistence), `AmexInsightService.refreshAndPersist(): AmexInsightsResponse` (generates + persists onto every currency's latest row).

- [ ] **Step 1: Create the service**

```java
package com.isym444.networth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isym444.networth.client.GeminiClient;
import com.isym444.networth.dto.AmexInsightsResponse;
import com.isym444.networth.dto.AmexTransaction;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Generates LLM commentary on recent Amex transactions and, on manual
 * refresh, persists it onto the latest snapshot row for every currency.
 */
@Service
public class AmexInsightService {

    private static final int LOOKBACK_DAYS = 30;

    private final YnabTransactionService ynabTransactionService;
    private final GeminiClient geminiClient;
    private final NetWorthSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${networth.llm.gemini.api-key}")
    private String apiKey;

    @Value("${networth.llm.gemini.model}")
    private String model;

    public AmexInsightService(YnabTransactionService ynabTransactionService,
                               GeminiClient geminiClient,
                               NetWorthSnapshotRepository repository,
                               ObjectMapper objectMapper) {
        this.ynabTransactionService = ynabTransactionService;
        this.geminiClient = geminiClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Generates fresh commentary without persisting it anywhere. */
    public String generateCommentary() {
        List<AmexTransaction> transactions = ynabTransactionService.getRecentAmexTransactions(LOOKBACK_DAYS);
        return geminiClient.generateContent(model, buildPrompt(transactions), apiKey);
    }

    /** Generates fresh commentary and writes it onto the latest snapshot row for every currency. */
    public AmexInsightsResponse refreshAndPersist() {
        List<AmexTransaction> transactions = ynabTransactionService.getRecentAmexTransactions(LOOKBACK_DAYS);
        String commentary = geminiClient.generateContent(model, buildPrompt(transactions), apiKey);

        for (String currency : repository.findDistinctCurrencies()) {
            repository.findFirstByCurrencyOrderByTimestampDescIdDesc(currency)
                    .ifPresent(row -> {
                        row.setAiCommentary(commentary);
                        repository.save(row);
                    });
        }

        return new AmexInsightsResponse(commentary, Instant.now(), transactions.size());
    }

    private String buildPrompt(List<AmexTransaction> transactions) {
        String json;
        try {
            json = objectMapper.writeValueAsString(transactions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize transactions for the LLM prompt", e);
        }
        return """
                You are a concise personal-finance analyst. Below is a JSON array of the user's recent
                Amex credit card transactions (last 30 days), each with date, payee, category, amount
                (in the account's currency, positive = money spent), memo, and account name.

                Respond in plain text only — no markdown headers, no asterisks, no numbered lists. Use
                short paragraphs and "- " for bullet points where useful. Keep the entire response under
                250 words.

                Cover:
                - A brief summary of overall spending and the top 2-3 categories by total spend.
                - Any notable, unusual, or one-off transactions worth flagging.
                - Any visible trend (e.g. increasing spend in a category, recurring charges).
                - 2-4 concrete, specific financial tips grounded in this actual data (not generic advice).

                If the transaction list is empty, say so briefly and skip the sections that don't apply.

                Transactions (JSON):
                """ + json;
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `cd net-worth-service && mvn -q compile`
Expected: builds with no errors.

- [ ] **Step 3: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/service/AmexInsightService.java
git commit -m "Add AmexInsightService to generate and persist Amex spending commentary"
```

---

### Task 6: Wire commentary regeneration into `NetWorthSnapshotService`

**Files:**
- Modify: `net-worth-service/src/main/java/com/isym444/networth/service/NetWorthSnapshotService.java`

**Interfaces:**
- Consumes: `AmexInsightService.generateCommentary(): String` (Task 5), `NetWorthSnapshot.getAiCommentary()/setAiCommentary(String)` (Task 2).
- Produces: `NetWorthSnapshotService.takeSnapshot(): List<NetWorthSnapshot>` (unchanged signature, now delegates to the overload below with `false`), `NetWorthSnapshotService.takeSnapshot(boolean regenerateCommentary): List<NetWorthSnapshot>` (new).

- [ ] **Step 1: Add the field and constructor parameter**

In `NetWorthSnapshotService.java`, add a new field after `private final NetWorthSnapshotRepository repository;` (line 48):

```java
    private final NetWorthSnapshotRepository repository;
    private final AmexInsightService amexInsightService;
```

Update the constructor (lines 86-96) to:

```java
    public NetWorthSnapshotService(Trading212Client trading212Client,
                                    CryptoComClient cryptoComClient,
                                    YnabClient ynabClient,
                                    ExchangeRateClient exchangeRateClient,
                                    NetWorthSnapshotRepository repository,
                                    AmexInsightService amexInsightService) {
        this.trading212Client = trading212Client;
        this.cryptoComClient = cryptoComClient;
        this.ynabClient = ynabClient;
        this.exchangeRateClient = exchangeRateClient;
        this.repository = repository;
        this.amexInsightService = amexInsightService;
    }
```

- [ ] **Step 2: Replace `scheduledSnapshot()` and `takeSnapshot()` (lines 98-168)**

Replace the entire block from `@Scheduled(cron = ...)` (line 98) through the closing `}` of `takeSnapshot()` (line 168) with:

```java
    @Scheduled(cron = "${networth.snapshot.cron}", zone = "${networth.snapshot.zone}")
    public void scheduledSnapshot() {
        log.info("Running scheduled net worth snapshot");
        try {
            List<NetWorthSnapshot> saved = takeSnapshot(true);
            log.info("Saved {} snapshot row(s)", saved.size());
        } catch (Exception e) {
            log.error("Scheduled net worth snapshot failed", e);
        }
    }

    public List<NetWorthSnapshot> takeSnapshot() {
        return takeSnapshot(false);
    }

    /**
     * @param regenerateCommentary if true, calls the LLM once for fresh Amex spending
     *                             commentary and stamps it onto every row in this batch
     *                             (used by the daily cron run). If false, each row's
     *                             commentary is copied forward from the previous row for
     *                             its currency instead (used by the manual "take snapshot"
     *                             button, to avoid an LLM call on every click).
     */
    public List<NetWorthSnapshot> takeSnapshot(boolean regenerateCommentary) {
        log.info("Taking net worth snapshot (trading212.env={}, regenerateCommentary={})",
                trading212Env, regenerateCommentary);
        String baseUrl = "demo".equalsIgnoreCase(trading212Env)
                ? "https://demo.trading212.com"
                : "https://live.trading212.com";

        // currency -> [t212Total, cryptoTotal, chaseTotal, amexDebtTotal]
        Map<String, double[]> totalsByCurrency = new LinkedHashMap<>();

        addTrading212Account(totalsByCurrency, baseUrl, "ISA", isaApiKey, isaApiSecret);
        addTrading212Account(totalsByCurrency, baseUrl, "Invest", investApiKey, investApiSecret);
        addCryptoCom(totalsByCurrency);
        addYnabAccounts(totalsByCurrency);

        String freshCommentary = null;
        if (regenerateCommentary) {
            try {
                freshCommentary = amexInsightService.generateCommentary();
            } catch (Exception e) {
                log.warn("AI commentary generation failed, falling back to carried-forward value", e);
            }
        }

        Instant now = Instant.now();
        List<NetWorthSnapshot> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : totalsByCurrency.entrySet()) {
            String currency = entry.getKey();
            double t212Total = entry.getValue()[0];
            double cryptoTotal = entry.getValue()[1];
            double chaseTotal = entry.getValue()[2];
            double amexDebtTotal = entry.getValue()[3];

            NetWorthSnapshot previous = repository.findFirstByCurrencyOrderByTimestampDescIdDesc(currency)
                    .orElse(null);
            double pensionTotal = previous != null && previous.getPensionTotal() != null
                    ? previous.getPensionTotal() : 0.0;
            double barclaysSharesTotal = previous != null && previous.getBarclaysSharesTotal() != null
                    ? previous.getBarclaysSharesTotal() : 0.0;
            double grandTotal = round2(t212Total + cryptoTotal + pensionTotal + barclaysSharesTotal
                    + chaseTotal - amexDebtTotal);
            double amountLeft = round2(chaseTotal - amexDebtTotal);

            CurrencyConversions conversions = fetchCurrencyConversions(currency, grandTotal);

            NetWorthSnapshot snapshot = new NetWorthSnapshot();
            snapshot.setTimestamp(now);
            snapshot.setCurrency(currency);
            snapshot.setT212Total(round2(t212Total));
            snapshot.setCryptoTotal(round2(cryptoTotal));
            snapshot.setPensionTotal(round2(pensionTotal));
            snapshot.setBarclaysSharesTotal(round2(barclaysSharesTotal));
            snapshot.setChaseTotal(round2(chaseTotal));
            snapshot.setAmexDebtTotal(round2(amexDebtTotal));
            snapshot.setAmountLeft(amountLeft);
            snapshot.setGrandTotal(grandTotal);
            snapshot.setGrandTotalEur(conversions.eur());
            snapshot.setGrandTotalUsd(conversions.usd());
            snapshot.setGrandTotalMxn(conversions.mxn());
            snapshot.setGrandTotalJpy(conversions.jpy());
            snapshot.setAiCommentary(freshCommentary != null
                    ? freshCommentary
                    : (previous != null ? previous.getAiCommentary() : null));
            rows.add(repository.save(snapshot));
            log.info("Saved snapshot: currency={} t212Total={} cryptoTotal={} pensionTotal={} barclaysSharesTotal={} "
                            + "chaseTotal={} amexDebtTotal={} amountLeft={} grandTotal={}",
                    snapshot.getCurrency(), snapshot.getT212Total(), snapshot.getCryptoTotal(),
                    snapshot.getPensionTotal(), snapshot.getBarclaysSharesTotal(),
                    snapshot.getChaseTotal(), snapshot.getAmexDebtTotal(), snapshot.getAmountLeft(),
                    snapshot.getGrandTotal());
        }
        return rows;
    }
```

- [ ] **Step 3: Compile to verify**

Run: `cd net-worth-service && mvn -q compile`
Expected: builds with no errors. Nothing in the codebase calls `new NetWorthSnapshotService(...)` directly — Spring wires it via dependency injection at runtime — so changing its constructor signature here doesn't break compilation of any other file, even before `NetWorthController` is updated in Task 7. (Spring would only catch a real mismatch when the app boots, which happens in Task 11.)

- [ ] **Step 4: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/service/NetWorthSnapshotService.java
git commit -m "Regenerate Amex commentary on cron snapshots, copy forward otherwise"
```

---

### Task 7: Controller — `POST /api/v1/amex/insights`

**Files:**
- Modify: `net-worth-service/src/main/java/com/isym444/networth/controller/NetWorthController.java`
- Modify: `net-worth-service/README.md`

**Interfaces:**
- Consumes: `AmexInsightService.refreshAndPersist(): AmexInsightsResponse` (Task 5).
- Produces: `POST /api/v1/amex/insights` HTTP endpoint returning `AmexInsightsResponse` JSON.

- [ ] **Step 1: Add imports**

In `NetWorthController.java`, add these two imports alongside the existing `com.isym444.networth.dto.*` / `com.isym444.networth.service.*` imports:

```java
import com.isym444.networth.dto.AmexInsightsResponse;
import com.isym444.networth.service.AmexInsightService;
```

- [ ] **Step 2: Add the field and constructor parameter**

Add a field after `private final YnabTransactionService ynabTransactionService;` (line 30):

```java
    private final YnabTransactionService ynabTransactionService;
    private final AmexInsightService amexInsightService;
```

Update the constructor (lines 32-42) to:

```java
    public NetWorthController(NetWorthSnapshotService snapshotService,
                               NetWorthHistoryService historyService,
                               NetWorthSnapshotRepository repository,
                               ChartService chartService,
                               YnabTransactionService ynabTransactionService,
                               AmexInsightService amexInsightService) {
        this.snapshotService = snapshotService;
        this.historyService = historyService;
        this.repository = repository;
        this.chartService = chartService;
        this.ynabTransactionService = ynabTransactionService;
        this.amexInsightService = amexInsightService;
    }
```

- [ ] **Step 3: Add the endpoint**

Add this method after `amexTransactions()` (after line 58, before the `/chart` mapping):

```java
    @PostMapping("/amex/insights")
    public ResponseEntity<AmexInsightsResponse> refreshAmexInsights() {
        return ResponseEntity.ok(amexInsightService.refreshAndPersist());
    }
```

- [ ] **Step 4: Compile to verify**

Run: `cd net-worth-service && mvn -q compile`
Expected: builds with no errors. This only confirms `NetWorthController.java` itself compiles (its own field/constructor/method syntax and types) — Spring's dependency graph (whether every constructor param has a matching bean) is validated at app boot, not compile time, so that check happens in Task 11.

- [ ] **Step 5: Update `net-worth-service/README.md`'s API table**

Add a new row to the API table (after the `GET /api/v1/amex/transactions` row):

```markdown
| `POST /api/v1/amex/insights` | Fetch recent Amex transactions, ask Gemini to analyze them, and overwrite `ai_commentary` on the latest snapshot row for every currency. Returns `{"commentary": "...", "generatedAt": "...", "transactionCount": 12}`. Requires `GEMINI_API_KEY`. |
```

Add a curl example to the "Run locally" section, after the existing `amex/transactions` curl line:

```bash
curl "http://localhost:8080/api/v1/amex/transactions?days=30"
curl -X POST http://localhost:8080/api/v1/amex/insights
```

- [ ] **Step 6: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/controller/NetWorthController.java net-worth-service/README.md
git commit -m "Add POST /api/v1/amex/insights endpoint"
```

---

### Task 8: Frontend — `net-worth.service.ts`

**Files:**
- Modify: `net-worth-frontend/src/app/net-worth.service.ts`

**Interfaces:**
- Produces: `NetWorthSnapshot.aiCommentary: string | null` (new field on the existing interface), `interface AmexInsightsResponse { commentary: string; generatedAt: string; transactionCount: number; }`, `NetWorthService.refreshAmexInsights(): Observable<AmexInsightsResponse>`.

- [ ] **Step 1: Add `aiCommentary` to the `NetWorthSnapshot` interface**

In `net-worth.service.ts`, add a field to the `NetWorthSnapshot` interface (after `grandTotalJpy: number | null;`, line 21):

```ts
  grandTotalJpy: number | null;
  aiCommentary: string | null;
}
```

- [ ] **Step 2: Add the `AmexInsightsResponse` interface**

Add this after the `NetWorthHistoryResponse` interface (after line 30):

```ts
export interface AmexInsightsResponse {
  commentary: string;
  generatedAt: string;
  transactionCount: number;
}
```

- [ ] **Step 3: Add the `refreshAmexInsights` method**

Add this method to the `NetWorthService` class, after `takeSnapshot()` (after line 46):

```ts
  refreshAmexInsights(): Observable<AmexInsightsResponse> {
    return this.http.post<AmexInsightsResponse>(`${this.baseUrl}/api/v1/amex/insights`, {});
  }
```

- [ ] **Step 4: Build to verify**

Run: `cd net-worth-frontend && npm run build`
Expected: builds with no TypeScript errors.

- [ ] **Step 5: Commit**

```bash
git add net-worth-frontend/src/app/net-worth.service.ts
git commit -m "Add aiCommentary field and refreshAmexInsights() to NetWorthService"
```

---

### Task 9: Frontend — `dashboard.component.ts`

**Files:**
- Modify: `net-worth-frontend/src/app/dashboard.component.ts`

**Interfaces:**
- Consumes: `NetWorthService.refreshAmexInsights(): Observable<AmexInsightsResponse>` (Task 8), `NetWorthSnapshot.aiCommentary` (Task 8).
- Produces: `DashboardComponent.refreshingInsights: Signal<boolean>` (protected signal), `DashboardComponent.refreshInsights(): void` (method callable from the template).

- [ ] **Step 1: Import `AmexInsightsResponse` is not needed in the component (unused if not referenced) — skip adding it; only the signal and method are needed**

No import changes needed since `refreshAmexInsights()`'s return value isn't stored by type in the component (only its side effect matters, same as `takeSnapshot()` today).

- [ ] **Step 2: Add the `refreshingInsights` signal**

Add this after `protected readonly takingSnapshot = signal(false);` (line 25):

```ts
  protected readonly takingSnapshot = signal(false);
  protected readonly refreshingInsights = signal(false);
```

- [ ] **Step 3: Add the `refreshInsights()` method**

Add this method after `takeSnapshot()` (after line 86, before the `monthlySubscriptions` comment block):

```ts
  refreshInsights(): void {
    this.refreshingInsights.set(true);
    this.error.set(null);
    this.netWorthService.refreshAmexInsights().subscribe({
      next: () => {
        this.refreshingInsights.set(false);
        this.loadHistory(false);
      },
      error: (err: unknown) => {
        this.refreshingInsights.set(false);
        this.error.set(this.extractError(err));
      },
    });
  }
```

- [ ] **Step 4: Build to verify**

Run: `cd net-worth-frontend && npm run build`
Expected: builds with no TypeScript errors. (The template doesn't reference `refreshingInsights()`/`refreshInsights()` yet — that's Task 10 — so this build only confirms the component class itself compiles.)

- [ ] **Step 5: Commit**

```bash
git add net-worth-frontend/src/app/dashboard.component.ts
git commit -m "Add refreshInsights() and refreshingInsights signal to DashboardComponent"
```

---

### Task 10: Frontend — insights card (HTML + CSS)

**Files:**
- Modify: `net-worth-frontend/src/app/dashboard.component.html`
- Modify: `net-worth-frontend/src/app/dashboard.component.css`

**Interfaces:**
- Consumes: `DashboardComponent.refreshingInsights`, `DashboardComponent.refreshInsights()` (Task 9), `NetWorthSnapshot.aiCommentary` (Task 8), `DashboardComponent.latest()` (existing computed signal).

- [ ] **Step 1: Add the insights card to the template**

In `dashboard.component.html`, add this new section immediately after the closing `</section>` of the `headline` section (after line 148, before the `@if (chartUrl(); as url)` block on line 153):

```html
    <section class="insights-card">
      <div class="insights-card__header">
        <h2>AI Spending Insights</h2>
        <button (click)="refreshInsights()" [disabled]="refreshingInsights()">
          @if (refreshingInsights()) {
            <span class="spinner"></span>
          }
          {{ refreshingInsights() ? 'Analyzing…' : 'Refresh insights' }}
        </button>
      </div>
      @if (latestSnapshot.aiCommentary) {
        <p class="insights-card__text">{{ latestSnapshot.aiCommentary }}</p>
      } @else {
        <p class="muted">No insights yet. Click "Refresh insights" to generate one.</p>
      }
    </section>
```

This sits inside the existing `@if (latest(); as latestSnapshot)` block (it closes just after this point), so `latestSnapshot` is already in scope.

- [ ] **Step 2: Add the CSS**

In `dashboard.component.css`, add this block after the `.chart` rule (after line 193, before `.table-card`):

```css
.insights-card {
  margin-bottom: 1.5rem;
  padding: 1.25rem;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  animation: fade-in-up 240ms var(--ease-out) 40ms both;
}

.insights-card__header {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.75rem;
}

.insights-card__header h2 {
  font-size: 1.1rem;
  margin: 0;
  color: var(--text);
}

.insights-card__header button {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 999px;
  background: var(--accent);
  color: #05261b;
  font-weight: 600;
  font-size: 0.85rem;
  cursor: pointer;
  transition: background-color 0.15s ease, transform 160ms var(--ease-out);
}

.insights-card__header button:hover:not(:disabled) {
  background: var(--accent-hover);
}

.insights-card__header button:active:not(:disabled) {
  transform: scale(0.97);
}

.insights-card__header button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.insights-card__text {
  white-space: pre-wrap;
  color: var(--text);
  line-height: 1.5;
  margin: 0;
}
```

Also add `.insights-card` to the reduced-motion media query's selector list (the block starting at line 256 in the original file):

```css
@media (prefers-reduced-motion: reduce) {
  :host,
  .headline,
  .insights-card,
  .chart-card,
  .table-card,
  .error,
  tbody tr {
    animation-name: fade-in;
  }

  .spinner {
    animation: none;
  }
}
```

- [ ] **Step 3: Build to verify**

Run: `cd net-worth-frontend && npm run build`
Expected: builds with no errors (Angular's template type-checking will catch a typo like `refreshinsights()` here).

- [ ] **Step 4: Commit**

```bash
git add net-worth-frontend/src/app/dashboard.component.html net-worth-frontend/src/app/dashboard.component.css
git commit -m "Add AI Spending Insights card to the dashboard"
```

---

### Task 11: End-to-end manual verification

**Files:** none (verification only).

- [ ] **Step 1: Start the backend locally**

From the repo root:

```bash
export TRADING212_API_KEY_ISA=... TRADING212_API_KEY_INVEST=... CRYPTO_COM_KEY=... CRYPTO_COM_SECRET=... YNAB_API_TOKEN=... YNAB_CHASE_ACCOUNT_IDS=... YNAB_AMEX_ACCOUNT_IDS=...
cd net-worth-service
docker compose up --build
```

(Use whatever real credentials you already use for local dev — these are the same ones required before this feature. `GEMINI_API_KEY` comes from the `.env` file set in Task 1, Step 6, and is picked up automatically by `docker compose` since `net-worth-service/.env` is a symlink to the repo-root `.env`.)

- [ ] **Step 2: Verify the schema migrated**

Run: `curl http://localhost:8080/api/v1/history`
Expected: JSON response (empty or with existing rows) — no boot errors in the `docker compose` logs about the `ai_commentary` column. If there are existing rows, none will show `aiCommentary` populated yet.

- [ ] **Step 3: Take a manual snapshot (copy-forward path)**

Run: `curl -X POST http://localhost:8080/api/v1/snapshot`
Expected: JSON array of saved snapshot rows, each including `"aiCommentary": null` (since there's no prior commentary to copy forward yet, and this path doesn't call Gemini).

- [ ] **Step 4: Trigger the new insights endpoint**

Run: `curl -X POST http://localhost:8080/api/v1/amex/insights`
Expected: JSON response like `{"commentary": "...", "generatedAt": "...", "transactionCount": N}` with real analysis text (plain text, no markdown symbols, roughly under 250 words). If this errors, check the `docker compose` logs — a blank `GEMINI_API_KEY` or an invalid key will surface as a 500 with a clear message.

- [ ] **Step 5: Confirm persistence**

Run: `curl -X POST http://localhost:8080/api/v1/snapshot` again, then `curl http://localhost:8080/api/v1/history`
Expected: the newest snapshot row(s) now carry the `aiCommentary` text from Step 4 (copied forward, since this is the manual "take snapshot" path, not the cron).

- [ ] **Step 6: Verify the dashboard end-to-end**

In a second terminal:

```bash
cd net-worth-frontend
npm start
```

Open `http://localhost:4200`. Confirm:
- The "AI Spending Insights" card renders with the commentary text from Step 5 wrapped in a bordered card matching the existing stat-card/chart-card visual style.
- Clicking "Refresh insights" shows the spinner, then updates the card's text in place once it completes.
- Reloading the page still shows the last-generated commentary (proves it's read from `/history`, not held only in memory).

- [ ] **Step 7: Stop the local Docker container**

```bash
docker compose stop
```

Run this from `net-worth-service/`. This project's local Postgres is a shared, low-connection-budget Supabase instance — always stop the container after a manual test session (see the `render-supabase-datasource-incident` project note) rather than leaving it running.

- [ ] **Step 8: No commit for this task** — it's verification only. If any step surfaced a bug, fix it in the relevant earlier task's files and re-commit there before proceeding.

## Out of scope

(Carried from the spec — not implemented by this plan.)

- Markdown rendering on the frontend.
- Using net-worth totals (beyond transactions) as additional LLM context.
- A history/list view of past commentary.
- Retry logic for Gemini failures.
