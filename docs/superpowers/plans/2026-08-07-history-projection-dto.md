# History Endpoint DTO with Net Worth Projections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GET /api/v1/history` return a custom `NetWorthHistoryResponse` DTO (built by a new `NetWorthHistoryService`, not the repository directly) that wraps the snapshot list plus four projected-net-worth fields (`expectedNetWorthAt40/50/60/65`), computed via 7% compound interest from the latest snapshot's `grandTotal`, per `docs/superpowers/specs/2026-08-07-history-projection-dto-design.md`.

**Architecture:** New `NetWorthHistoryService` (separate from `NetWorthSnapshotService`, which only creates snapshots) owns currency resolution, the repository read, and the projection math, injected with a `Clock` bean for deterministic testing. `NetWorthController.history()` shrinks to a one-line delegation. The Angular frontend's `getHistory()` and dashboard are updated in the same plan to consume the new wrapped shape and display the four projections as headline cards, matching the existing EUR/USD/MXN/JPY card pattern.

**Tech Stack:** Spring Boot (Java records for the DTO), JUnit 5 + Mockito (already available via `spring-boot-starter-test`, no new dependency), Angular signals.

## Global Constraints

- Birth date is a hardcoded constant: `LocalDate.of(1996, 6, 14)`. Growth rate is a hardcoded constant: `0.07`. No config/env plumbing for either — single-user app.
- Projection years-remaining is computed from **today's date at request time** (via an injected `Clock`), not the latest snapshot's timestamp.
- Monetary values round to 2dp using the same `Math.round(value * 100.0) / 100.0` pattern already used in `NetWorthSnapshotService`.
- `/chart` endpoint and its direct repository access are unchanged — out of scope.
- No new test infrastructure is scaffolded for the frontend (no `.spec.ts` files exist in `net-worth-frontend` today, and the design spec explicitly keeps it that way) — frontend tasks verify via `ng build`'s type-checking instead of unit tests. The backend already has `spring-boot-starter-test` as a dependency (JUnit 5 + Mockito), so backend tasks do follow full TDD — this is the first Java test file in the project.
- DTO lives in a new `com.isym444.networth.dto` package (not `model`, which is reserved for the JPA entity).

---

### Task 1: `NetWorthHistoryResponse` DTO + pure projection math in `NetWorthHistoryService`

**Files:**
- Create: `net-worth-service/src/main/java/com/isym444/networth/dto/NetWorthHistoryResponse.java`
- Create: `net-worth-service/src/main/java/com/isym444/networth/service/NetWorthHistoryService.java`
- Create: `net-worth-service/src/test/java/com/isym444/networth/service/NetWorthHistoryServiceTest.java`
- Modify: `net-worth-service/src/main/java/com/isym444/networth/NetWorthServiceApplication.java`

**Interfaces:**
- Produces: `NetWorthHistoryResponse` record — `snapshots(): List<NetWorthSnapshot>`, `expectedNetWorthAt40/50/60/65(): Double`.
- Produces: `NetWorthHistoryService(NetWorthSnapshotRepository repository, Clock clock)` constructor.
- Produces: package-private `double yearsRemaining(LocalDate now, int targetAge)` and `double projectedValue(double baseline, double years)` on `NetWorthHistoryService` — consumed directly by this task's tests, and internally by `getHistory()` in Task 2.
- Produces: a `Clock` bean (`Clock.systemDefaultZone()`) from `NetWorthServiceApplication` — consumed by Spring's DI when constructing `NetWorthHistoryService` in the running app.

- [ ] **Step 1: Write the failing test for the pure math methods**

Create `net-worth-service/src/test/java/com/isym444/networth/service/NetWorthHistoryServiceTest.java`:

```java
package com.isym444.networth.service;

import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class NetWorthHistoryServiceTest {

    private final NetWorthHistoryService service =
            new NetWorthHistoryService(mock(NetWorthSnapshotRepository.class), Clock.systemUTC());

    @Test
    void yearsRemaining_returnsZero_whenNowIsExactlyTheTargetBirthday() {
        LocalDate now = LocalDate.of(2036, 6, 14); // birth date (1996-06-14) + 40 years
        assertEquals(0.0, service.yearsRemaining(now, 40), 0.001);
    }

    @Test
    void yearsRemaining_returnsFortyYears_whenNowIsTheBirthDate() {
        LocalDate now = LocalDate.of(1996, 6, 14);
        assertEquals(40.0, service.yearsRemaining(now, 40), 0.01);
    }

    @Test
    void yearsRemaining_isNegative_whenTargetAgeAlreadyPassed() {
        LocalDate now = LocalDate.of(2040, 6, 14); // 4 years after turning 40
        assertEquals(-4.0, service.yearsRemaining(now, 40), 0.01);
    }

    @Test
    void projectedValue_appliesCompoundInterestAtSevenPercent() {
        // 1000 * 1.07^10 = 1967.151357... -> rounds to 1967.15
        assertEquals(1967.15, service.projectedValue(1000.0, 10.0), 0.001);
    }

    @Test
    void projectedValue_clampsNegativeYearsToZero() {
        assertEquals(1000.0, service.projectedValue(1000.0, -5.0), 0.001);
    }

    @Test
    void projectedValue_returnsBaselineWhenYearsIsZero() {
        assertEquals(1000.0, service.projectedValue(1000.0, 0.0), 0.001);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd net-worth-service && mvn -q test -Dtest=NetWorthHistoryServiceTest`
Expected: compilation FAILURE — `NetWorthHistoryService` does not exist yet.

- [ ] **Step 3: Create the DTO**

Create `net-worth-service/src/main/java/com/isym444/networth/dto/NetWorthHistoryResponse.java`:

```java
package com.isym444.networth.dto;

import com.isym444.networth.model.NetWorthSnapshot;

import java.util.List;

public record NetWorthHistoryResponse(
        List<NetWorthSnapshot> snapshots,
        Double expectedNetWorthAt40,
        Double expectedNetWorthAt50,
        Double expectedNetWorthAt60,
        Double expectedNetWorthAt65) {
}
```

- [ ] **Step 4: Create `NetWorthHistoryService` with the pure math methods**

Create `net-worth-service/src/main/java/com/isym444/networth/service/NetWorthHistoryService.java`:

```java
package com.isym444.networth.service;

import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Read-side service for /history: fetches persisted snapshots and derives
 * projected future net worth via simple compound interest. Kept separate
 * from NetWorthSnapshotService, which is dedicated to creating snapshots
 * from external APIs.
 */
@Service
public class NetWorthHistoryService {

    // Single-user personal finance app — no config plumbing needed for either.
    private static final LocalDate BIRTH_DATE = LocalDate.of(1996, 6, 14);
    private static final double ANNUAL_GROWTH_RATE = 0.07;

    private final NetWorthSnapshotRepository repository;
    private final Clock clock;

    public NetWorthHistoryService(NetWorthSnapshotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    double yearsRemaining(LocalDate now, int targetAge) {
        LocalDate targetDate = BIRTH_DATE.plusYears(targetAge);
        return ChronoUnit.DAYS.between(now, targetDate) / 365.25;
    }

    double projectedValue(double baseline, double years) {
        double effectiveYears = Math.max(0, years);
        return round2(baseline * Math.pow(1 + ANNUAL_GROWTH_RATE, effectiveYears));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
```

- [ ] **Step 5: Add the `Clock` bean**

Edit `net-worth-service/src/main/java/com/isym444/networth/NetWorthServiceApplication.java` — add imports and a bean method.

Find:
```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.net.URISyntaxException;

@SpringBootApplication
@EnableScheduling
public class NetWorthServiceApplication {

    public static void main(String[] args) {
```

Replace with:
```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class NetWorthServiceApplication {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    public static void main(String[] args) {
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd net-worth-service && mvn -q test -Dtest=NetWorthHistoryServiceTest`
Expected: PASS (6 tests).

- [ ] **Step 7: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/dto/NetWorthHistoryResponse.java \
        net-worth-service/src/main/java/com/isym444/networth/service/NetWorthHistoryService.java \
        net-worth-service/src/test/java/com/isym444/networth/service/NetWorthHistoryServiceTest.java \
        net-worth-service/src/main/java/com/isym444/networth/NetWorthServiceApplication.java
git commit -m "Add NetWorthHistoryResponse DTO and projection math in NetWorthHistoryService"
```

---

### Task 2: `getHistory()` orchestration (currency resolution + repository read + response assembly)

**Files:**
- Modify: `net-worth-service/src/main/java/com/isym444/networth/service/NetWorthHistoryService.java`
- Modify: `net-worth-service/src/test/java/com/isym444/networth/service/NetWorthHistoryServiceTest.java`

**Interfaces:**
- Consumes: `NetWorthHistoryResponse` record and `yearsRemaining`/`projectedValue` from Task 1 — unchanged signatures.
- Consumes: `NetWorthSnapshotRepository.findByCurrencyOrderByTimestampAsc(String): List<NetWorthSnapshot>` and `.findDistinctCurrencies(): List<String>` — both already exist, unchanged.
- Produces: `public NetWorthHistoryResponse getHistory(String currency)` on `NetWorthHistoryService` — consumed by `NetWorthController` in Task 3.

- [ ] **Step 1: Write the failing tests for `getHistory()`**

Add to `net-worth-service/src/test/java/com/isym444/networth/service/NetWorthHistoryServiceTest.java` — update the import block at the top of the file to:

```java
package com.isym444.networth.service;

import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

Then append these test methods at the end of the class (before the final closing `}`):

```java
    @Test
    void getHistory_usesProvidedCurrency_andComputesProjectionsFromLatestSnapshot() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        NetWorthSnapshot older = snapshotWithGrandTotal(1000.0);
        NetWorthSnapshot latest = snapshotWithGrandTotal(2000.0);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of(older, latest));

        // Deliberately NOT a round number of years before any target age (e.g. exactly
        // 50 years before 2046-06-14 is 18262 days, not 18262.5 = 50*365.25, because of
        // how leap days fall in that window) — so the expected values below are derived
        // by calling the already-tested yearsRemaining()/projectedValue() directly with
        // this same fixed date, rather than assuming a hand-computed round number.
        LocalDate fixedToday = LocalDate.of(1996, 6, 14);
        Clock fixedClock = Clock.fixed(fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        NetWorthHistoryService service = new NetWorthHistoryService(repository, fixedClock);

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(List.of(older, latest), response.snapshots());
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 40)),
                response.expectedNetWorthAt40(), 0.0001);
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 50)),
                response.expectedNetWorthAt50(), 0.0001);
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 60)),
                response.expectedNetWorthAt60(), 0.0001);
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 65)),
                response.expectedNetWorthAt65(), 0.0001);
        verify(repository, never()).findDistinctCurrencies();
    }

    @Test
    void getHistory_fallsBackToFirstAlphabeticalCurrency_whenNoneRequested() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        when(repository.findDistinctCurrencies()).thenReturn(List.of("EUR", "GBP"));
        when(repository.findByCurrencyOrderByTimestampAsc("EUR"))
                .thenReturn(List.of(snapshotWithGrandTotal(500.0)));
        NetWorthHistoryService service = new NetWorthHistoryService(repository, Clock.systemUTC());

        NetWorthHistoryResponse response = service.getHistory(null);

        assertEquals(1, response.snapshots().size());
        verify(repository).findByCurrencyOrderByTimestampAsc("EUR");
    }

    @Test
    void getHistory_returnsEmptyResponse_whenNoCurrenciesExist() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        when(repository.findDistinctCurrencies()).thenReturn(List.of());
        NetWorthHistoryService service = new NetWorthHistoryService(repository, Clock.systemUTC());

        NetWorthHistoryResponse response = service.getHistory(null);

        assertEquals(List.of(), response.snapshots());
        assertNull(response.expectedNetWorthAt40());
        assertNull(response.expectedNetWorthAt50());
        assertNull(response.expectedNetWorthAt60());
        assertNull(response.expectedNetWorthAt65());
        verify(repository, never()).findByCurrencyOrderByTimestampAsc(anyString());
    }

    @Test
    void getHistory_returnsEmptyResponse_whenCurrencyHasNoSnapshots() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of());
        NetWorthHistoryService service = new NetWorthHistoryService(repository, Clock.systemUTC());

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(List.of(), response.snapshots());
        assertNull(response.expectedNetWorthAt40());
    }

    private NetWorthSnapshot snapshotWithGrandTotal(double grandTotal) {
        NetWorthSnapshot snapshot = new NetWorthSnapshot();
        snapshot.setGrandTotal(grandTotal);
        return snapshot;
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd net-worth-service && mvn -q test -Dtest=NetWorthHistoryServiceTest`
Expected: compilation FAILURE — `getHistory` does not exist on `NetWorthHistoryService`.

- [ ] **Step 3: Implement `getHistory()` and `resolveCurrency()`**

Edit `net-worth-service/src/main/java/com/isym444/networth/service/NetWorthHistoryService.java`.

Find:
```java
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
```

Replace with:
```java
import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
```

Find:
```java
    public NetWorthHistoryService(NetWorthSnapshotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    double yearsRemaining(LocalDate now, int targetAge) {
```

Replace with:
```java
    public NetWorthHistoryService(NetWorthSnapshotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public NetWorthHistoryResponse getHistory(String currency) {
        String resolved = resolveCurrency(currency);
        if (resolved == null) {
            return new NetWorthHistoryResponse(List.of(), null, null, null, null);
        }

        List<NetWorthSnapshot> snapshots = repository.findByCurrencyOrderByTimestampAsc(resolved);
        if (snapshots.isEmpty()) {
            return new NetWorthHistoryResponse(List.of(), null, null, null, null);
        }

        double baseline = snapshots.get(snapshots.size() - 1).getGrandTotal();
        LocalDate now = LocalDate.now(clock);
        return new NetWorthHistoryResponse(
                snapshots,
                projectedValue(baseline, yearsRemaining(now, 40)),
                projectedValue(baseline, yearsRemaining(now, 50)),
                projectedValue(baseline, yearsRemaining(now, 60)),
                projectedValue(baseline, yearsRemaining(now, 65)));
    }

    // Assumes a single-currency dataset: with no ?currency= param, falls back to
    // whichever currency sorts first alphabetically among logged snapshots. Same
    // fallback NetWorthController.resolveCurrency uses for /chart.
    private String resolveCurrency(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        List<String> currencies = repository.findDistinctCurrencies();
        return currencies.isEmpty() ? null : currencies.get(0);
    }

    double yearsRemaining(LocalDate now, int targetAge) {
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd net-worth-service && mvn -q test -Dtest=NetWorthHistoryServiceTest`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/service/NetWorthHistoryService.java \
        net-worth-service/src/test/java/com/isym444/networth/service/NetWorthHistoryServiceTest.java
git commit -m "Implement NetWorthHistoryService.getHistory() with currency resolution"
```

---

### Task 3: Wire `NetWorthController.history()` to the new service

**Files:**
- Modify: `net-worth-service/src/main/java/com/isym444/networth/controller/NetWorthController.java`
- Create: `net-worth-service/src/test/java/com/isym444/networth/controller/NetWorthControllerTest.java`

**Interfaces:**
- Consumes: `NetWorthHistoryService.getHistory(String): NetWorthHistoryResponse` from Task 2.
- Produces: `NetWorthController(NetWorthSnapshotService, NetWorthHistoryService, NetWorthSnapshotRepository, ChartService)` — new 4-arg constructor (was 3-arg). No other code in the repo constructs `NetWorthController` manually except this task's own test, so this is not a breaking change for Spring's DI (it resolves the new param automatically).

- [ ] **Step 1: Write the failing test**

Create `net-worth-service/src/test/java/com/isym444/networth/controller/NetWorthControllerTest.java`:

```java
package com.isym444.networth.controller;

import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.service.ChartService;
import com.isym444.networth.service.NetWorthHistoryService;
import com.isym444.networth.service.NetWorthSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetWorthControllerTest {

    @Test
    void history_delegatesToHistoryServiceWithTheRequestedCurrency() {
        NetWorthHistoryService historyService = mock(NetWorthHistoryService.class);
        NetWorthHistoryResponse expected = new NetWorthHistoryResponse(List.of(), null, null, null, null);
        when(historyService.getHistory("GBP")).thenReturn(expected);

        NetWorthController controller = new NetWorthController(
                mock(NetWorthSnapshotService.class),
                historyService,
                mock(NetWorthSnapshotRepository.class),
                mock(ChartService.class));

        ResponseEntity<NetWorthHistoryResponse> response = controller.history("GBP");

        assertEquals(200, response.getStatusCode().value());
        assertSame(expected, response.getBody());
        verify(historyService).getHistory("GBP");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd net-worth-service && mvn -q test -Dtest=NetWorthControllerTest`
Expected: compilation FAILURE — `NetWorthController` has no 4-arg constructor and `history()` still returns `List<NetWorthSnapshot>`.

- [ ] **Step 3: Update the controller**

Edit `net-worth-service/src/main/java/com/isym444/networth/controller/NetWorthController.java`.

Find:
```java
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.service.ChartService;
import com.isym444.networth.service.NetWorthSnapshotService;
```

Replace with:
```java
import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.service.ChartService;
import com.isym444.networth.service.NetWorthHistoryService;
import com.isym444.networth.service.NetWorthSnapshotService;
```

Find:
```java
    private final NetWorthSnapshotService snapshotService;
    private final NetWorthSnapshotRepository repository;
    private final ChartService chartService;

    public NetWorthController(NetWorthSnapshotService snapshotService,
                               NetWorthSnapshotRepository repository,
                               ChartService chartService) {
        this.snapshotService = snapshotService;
        this.repository = repository;
        this.chartService = chartService;
    }
```

Replace with:
```java
    private final NetWorthSnapshotService snapshotService;
    private final NetWorthHistoryService historyService;
    private final NetWorthSnapshotRepository repository;
    private final ChartService chartService;

    public NetWorthController(NetWorthSnapshotService snapshotService,
                               NetWorthHistoryService historyService,
                               NetWorthSnapshotRepository repository,
                               ChartService chartService) {
        this.snapshotService = snapshotService;
        this.historyService = historyService;
        this.repository = repository;
        this.chartService = chartService;
    }
```

Find:
```java
    @GetMapping("/history")
    public ResponseEntity<List<NetWorthSnapshot>> history(@RequestParam(required = false) String currency) {
        String resolved = resolveCurrency(currency);
        if (resolved == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(repository.findByCurrencyOrderByTimestampAsc(resolved));
    }
```

Replace with:
```java
    @GetMapping("/history")
    public ResponseEntity<NetWorthHistoryResponse> history(@RequestParam(required = false) String currency) {
        return ResponseEntity.ok(historyService.getHistory(currency));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd net-worth-service && mvn -q test -Dtest=NetWorthControllerTest`
Expected: PASS.

- [ ] **Step 5: Run the full backend test suite**

Run: `cd net-worth-service && mvn -q test`
Expected: PASS (all tests, including Task 1/2's `NetWorthHistoryServiceTest`).

- [ ] **Step 6: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/controller/NetWorthController.java \
        net-worth-service/src/test/java/com/isym444/networth/controller/NetWorthControllerTest.java
git commit -m "Delegate GET /history to NetWorthHistoryService"
```

---

### Task 4: Frontend — `NetWorthHistoryResponse` shape in `net-worth.service.ts`

**Files:**
- Modify: `net-worth-frontend/src/app/net-worth.service.ts`

**Interfaces:**
- Produces: `NetWorthHistoryResponse` TypeScript interface — `snapshots: NetWorthSnapshot[]`, `expectedNetWorthAt40/50/60/65: number | null` — consumed by `dashboard.component.ts` in Task 5.
- Produces: `getHistory(currency?: string): Observable<NetWorthHistoryResponse>` — return type changed from `Observable<NetWorthSnapshot[]>`.

- [ ] **Step 1: Update `net-worth.service.ts`**

Edit `net-worth-frontend/src/app/net-worth.service.ts`.

Find:
```ts
export interface NetWorthSnapshot {
  id: number;
  timestamp: string;
  currency: string;
  t212Total: number;
  cryptoTotal: number;
  pensionTotal: number;
  barclaysSharesTotal: number;
  grandTotal: number;
  grandTotalEur: number | null;
  grandTotalUsd: number | null;
  grandTotalMxn: number | null;
  grandTotalJpy: number | null;
}
```

Replace with:
```ts
export interface NetWorthSnapshot {
  id: number;
  timestamp: string;
  currency: string;
  t212Total: number;
  cryptoTotal: number;
  pensionTotal: number;
  barclaysSharesTotal: number;
  grandTotal: number;
  grandTotalEur: number | null;
  grandTotalUsd: number | null;
  grandTotalMxn: number | null;
  grandTotalJpy: number | null;
}

export interface NetWorthHistoryResponse {
  snapshots: NetWorthSnapshot[];
  expectedNetWorthAt40: number | null;
  expectedNetWorthAt50: number | null;
  expectedNetWorthAt60: number | null;
  expectedNetWorthAt65: number | null;
}
```

Find:
```ts
  getHistory(currency?: string): Observable<NetWorthSnapshot[]> {
    const url = currency
      ? `${this.baseUrl}/api/v1/history?currency=${encodeURIComponent(currency)}`
      : `${this.baseUrl}/api/v1/history`;
    return this.http.get<NetWorthSnapshot[]>(url);
  }
```

Replace with:
```ts
  getHistory(currency?: string): Observable<NetWorthHistoryResponse> {
    const url = currency
      ? `${this.baseUrl}/api/v1/history?currency=${encodeURIComponent(currency)}`
      : `${this.baseUrl}/api/v1/history`;
    return this.http.get<NetWorthHistoryResponse>(url);
  }
```

- [ ] **Step 2: Verify the project still type-checks**

Run: `cd net-worth-frontend && npm run build`
Expected: FAILS — `dashboard.component.ts:41` does `this.snapshots.set(data)` where `data` is now `NetWorthHistoryResponse`, not `NetWorthSnapshot[]`. This confirms the interface change is wired correctly (TypeScript is doing its job); Task 5 fixes the call site.

- [ ] **Step 3: Commit**

```bash
git add net-worth-frontend/src/app/net-worth.service.ts
git commit -m "Change getHistory() to return the wrapped NetWorthHistoryResponse shape"
```

---

### Task 5: Frontend — consume the new shape and display projection headline cards

**Files:**
- Modify: `net-worth-frontend/src/app/dashboard.component.ts`
- Modify: `net-worth-frontend/src/app/dashboard.component.html`

**Interfaces:**
- Consumes: `NetWorthHistoryResponse` from Task 4.

- [ ] **Step 1: Update `dashboard.component.ts`**

Edit `net-worth-frontend/src/app/dashboard.component.ts`.

Find:
```ts
  protected readonly snapshots = signal<NetWorthSnapshot[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly takingSnapshot = signal(false);
  protected readonly chartUrl = signal<string | null>(null);
```

Replace with:
```ts
  protected readonly snapshots = signal<NetWorthSnapshot[]>([]);
  protected readonly expectedNetWorth = signal<{
    at40: number | null;
    at50: number | null;
    at60: number | null;
    at65: number | null;
  } | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly takingSnapshot = signal(false);
  protected readonly chartUrl = signal<string | null>(null);
```

Find:
```ts
    this.netWorthService.getHistory().subscribe({
      next: (data) => {
        this.snapshots.set(data);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(this.extractError(err));
        this.loading.set(false);
      },
    });
```

Replace with:
```ts
    this.netWorthService.getHistory().subscribe({
      next: (data) => {
        this.snapshots.set(data.snapshots);
        this.expectedNetWorth.set({
          at40: data.expectedNetWorthAt40,
          at50: data.expectedNetWorthAt50,
          at60: data.expectedNetWorthAt60,
          at65: data.expectedNetWorthAt65,
        });
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(this.extractError(err));
        this.loading.set(false);
      },
    });
```

- [ ] **Step 2: Add the headline cards**

Edit `net-worth-frontend/src/app/dashboard.component.html`.

Find:
```html
      <div class="stat-card">
        <span>Net worth (JPY)</span>
        <strong>
          @if (latestSnapshot.grandTotalJpy !== null) {
            {{ latestSnapshot.grandTotalJpy | number: '1.2-2' }} JPY
          } @else {
            —
          }
        </strong>
      </div>
    </section>
```

Replace with:
```html
      <div class="stat-card">
        <span>Net worth (JPY)</span>
        <strong>
          @if (latestSnapshot.grandTotalJpy !== null) {
            {{ latestSnapshot.grandTotalJpy | number: '1.2-2' }} JPY
          } @else {
            —
          }
        </strong>
      </div>
      @if (expectedNetWorth(); as proj) {
        <div class="stat-card">
          <span>Net worth at 40 (2036)</span>
          <strong>
            @if (proj.at40 !== null) {
              {{ proj.at40 | number: '1.2-2' }} {{ latestSnapshot.currency }}
            } @else {
              —
            }
          </strong>
        </div>
        <div class="stat-card">
          <span>Net worth at 50 (2046)</span>
          <strong>
            @if (proj.at50 !== null) {
              {{ proj.at50 | number: '1.2-2' }} {{ latestSnapshot.currency }}
            } @else {
              —
            }
          </strong>
        </div>
        <div class="stat-card">
          <span>Net worth at 60 (2056)</span>
          <strong>
            @if (proj.at60 !== null) {
              {{ proj.at60 | number: '1.2-2' }} {{ latestSnapshot.currency }}
            } @else {
              —
            }
          </strong>
        </div>
        <div class="stat-card">
          <span>Net worth at 65 (2061)</span>
          <strong>
            @if (proj.at65 !== null) {
              {{ proj.at65 | number: '1.2-2' }} {{ latestSnapshot.currency }}
            } @else {
              —
            }
          </strong>
        </div>
      }
    </section>
```

- [ ] **Step 3: Verify the project type-checks and builds**

Run: `cd net-worth-frontend && npm run build`
Expected: PASS, no TypeScript errors.

- [ ] **Step 4: Commit**

```bash
git add net-worth-frontend/src/app/dashboard.component.ts net-worth-frontend/src/app/dashboard.component.html
git commit -m "Display projected net worth at 40/50/60/65 as headline cards"
```

---

### Task 6: Manual end-to-end verification

**Files:** none (verification only).

- [ ] **Step 1: Start local Postgres**

Run: `cd net-worth-service && docker-compose up -d postgres`
Expected: container starts and stays healthy (`docker-compose ps` shows `postgres` as `Up`).

- [ ] **Step 2: Start the backend**

Run (from `net-worth-service`, in the background): `mvn spring-boot:run`
Expected: log line `Started NetWorthServiceApplication` with no errors. `DASHBOARD_ACCESS_KEY` is unset locally, so `/api/**` is unprotected (per `AccessKeyFilter`) — no auth header needed for the next step.

- [ ] **Step 3: Confirm the new response shape**

Run: `curl -s http://localhost:8080/api/v1/history | head -c 500`
Expected: JSON object (not a bare array) starting with `{"snapshots":[...` and containing `"expectedNetWorthAt40":`, `"expectedNetWorthAt50":`, `"expectedNetWorthAt60":`, `"expectedNetWorthAt65":` keys. If there are existing snapshots in the local DB, these four values are non-null numbers; if the DB is empty, `snapshots` is `[]` and all four are `null`.

- [ ] **Step 4: Start the frontend and check the dashboard in a browser**

Run (from `net-worth-frontend`, in the background): `npm start`
Then open `http://localhost:4200` in a browser.
Expected: dashboard loads without console errors; if snapshots exist, four new headline cards ("Net worth at 40 (2036)", "...at 50 (2046)", "...at 60 (2056)", "...at 65 (2061)") appear after the JPY card, showing formatted numbers with the account's currency suffix (or `—` if a value is null).

- [ ] **Step 5: Stop background processes**

Stop the `mvn spring-boot:run` and `npm start` background processes, then run: `cd net-worth-service && docker-compose down`
