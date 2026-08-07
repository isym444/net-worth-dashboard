# History endpoint: extendable DTO with net worth projections

## Problem

`GET /api/v1/history` currently returns `List<NetWorthSnapshot>` fetched directly by
the controller from `NetWorthSnapshotRepository`. There's no room to add derived,
non-persisted fields (like a projected future net worth) without polluting the JPA
entity, and the controller bypasses the service layer entirely for this endpoint.

## Goal

- Return a custom, extendable response DTO from `/history` instead of the raw entity list.
- Add four derived fields to that DTO: `expectedNetWorthAt40`, `expectedNetWorthAt50`,
  `expectedNetWorthAt60`, `expectedNetWorthAt65` — projected net worth via compound
  interest, computed from the latest snapshot's `grandTotal`.
- The controller should call a service method for `/history`, not the repository directly.
  The service method calls the repository and also builds the projection fields.

## Design

### Architecture

A new read-side `NetWorthHistoryService` is added, separate from the existing
`NetWorthSnapshotService`. `NetWorthSnapshotService` is dedicated to *creating*
snapshots by calling external APIs (Trading212, Crypto.com, exchange rates) — mixing
in projection math for a read endpoint would blur that responsibility. This mirrors
the existing split with `ChartService` (also a small dedicated service consuming the
repository for a single read concern).

`NetWorthController.history()` becomes a one-line delegation to
`NetWorthHistoryService.getHistory(currency)`. The controller no longer touches
`NetWorthSnapshotRepository` for this endpoint. The `/chart` endpoint is unchanged
(out of scope) and keeps its own repository access and currency resolution.

### New DTO: `dto/NetWorthHistoryResponse.java`

A new `dto` package holds API-shape types that aren't JPA entities. The DTO is a
Java record:

```java
public record NetWorthHistoryResponse(
        List<NetWorthSnapshot> snapshots,
        Double expectedNetWorthAt40,
        Double expectedNetWorthAt50,
        Double expectedNetWorthAt60,
        Double expectedNetWorthAt65) {
}
```

### New service: `service/NetWorthHistoryService.java`

Depends on `NetWorthSnapshotRepository`. Public method:

```java
public NetWorthHistoryResponse getHistory(String currency)
```

Steps:
1. Resolve the currency: if `currency` is provided, use it; otherwise fall back to
   the first of `repository.findDistinctCurrencies()` alphabetically. This is the
   same fallback logic currently living in `NetWorthController.resolveCurrency`,
   moved here so the controller no longer needs repository access for `/history`.
   (The controller keeps its own copy for `/chart`, which is out of scope.)
2. If no currency resolves, return `NetWorthHistoryResponse` with an empty list and
   all four projection fields `null`.
3. Otherwise fetch `repository.findByCurrencyOrderByTimestampAsc(resolved)`.
4. Build the four projection fields from the **latest** snapshot (last element of
   the ascending-ordered list). If the list is empty, all four fields are `null`.

### Projection math

- Birth date is a hardcoded constant: `LocalDate.of(1996, 6, 14)` — this is a
  single-user personal finance app, no config plumbing needed.
- For each target age (40, 50, 60, 65), compute `yearsRemaining` as the exact
  fractional number of years between **today** (`LocalDate.now()`, at request time
  — not the snapshot's timestamp, so the projection keeps moving forward each time
  the endpoint is hit even between snapshots) and `birthDate.plusYears(targetAge)`:
  `ChronoUnit.DAYS.between(today, birthDate.plusYears(targetAge)) / 365.25`.
- If `yearsRemaining` is negative (target age already passed), clamp to `0` so the
  projection never compounds backward — it simply returns the current grand total
  for that field. (Not expected to trigger for ages 40/50/60/65 given a 1996 birth
  year and current date, but guards against the endpoint being hit decades from now.)
- `FV = latestGrandTotal * Math.pow(1.07, yearsRemaining)`, rounded to 2dp using the
  same `round2` pattern already used in `NetWorthSnapshotService`.
- Growth rate `0.07` (7%) is a private constant on `NetWorthHistoryService`.

### Controller change

```java
@GetMapping("/history")
public ResponseEntity<NetWorthHistoryResponse> history(@RequestParam(required = false) String currency) {
    return ResponseEntity.ok(historyService.getHistory(currency));
}
```

The controller's `repository` field is retained (still used by `/chart`), but is no
longer referenced by `history()`.

### Error handling

- No snapshots for the resolved currency, or no currency at all → `snapshots: []`,
  all four projection fields `null`. Same externally-visible behavior as today's
  empty-list response, just wrapped in the new DTO shape.
- `/chart` endpoint is untouched.

### Testing

No existing Java test suite in this project (confirmed: no files under `test/`).
Consistent with the rest of the codebase, no new test infrastructure is scaffolded
for this change.

### Frontend changes

The Angular frontend (`net-worth-frontend`) calls `GET /history` expecting a bare
`NetWorthSnapshot[]` (`net-worth.service.ts:26-31`), consumed by
`dashboard.component.ts:39-49`. Wrapping the response in a DTO breaks this, so it's
updated in the same change, following the existing pattern used for the
EUR/USD/MXN/JPY headline cards (commit `9b5aa06`):

- `net-worth.service.ts`: add a `NetWorthHistoryResponse` interface (`snapshots:
  NetWorthSnapshot[]`, `expectedNetWorthAt40/50/60/65: number | null`). Change
  `getHistory()`'s return type from `Observable<NetWorthSnapshot[]>` to
  `Observable<NetWorthHistoryResponse>`.
- `dashboard.component.ts`: add a signal holding the four projection values (e.g.
  `expectedNetWorth = signal<{ at40, at50, at60, at65 } | null>(null)`). In
  `loadHistory()`, set `this.snapshots` from `data.snapshots` and
  `this.expectedNetWorth` from the four DTO fields.
- `dashboard.component.html`: inside the existing `@if (latest(); as
  latestSnapshot)` headline section, add four more `stat-card` divs — "Net worth at
  40 (2036)", "...at 50 (2046)", "...at 60 (2056)", "...at 65 (2061)" — each reading
  from `expectedNetWorth()`, formatted the same way as the EUR/USD/MXN/JPY cards
  (`number: '1.2-2'` + `latestSnapshot.currency` suffix, `—` fallback when null).
  Target year in each label is computed as `currentYear + yearsRemaining`, i.e.
  `2026 + (40 - 30) = 2036`, etc. — hardcoded in the template since the birth year
  is fixed.

## Out of scope

- `/chart` endpoint and its repository access.
