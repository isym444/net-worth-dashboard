# Pension + Barclays Shares Carry-Forward Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the manually-maintained `pension_total`/`barclays_shares_total` columns (already added to the `snapshots` table via direct DB edits) into the JPA entity, the snapshot-taking service, and the frontend, per `docs/superpowers/specs/2026-07-29-pension-barclays-shares-design.md`.

**Architecture:** The two values are never computed or fetched — they're maintained by hand in the database. `NetWorthSnapshotService.takeSnapshot()` looks up the latest existing row for each currency being saved and carries its `pensionTotal`/`barclaysSharesTotal` forward onto the new row (defaulting to `0.0` for a brand-new currency), folding both into `grandTotal`. The frontend adds two headline cards and two table columns to display them — no new interactivity.

**Tech Stack:** Spring Boot / JPA / Hibernate (`ddl-auto: update`, so no migration needed — the columns already exist), Angular 18+ (TypeScript interface + CSS/HTML display only).

## Global Constraints

- No database migration — `ddl-auto: update` reconciles the entity against already-existing columns.
- No UI to edit these two values — display only, per the spec's non-goals.
- No new chart series — the chart stays Trading212/Crypto.com/Net worth.
- `grandTotal = t212Total + cryptoTotal + pensionTotal + barclaysSharesTotal`, rounded to 2dp with the existing `round2` helper, same as the other totals.
- Field names: `pensionTotal`/`barclaysSharesTotal` (Java, camelCase) mapped to `pension_total`/`barclays_shares_total` (DB columns), and `pensionTotal`/`barclaysSharesTotal` (TypeScript, matching the Java field names exactly — no naming-strategy translation happens in this codebase today).

---

### Task 1: Backend carry-forward logic

**Files:**
- Modify: `net-worth-service/src/main/java/com/isym444/networth/model/NetWorthSnapshot.java`
- Modify: `net-worth-service/src/main/java/com/isym444/networth/repository/NetWorthSnapshotRepository.java`
- Modify: `net-worth-service/src/main/java/com/isym444/networth/service/NetWorthSnapshotService.java`

**Interfaces:**
- Produces: `NetWorthSnapshot.getPensionTotal()`/`setPensionTotal(Double)` and `getBarclaysSharesTotal()`/`setBarclaysSharesTotal(Double)`, consumed by the frontend's JSON deserialization (field names `pensionTotal`/`barclaysSharesTotal` in the serialized response, matching Task 2's TypeScript interface).
- Produces: `NetWorthSnapshotRepository.findFirstByCurrencyOrderByTimestampDesc(String currency): Optional<NetWorthSnapshot>`.
- `NetWorthSnapshotService.takeSnapshot()`'s public signature (`List<NetWorthSnapshot> takeSnapshot()`) and `NetWorthController`'s usage of it are unchanged — this task only changes what happens inside the method body.

- [ ] **Step 1: Replace the contents of `NetWorthSnapshot.java`**

```java
package com.isym444.networth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "snapshots")
public class NetWorthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private String currency;

    @Column(name = "t212_total", nullable = false)
    private Double t212Total;

    @Column(name = "crypto_total", nullable = false)
    private Double cryptoTotal;

    @Column(name = "pension_total", nullable = false)
    private Double pensionTotal;

    @Column(name = "barclays_shares_total", nullable = false)
    private Double barclaysSharesTotal;

    @Column(name = "grand_total", nullable = false)
    private Double grandTotal;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Double getT212Total() {
        return t212Total;
    }

    public void setT212Total(Double t212Total) {
        this.t212Total = t212Total;
    }

    public Double getCryptoTotal() {
        return cryptoTotal;
    }

    public void setCryptoTotal(Double cryptoTotal) {
        this.cryptoTotal = cryptoTotal;
    }

    public Double getPensionTotal() {
        return pensionTotal;
    }

    public void setPensionTotal(Double pensionTotal) {
        this.pensionTotal = pensionTotal;
    }

    public Double getBarclaysSharesTotal() {
        return barclaysSharesTotal;
    }

    public void setBarclaysSharesTotal(Double barclaysSharesTotal) {
        this.barclaysSharesTotal = barclaysSharesTotal;
    }

    public Double getGrandTotal() {
        return grandTotal;
    }

    public void setGrandTotal(Double grandTotal) {
        this.grandTotal = grandTotal;
    }
}
```

- [ ] **Step 2: Replace the contents of `NetWorthSnapshotRepository.java`**

```java
package com.isym444.networth.repository;

import com.isym444.networth.model.NetWorthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, Long> {

    List<NetWorthSnapshot> findByCurrencyOrderByTimestampAsc(String currency);

    Optional<NetWorthSnapshot> findFirstByCurrencyOrderByTimestampDesc(String currency);

    @Query("SELECT DISTINCT s.currency FROM NetWorthSnapshot s ORDER BY s.currency")
    List<String> findDistinctCurrencies();
}
```

- [ ] **Step 3: Replace the contents of `NetWorthSnapshotService.java`**

```java
package com.isym444.networth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.isym444.networth.client.CryptoComClient;
import com.isym444.networth.client.Trading212Client;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches balances from every configured source (Trading212 ISA, Trading212
 * Invest, Crypto.com) and persists one row per currency observed. Neither
 * upstream API exposes historical portfolio value, so this service is the
 * only place history gets built up — one snapshot at a time.
 */
@Service
public class NetWorthSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(NetWorthSnapshotService.class);

    private final Trading212Client trading212Client;
    private final CryptoComClient cryptoComClient;
    private final NetWorthSnapshotRepository repository;

    @Value("${networth.trading212.env}")
    private String trading212Env;

    @Value("${networth.trading212.isa.api-key}")
    private String isaApiKey;

    @Value("${networth.trading212.isa.api-secret}")
    private String isaApiSecret;

    @Value("${networth.trading212.invest.api-key}")
    private String investApiKey;

    @Value("${networth.trading212.invest.api-secret}")
    private String investApiSecret;

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

    @Scheduled(cron = "${networth.snapshot.cron}", zone = "${networth.snapshot.zone}")
    public void scheduledSnapshot() {
        log.info("Running scheduled net worth snapshot");
        try {
            List<NetWorthSnapshot> saved = takeSnapshot();
            log.info("Saved {} snapshot row(s)", saved.size());
        } catch (Exception e) {
            log.error("Scheduled net worth snapshot failed", e);
        }
    }

    public List<NetWorthSnapshot> takeSnapshot() {
        log.info("Taking net worth snapshot (trading212.env={})", trading212Env);
        String baseUrl = "demo".equalsIgnoreCase(trading212Env)
                ? "https://demo.trading212.com"
                : "https://live.trading212.com";

        // currency -> [t212Total, cryptoTotal]
        Map<String, double[]> totalsByCurrency = new LinkedHashMap<>();

        addTrading212Account(totalsByCurrency, baseUrl, "ISA", isaApiKey, isaApiSecret);
        addTrading212Account(totalsByCurrency, baseUrl, "Invest", investApiKey, investApiSecret);
        addCryptoCom(totalsByCurrency);

        if (totalsByCurrency.isEmpty()) {
            throw new IllegalStateException(
                    "No API keys configured. Set TRADING212_API_KEY_ISA / TRADING212_API_KEY_INVEST "
                            + "and/or CRYPTO_COM_KEY + CRYPTO_COM_SECRET.");
        }

        Instant now = Instant.now();
        List<NetWorthSnapshot> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : totalsByCurrency.entrySet()) {
            String currency = entry.getKey();
            double t212Total = entry.getValue()[0];
            double cryptoTotal = entry.getValue()[1];

            double[] carriedForward = carryForwardManualTotals(currency);
            double pensionTotal = carriedForward[0];
            double barclaysSharesTotal = carriedForward[1];

            NetWorthSnapshot snapshot = new NetWorthSnapshot();
            snapshot.setTimestamp(now);
            snapshot.setCurrency(currency);
            snapshot.setT212Total(round2(t212Total));
            snapshot.setCryptoTotal(round2(cryptoTotal));
            snapshot.setPensionTotal(round2(pensionTotal));
            snapshot.setBarclaysSharesTotal(round2(barclaysSharesTotal));
            snapshot.setGrandTotal(round2(t212Total + cryptoTotal + pensionTotal + barclaysSharesTotal));
            rows.add(repository.save(snapshot));
            log.info("Saved snapshot: currency={} t212Total={} cryptoTotal={} pensionTotal={} barclaysSharesTotal={} grandTotal={}",
                    snapshot.getCurrency(), snapshot.getT212Total(), snapshot.getCryptoTotal(),
                    snapshot.getPensionTotal(), snapshot.getBarclaysSharesTotal(), snapshot.getGrandTotal());
        }
        return rows;
    }

    /**
     * Pension and Barclays shares aren't fetched from any API — they're maintained
     * by hand directly in the database. Each new snapshot carries forward whichever
     * values were most recently saved for this currency, rather than resetting them.
     */
    private double[] carryForwardManualTotals(String currency) {
        return repository.findFirstByCurrencyOrderByTimestampDesc(currency)
                .map(previous -> new double[] { previous.getPensionTotal(), previous.getBarclaysSharesTotal() })
                .orElse(new double[] { 0.0, 0.0 });
    }

    private void addTrading212Account(Map<String, double[]> totalsByCurrency, String baseUrl, String label,
                                       String apiKey, String apiSecret) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("Trading212 {} account skipped (no API key configured)", label);
            return;
        }
        log.info("Fetching Trading212 {} account summary from {}", label, baseUrl);
        JsonNode summary = trading212Client.fetchAccountSummary(baseUrl, apiKey, apiSecret);
        String currency = summary.path("currency").asText("");

        double available = summary.path("cash").path("availableToTrade").asDouble(0);
        double reserved = summary.path("cash").path("reservedForOrders").asDouble(0);
        double inPies = summary.path("cash").path("inPies").asDouble(0);
        double currentValue = summary.path("investments").path("currentValue").asDouble(0);
        double totalValue = summary.has("totalValue")
                ? summary.path("totalValue").asDouble()
                : currentValue + available + reserved + inPies;

        log.info("Trading212 {} account total: {} {}", label, totalValue, currency);
        totalsByCurrency.computeIfAbsent(currency, c -> new double[2])[0] += totalValue;
    }

    private void addCryptoCom(Map<String, double[]> totalsByCurrency) {
        if (cryptoComApiKey == null || cryptoComApiKey.isBlank()
                || cryptoComApiSecret == null || cryptoComApiSecret.isBlank()) {
            log.info("Crypto.com skipped (no API key/secret configured)");
            return;
        }

        log.info("Fetching Crypto.com fiat + portfolio balances");
        JsonNode fiat = cryptoComClient.get("/v1/fiat-account", cryptoComApiKey, cryptoComApiSecret);
        for (JsonNode balance : fiat.path("account").path("balances")) {
            double amount = balance.path("amount").path("amount").asDouble(0);
            if (amount <= 0) {
                continue;
            }
            String currency = balance.path("currency").asText("");
            totalsByCurrency.computeIfAbsent(currency, c -> new double[2])[1] += amount;
        }

        // Use the portfolio breakdown (not raw wallet balances) so staked/
        // locked funds across every product are included without double-counting.
        JsonNode portfolio = cryptoComClient.get("/v1/portfolio", cryptoComApiKey, cryptoComApiSecret);
        for (JsonNode product : portfolio.path("products")) {
            double amount = product.path("price_native").path("amount").asDouble(0);
            if (amount <= 0) {
                continue;
            }
            String currency = product.path("price_native").path("currency").asText("");
            totalsByCurrency.computeIfAbsent(currency, c -> new double[2])[1] += amount;
        }
        log.info("Crypto.com balances fetched");
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
```

- [ ] **Step 4: Verify the service compiles**

Run: `cd net-worth-service && mvn -q compile` (no Maven wrapper in this repo — use the `mvn` CLI directly)
Expected: `BUILD SUCCESS`, no errors.

- [ ] **Step 5: Verify the carry-forward query logic directly against Postgres**

The live `/api/v1/snapshot` endpoint can't be exercised end-to-end in this dev environment (no real Trading212/Crypto.com credentials — it already fails today with "No API keys configured", unrelated to this change). Instead, confirm the SQL `findFirstByCurrencyOrderByTimestampDesc` will generate returns the expected row:

Run:
```bash
docker exec net-worth-service-postgres-1 psql -U postgres -d networth -c \
  "SELECT currency, timestamp, pension_total, barclays_shares_total FROM snapshots ORDER BY timestamp DESC LIMIT 1;"
```
Expected: one row, matching the most recently saved snapshot's currency and its `pension_total`/`barclays_shares_total` values (e.g. `10577.98` / `1065.78` if that's still the latest row at verification time) — confirming that "the latest row per currency, ordered by timestamp descending, limited to one" is exactly what the derived query method name expresses.

- [ ] **Step 6: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/model/NetWorthSnapshot.java \
        net-worth-service/src/main/java/com/isym444/networth/repository/NetWorthSnapshotRepository.java \
        net-worth-service/src/main/java/com/isym444/networth/service/NetWorthSnapshotService.java
git commit -m "Carry forward pension and Barclays shares totals into new snapshots"
```

---

### Task 2: Frontend display

**Files:**
- Modify: `net-worth-frontend/src/app/net-worth.service.ts`
- Modify: `net-worth-frontend/src/app/dashboard.component.html`
- Modify: `net-worth-frontend/src/app/dashboard.component.css`

**Interfaces:**
- Consumes: `pensionTotal`/`barclaysSharesTotal` fields on the JSON returned by `GET /api/v1/history` and `POST /api/v1/snapshot` (produced by Task 1's backend changes) — same field names, no translation.
- Consumes existing `DashboardComponent` signals unchanged: `latest()`, `sortedDescending()`, `chartUrl()`, `takingSnapshot()`, `takeSnapshot()`, `error()`, `loading()`.
- No `.ts` component logic changes — only the `NetWorthSnapshot` interface (a type, not logic) and the two component's template/styles.

- [ ] **Step 1: Edit `net-worth.service.ts`** — add two fields to the `NetWorthSnapshot` interface

Find:
```typescript
export interface NetWorthSnapshot {
  id: number;
  timestamp: string;
  currency: string;
  t212Total: number;
  cryptoTotal: number;
  grandTotal: number;
}
```

Replace with:
```typescript
export interface NetWorthSnapshot {
  id: number;
  timestamp: string;
  currency: string;
  t212Total: number;
  cryptoTotal: number;
  pensionTotal: number;
  barclaysSharesTotal: number;
  grandTotal: number;
}
```

- [ ] **Step 2: Replace the contents of `dashboard.component.html`**

```html
<header>
  <h1>Net Worth Dashboard</h1>
  <button (click)="takeSnapshot()" [disabled]="takingSnapshot()">
    @if (takingSnapshot()) {
      <span class="spinner"></span>
    }
    {{ takingSnapshot() ? 'Taking snapshot…' : 'Take snapshot now' }}
  </button>
</header>

@if (error()) {
  <p class="error">{{ error() }}</p>
}

@if (loading()) {
  <p class="muted">Loading…</p>
} @else {
  @if (latest(); as latestSnapshot) {
    <section class="headline">
      <div class="stat-card">
        <span>Trading212</span>
        <strong>{{ latestSnapshot.t212Total | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
      </div>
      <div class="stat-card">
        <span>Crypto.com</span>
        <strong>{{ latestSnapshot.cryptoTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
      </div>
      <div class="stat-card">
        <span>Pension</span>
        <strong>{{ latestSnapshot.pensionTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
      </div>
      <div class="stat-card">
        <span>Barclays Shares</span>
        <strong>{{ latestSnapshot.barclaysSharesTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
      </div>
      <div class="stat-card stat-card--primary">
        <span>Net worth</span>
        <strong>{{ latestSnapshot.grandTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
      </div>
    </section>
  } @else {
    <p class="muted">No snapshots yet. Click "Take snapshot now" to get started.</p>
  }

  @if (chartUrl(); as url) {
    <section class="chart-card">
      <img [src]="url" alt="Net worth over time" class="chart" />
    </section>
  }

  @if (sortedDescending().length > 0) {
    <section class="table-card">
      <table>
        <thead>
          <tr>
            <th>Date</th>
            <th>Trading212</th>
            <th>Crypto.com</th>
            <th>Pension</th>
            <th>Barclays Shares</th>
            <th>Net worth</th>
          </tr>
        </thead>
        <tbody>
          @for (row of sortedDescending(); track row.id) {
            <tr>
              <td>{{ row.timestamp | date: 'medium' }}</td>
              <td>{{ row.t212Total | number: '1.2-2' }}</td>
              <td>{{ row.cryptoTotal | number: '1.2-2' }}</td>
              <td>{{ row.pensionTotal | number: '1.2-2' }}</td>
              <td>{{ row.barclaysSharesTotal | number: '1.2-2' }}</td>
              <td>{{ row.grandTotal | number: '1.2-2' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  }
}
```

- [ ] **Step 3: Edit `dashboard.component.css`** — widen the headline grid from a fixed 3 columns to a responsive auto-fit layout for 5 cards

Find:
```css
.headline {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
  margin-bottom: 1.5rem;
}
```

Replace with:
```css
.headline {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 1rem;
  margin-bottom: 1.5rem;
}
```

No other rule in this file changes — the existing `@media (max-width: 640px) { .headline { grid-template-columns: 1fr; } }` block still applies and continues to force a single column on mobile, and `.table-card`'s existing `overflow-x: auto` already handles the two extra table columns on narrow viewports.

- [ ] **Step 4: Verify the app still builds**

Run: `cd net-worth-frontend && npx ng build`
Expected: build succeeds with no errors.

- [ ] **Step 5: Commit**

```bash
git add net-worth-frontend/src/app/net-worth.service.ts \
        net-worth-frontend/src/app/dashboard.component.html \
        net-worth-frontend/src/app/dashboard.component.css
git commit -m "Display pension and Barclays shares totals on the dashboard"
```

---

### Task 3: Manual visual verification

**Files:** none (verification only)

**Interfaces:** none — exercises the backend and frontend built by Tasks 1–2.

- [ ] **Step 1: Rebuild and restart the backend**

Run: `cd net-worth-service && docker compose up -d --build app` so the new entity/repository/service code is live.

- [ ] **Step 2: Confirm existing history still returns the new fields**

Run: `curl -s http://localhost:8080/api/v1/history | head -c 500` (or pipe through a JSON formatter if available).
Confirm: each returned object includes `pensionTotal` and `barclaysSharesTotal` keys with the values already present in the database (e.g. `10577.98` / `1065.78` for the most recent existing row) — proving Hibernate mapped the new columns correctly on read, independent of whether a new snapshot can be taken in this environment.

- [ ] **Step 3: Check the dashboard renders the new cards and columns**

With the frontend dev server running (`ng serve`) and the browser pointed at the unlocked dashboard, confirm:
- Five headline stat cards render (Trading212, Crypto.com, Pension, Barclays Shares, Net worth), laid out cleanly without visually breaking at both a wide (~1280px) and narrow (~375px) viewport.
- The history table has two new columns ("Pension", "Barclays Shares") with values matching the existing DB data, positioned between Crypto.com and Net worth.
- "Net worth" in both the headline card and the table reflects the sum including pension and Barclays shares (i.e. it's larger than `t212Total + cryptoTotal` alone) for any row where pension/Barclays shares are non-zero.
