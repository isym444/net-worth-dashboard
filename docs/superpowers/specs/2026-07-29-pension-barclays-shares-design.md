# Net worth dashboard: pension + Barclays shares carry-forward

## Context

The user manually added two columns to the `snapshots` table via the Supabase table editor: `pension_total` and `barclays_shares_total` (both `float8`), and backfilled historical rows with real values (most recently `10577.98` / `1065.78`). Neither value comes from an external API — Trading212 and Crypto.com are the only two sources `NetWorthSnapshotService` fetches from (`net-worth-service/src/main/java/com/isym444/networth/service/NetWorthSnapshotService.java`). The user will keep updating these two figures by hand, directly in the database, whenever their real pension or Barclays shares value changes. This spec wires the existing JPA entity, the snapshot-taking logic, and the frontend up to those two columns.

## Goals

- Every new snapshot row (scheduled or on-demand) carries forward the most recently saved `pension_total`/`barclays_shares_total` for that snapshot's currency, instead of leaving them null/zero.
- `grand_total` includes both new values: `t212Total + cryptoTotal + pensionTotal + barclaysSharesTotal`.
- The dashboard displays both values: a headline stat card each, and a table column each.

## Non-goals

- No UI to edit `pension_total`/`barclays_shares_total` — they stay a direct-DB-edit workflow, same as today.
- No new chart series for pension/Barclays shares — the existing "Net worth" line already reflects the updated grand total; Trading212/Crypto.com/Net worth remain the only three chart series.
- No database migration — `spring.jpa.hibernate.ddl-auto: update` (`net-worth-service/src/main/resources/application.yml:16`) means Hibernate reconciles the entity against the already-existing columns with no explicit migration step.
- No test suite addition — consistent with the rest of this project (documented non-goal in prior specs), verification is manual.

## Backend changes (`net-worth-service`)

### `NetWorthSnapshot.java`

Add two fields, following the exact pattern of the existing `t212Total`/`cryptoTotal` fields:

```java
@Column(name = "pension_total", nullable = false)
private Double pensionTotal;

@Column(name = "barclays_shares_total", nullable = false)
private Double barclaysSharesTotal;
```

Plus standard getter/setter pairs (`getPensionTotal`/`setPensionTotal`, `getBarclaysSharesTotal`/`setBarclaysSharesTotal`), placed alongside the existing getters/setters in field-declaration order.

### `NetWorthSnapshotRepository.java`

Add one derived-query method to fetch the latest existing row for a currency:

```java
Optional<NetWorthSnapshot> findFirstByCurrencyOrderByTimestampDesc(String currency);
```

(Requires adding `java.util.Optional` to the imports.)

### `NetWorthSnapshotService.takeSnapshot()`

Inside the per-currency loop (`for (Map.Entry<String, double[]> entry : totalsByCurrency.entrySet())`), **before** constructing the new `NetWorthSnapshot` for that currency: query `repository.findFirstByCurrencyOrderByTimestampDesc(entry.getKey())`. If present, read `pensionTotal`/`barclaysSharesTotal` off that row; if absent (first-ever snapshot for a brand-new currency), default both to `0.0`. Set both on the new snapshot, and include them in the `grandTotal` calculation alongside the existing `t212Total + cryptoTotal`.

This lookup must happen before `repository.save(snapshot)` for that currency, since saving would otherwise make the new (not-yet-carrying-real-data) row the "latest" one for any subsequent lookup within the same run.

## Frontend changes (`net-worth-frontend`)

### `net-worth.service.ts`

Add to the `NetWorthSnapshot` interface, after `cryptoTotal`:

```typescript
pensionTotal: number;
barclaysSharesTotal: number;
```

(Field names match the backend's camelCase JSON keys exactly, same convention as the existing fields — no naming-strategy translation happens today, so none is introduced here.)

### `dashboard.component.html`

Two new `.stat-card` divs in the `.headline` section, positioned between the Crypto.com card and the Net worth card:

```html
<div class="stat-card">
  <span>Pension</span>
  <strong>{{ latestSnapshot.pensionTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
</div>
<div class="stat-card">
  <span>Barclays Shares</span>
  <strong>{{ latestSnapshot.barclaysSharesTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
</div>
```

Two new `<th>`/`<td>` pairs in the history table, in the same relative position (between Crypto.com and Net worth): header cells `Pension` and `Barclays Shares`, body cells `{{ row.pensionTotal | number: '1.2-2' }}` and `{{ row.barclaysSharesTotal | number: '1.2-2' }}`.

### `dashboard.component.css`

`.headline`'s `grid-template-columns` changes from the fixed `repeat(3, 1fr)` to `repeat(auto-fit, minmax(160px, 1fr))`, so 5 cards lay out cleanly on wide screens and reflow on narrower ones, while the existing `@media (max-width: 640px)` rule (which forces `1fr`) continues to force a single column on mobile. No other rule changes — `.table-card`'s existing `overflow-x: auto` already handles the two extra table columns on narrow viewports.

## Testing

No automated test suite exists for this backend or frontend (consistent with prior specs' non-goals). This dev environment also has no real Trading212/Crypto.com API credentials configured, so `/api/v1/snapshot` already fails today with "No API keys configured" before reaching the per-currency loop — unrelated to this change, but it means the carry-forward logic cannot be exercised end-to-end through the live endpoint in this environment. Verification instead:
- `mvn compile` for the backend.
- Direct SQL against the running Postgres container (`docker exec` into `net-worth-service-postgres-1`) to confirm the equivalent of `findFirstByCurrencyOrderByTimestampDesc` returns the expected latest row per currency, validating the query logic independent of the credential-gated endpoint.
- `ng build` for the frontend, plus a manual browser check that the two new cards/columns render with the existing (already-populated) historical data.
