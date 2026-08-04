# Net worth snapshot: multi-currency conversion

## Context

The user added four columns to the production `snapshots` table via the Supabase table editor: `grand_total_EUR`, `grand_total_USD`, `grand_total_MXP`, `grand_total_JPY` (all `bigint`, nullable, quoted mixed-case identifiers). They want each new snapshot's `grand_total` converted into these currencies via a live exchange-rate API call and stored alongside the existing totals — visible in the raw data only, not on the dashboard chart.

Investigation before design turned up three problems with the columns as originally created (confirmed via `information_schema.columns` against the live production DB, and a live test call against the actual API):
- `grand_total_MXP` isn't a valid/active ISO 4217 currency code (it was the pre-1993 Mexican peso, retired for decades) — the exchange-rate API rejects it outright (`{"success":false,"error":"invalid_currencies"}`). The correct code is `MXN`.
- All four columns are `bigint` (whole numbers only), inconsistent with every other total column in this table (`t212_total`, `crypto_total`, `grand_total`, `pension_total`, `barclays_shares_total`), all `double precision`.
- The columns are quoted mixed-case identifiers (`"grand_total_EUR"`, not `grand_total_eur`) — this requires Hibernate's backtick-quoting syntax in `@Column(name = ...)`, otherwise generated SQL would be unquoted and Postgres would fold it to lowercase, causing a "column does not exist" failure at runtime.

Resolved with the user: rename `grand_total_MXP` → `grand_total_MXN`, change all four columns to `double precision`, and treat the exchange-rate call as best-effort (a failure leaves the four columns null on that row rather than blocking the whole snapshot) — deliberately different from the strict all-or-nothing behavior just added for Trading212/Crypto.com, since these conversions are auxiliary data, not core to `grand_total` or the chart.

## Goals

- Every new snapshot (scheduled or on-demand) converts its `grand_total` into EUR, USD, MXN, and JPY via a live call to fxratesapi.com, storing the results in the four (renamed, retyped) columns.
- The conversion is computed from the snapshot's own `currency` field as the API's `base` parameter (not hardcoded to GBP), consistent with the rest of the codebase's per-currency design, even though in practice this deployment only ever uses GBP.
- A failed exchange-rate call (network error, API error, missing credential) logs a warning and leaves the four new fields `null` on that snapshot — it never blocks the snapshot's core data (`grand_total`, Trading212/Crypto.com totals, pension/Barclays carry-forward) from saving.

## Non-goals

- No display of these values anywhere in the frontend — not requested, and explicitly not added to the chart (`ChartService.java` is untouched; it continues to plot only Trading212/Crypto.com/Net worth).
- No historical backfill of existing rows' currency columns — only new snapshots going forward get populated.
- No new HTTP client library. The user's example used `org.asynchttpclient.AsyncHttpClient`, not currently a dependency; both existing external clients (`Trading212Client`, `CryptoComClient`) use Spring's synchronous `RestClient`, and `takeSnapshot()` already runs everything sequentially in one thread — there's no concurrency to exploit, so the new client follows the same synchronous `RestClient` pattern instead of adding a new dependency.
- No configurable target-currency list — EUR/USD/MXN/JPY are hardcoded to match the four columns that exist; adding a fifth currency later means adding both a column and a code change, not a config toggle.

## Database changes (production, via direct SQL — `ddl-auto: update` cannot rename or retype existing columns)

```sql
ALTER TABLE snapshots RENAME COLUMN "grand_total_MXP" TO "grand_total_MXN";
ALTER TABLE snapshots ALTER COLUMN "grand_total_EUR" TYPE double precision USING "grand_total_EUR"::double precision;
ALTER TABLE snapshots ALTER COLUMN "grand_total_USD" TYPE double precision USING "grand_total_USD"::double precision;
ALTER TABLE snapshots ALTER COLUMN "grand_total_MXN" TYPE double precision USING "grand_total_MXN"::double precision;
ALTER TABLE snapshots ALTER COLUMN "grand_total_JPY" TYPE double precision USING "grand_total_JPY"::double precision;
```

All four columns are currently empty (`null`) on every existing row, so the type change is a no-op conversion with no data-loss risk.

## Backend changes (`net-worth-service`)

### New: `ExchangeRateClient.java` (`com.isym444.networth.client`)

Follows the `CryptoComClient`/`Trading212Client` pattern: a `@Component` wrapping a `RestClient.create()` instance, one method:

```java
JsonNode fetchRates(String baseCurrency, String targetCurrencies, String apiKey)
```

Calls `GET https://api.fxratesapi.com/latest?base={baseCurrency}&currencies={targetCurrencies}&resolution=1m&amount=1&places=6&format=json&api_key={apiKey}` and returns the parsed response. Throws `IllegalStateException` if the response is null or `"success": false` — same shape as `CryptoComClient.get()`'s error handling. The base URL is a private constant (never varies, matching `CryptoComClient`'s pattern), not configurable.

### `NetWorthSnapshot.java`

Four new nullable fields, using backtick-quoted column names so Hibernate emits them properly quoted (required because the real columns are mixed-case):

```java
@Column(name = "`grand_total_EUR`")
private Double grandTotalEur;

@Column(name = "`grand_total_USD`")
private Double grandTotalUsd;

@Column(name = "`grand_total_MXN`")
private Double grandTotalMxn;

@Column(name = "`grand_total_JPY`")
private Double grandTotalJpy;
```

Plus standard getter/setter pairs.

### `NetWorthSnapshotService.java`

New `@Value("${networth.exchange-rate.api-key}")` field for the API key. Inside the per-currency loop, immediately after `grandTotal` is computed and before the snapshot is saved: call a new private helper that fetches rates for that currency's `grand_total` and sets the four new fields (or leaves them `null` on any failure — network error, non-success API response, or a blank API key). This never throws — it's the one part of `takeSnapshot()` that deliberately swallows its own errors, logging at `WARN`, consistent with the "auxiliary, best-effort" design goal (in contrast to the Trading212/Crypto.com calls earlier in the same method, which now deliberately throw and abort the whole snapshot on failure).

## Config changes

- `application.yml`: new `networth.exchange-rate.api-key: ${EXCHANGE_RATE_API_KEY:}` entry, following the existing credential pattern (blank-by-default, matching how every other credential in this file is declared).
- `render.yaml`: new `EXCHANGE_RATE_API_KEY` env var on the `net-worth-service` block, `sync: false` (matching `DASHBOARD_ACCESS_KEY` and the Trading212/Crypto.com credentials — manually set in Render's dashboard, not stored in the blueprint).
- `docker-compose.yml`: new `EXCHANGE_RATE_API_KEY: ${EXCHANGE_RATE_API_KEY:-}` passthrough on the `app` service's environment block, matching every other credential.
- The user sets the real key in their own local `.env` and in Render's dashboard — this spec doesn't reference or need to see the actual key value.

## Testing

No automated test suite exists for this backend (consistent with prior specs' non-goals). Verification:
- `mvn compile` for the backend.
- A direct call against the live API (already done during this design phase) confirms the corrected params (`base=GBP&currencies=EUR,USD,MXN,JPY`) return a valid response shape.
- After deploying, confirm via `GET /api/v1/history` (or a direct SQL check) that a newly-taken snapshot has non-null, plausible values in all four new columns, and that a snapshot taken with a deliberately-blank `EXCHANGE_RATE_API_KEY` still saves successfully with the four columns null and a `WARN` log line.
