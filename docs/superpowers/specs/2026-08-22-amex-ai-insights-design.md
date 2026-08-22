# AI-generated spending insights from recent Amex transactions

## Problem

`GET /api/v1/amex/transactions?days=30` (`NetWorthController.java:55-58`, backed by
`YnabTransactionService.getRecentAmexTransactions`) already surfaces recent Amex
transactions but is read-only and unused by the frontend. There's no analysis layer on
top of it — no trends, no spending commentary, no actionable advice — and nowhere to
persist such commentary so it survives across page loads.

## Goal

- A new endpoint that fetches recent Amex transactions, sends them to an LLM (Google
  Gemini) with a purpose-built prompt, and returns a plain-text financial analysis.
- Persist that analysis onto the `snapshots` table (new `ai_commentary` column) so it's
  visible without regenerating on every page load, and so its history is preserved
  per-snapshot like every other column.
- A "Refresh insights" button on the dashboard that triggers generation on demand and
  updates the persisted value immediately.
- The daily cron snapshot always regenerates commentary fresh; every other snapshot
  event (the existing manual "Take snapshot now" button) copies the previous row's
  commentary forward instead of calling the LLM, to keep cost and latency down.

## Design

### Architecture

Three new backend pieces, following existing patterns exactly:

- **`client/GeminiClient.java`** — `@Component`, `RestClient`-based (mirrors
  `ExchangeRateClient`), calls Gemini's `generateContent` REST endpoint. The API key is
  a query parameter, so like `ExchangeRateClient` it never logs the raw exception or URI
  on failure (only a generic message) to avoid leaking the key into logs.
- **`service/AmexInsightService.java`** — new `@Service`. Depends on
  `YnabTransactionService` (to fetch transactions), `GeminiClient` (to generate text),
  and `NetWorthSnapshotRepository` (to persist onto the latest row(s) on manual
  refresh). Two public methods: `generateCommentary()` (fetch + prompt + call LLM,
  returns text, no persistence) and `refreshAndPersist()` (calls `generateCommentary()`
  then writes the result onto the latest snapshot row for every currency).
- **`NetWorthSnapshotService`** gains a dependency on `AmexInsightService` (generation
  only) and a `boolean regenerateCommentary` parameter on `takeSnapshot`.

### Schema change

Add a nullable text column to `NetWorthSnapshot` (`model/NetWorthSnapshot.java`):

```java
@Column(name = "ai_commentary", columnDefinition = "text")
private String aiCommentary;
```

Plus getter/setter. `ddl-auto: update` adds the column automatically on next deploy —
same mechanism already used for `amount_left` (see the comment at
`NetWorthSnapshot.java:38-41`). No manual migration needed.

### Persistence semantics — copy-forward vs. regenerate

`NetWorthSnapshotService.takeSnapshot()` (currently no-arg, `NetWorthSnapshotService.java:109`)
becomes:

```java
public List<NetWorthSnapshot> takeSnapshot() {
    return takeSnapshot(false);
}

public List<NetWorthSnapshot> takeSnapshot(boolean regenerateCommentary) {
    ...
    String freshCommentary = null;
    if (regenerateCommentary) {
        try {
            freshCommentary = amexInsightService.generateCommentary();
        } catch (Exception e) {
            log.warn("AI commentary generation failed, falling back to carried-forward value", e);
        }
    }
    ... existing per-currency loop ...
        NetWorthSnapshot previous = repository.findFirstByCurrencyOrderByTimestampDescIdDesc(currency).orElse(null);
        ...
        snapshot.setAiCommentary(freshCommentary != null
                ? freshCommentary
                : (previous != null ? previous.getAiCommentary() : null));
    ...
}
```

The LLM is called **once per batch**, before the per-currency loop, so every row created
in the same snapshot event gets identical commentary text (commentary is about
transactions, not currency-specific figures — there's no reason for it to differ between
a GBP row and a USD row from the same run).

- `scheduledSnapshot()` (`NetWorthSnapshotService.java:98-107`) calls
  `takeSnapshot(true)` — always fresh.
- The existing `POST /api/v1/snapshot` controller endpoint keeps calling the no-arg
  `takeSnapshot()` — unchanged signature, now implicitly `regenerateCommentary=false`
  (copy-forward), matching current "Take snapshot now" button behavior.
- If Gemini fails during either path, the snapshot still saves — commentary just falls
  back to whatever the previous row had (or stays `null` if there's no history yet).
  This is deliberately more lenient than the Trading212/Crypto.com/YNAB calls (which
  abort the entire snapshot on failure) — commentary is auxiliary, like the EUR/USD/MXN/JPY
  conversions, not core net-worth data.

### New endpoint: manual refresh

```java
@PostMapping("/amex/insights")
public ResponseEntity<AmexInsightsResponse> refreshAmexInsights() {
    return ResponseEntity.ok(amexInsightService.refreshAndPersist());
}
```

New DTO `dto/AmexInsightsResponse.java`:

```java
public record AmexInsightsResponse(String commentary, Instant generatedAt, int transactionCount) {}
```

`AmexInsightService.refreshAndPersist()`:

```java
public AmexInsightsResponse refreshAndPersist() {
    List<AmexTransaction> transactions = ynabTransactionService.getRecentAmexTransactions(LOOKBACK_DAYS);
    String commentary = geminiClient.generateContent(model, buildPrompt(transactions), apiKey);
    for (String currency : repository.findDistinctCurrencies()) {
        repository.findFirstByCurrencyOrderByTimestampDescIdDesc(currency)
                .ifPresent(row -> { row.setAiCommentary(commentary); repository.save(row); });
    }
    return new AmexInsightsResponse(commentary, Instant.now(), transactions.size());
}
```

This does **not** create new snapshot rows — it only rewrites `ai_commentary` on
whatever the current latest row is per currency, per your requirement. It updates every
currency's latest row (not just one), since multiple currency rows exist per snapshot
batch and there's no reliably "primary" one to single out — this keeps behavior
consistent regardless of which currency happens to be displayed.

No unhandled-config guard is needed beyond what `GeminiClient` already throws
(`IllegalStateException` if the API key is blank) — that propagates as a 500 with a
message, exactly like `YnabTransactionService`'s existing "not configured" errors, which
the frontend's `extractError` already renders.

### Prompt design

`LOOKBACK_DAYS = 30` (matches the existing transactions endpoint's default). Transactions
are serialized to JSON via the injected Jackson `ObjectMapper` and embedded directly in
the prompt text (no `systemInstruction` field needed — Gemini's `generateContent` works
fine with a single combined prompt for a task this size):

```
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
<serialized array>
```

Plain text (not markdown) is deliberate: the frontend has no markdown renderer today, and
adding one is out of scope for this feature (YAGNI) — the 250-word cap also keeps output
tokens, and therefore cost, bounded.

### Gemini API call

`GeminiClient.generateContent(String model, String prompt, String apiKey)`:

- `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}`
- Body: `{"contents":[{"parts":[{"text": prompt}]}]}`
- Response text extracted from `candidates[0].content.parts[0].text`.
- Timeouts: 5s connect / 30s read (generation is slower than the 5s used for
  `ExchangeRateClient`'s simple lookup).
- On any `RestClientException`, log a generic message only (never the raw exception,
  since it embeds the URI with the API key in it) and throw `IllegalStateException`,
  exactly mirroring `ExchangeRateClient.java:44-49`.

### Configuration

`application.yml`, new block under `networth`:

```yaml
networth:
  llm:
    gemini:
      api-key: ${GEMINI_API_KEY:}
      model: ${GEMINI_MODEL:gemini-2.5-flash}
```

`render.yaml`, new entries under `net-worth-service.envVars`:

```yaml
      - key: GEMINI_API_KEY
        sync: false
      - key: GEMINI_MODEL
        value: gemini-2.5-flash
```

Local `.env` gets a matching `GEMINI_API_KEY=` entry (gitignored, same as existing keys).

### Frontend changes

- **`net-worth.service.ts`**: add `aiCommentary: string | null` to the `NetWorthSnapshot`
  interface (flows through automatically via the existing `/history` endpoint — no new
  fetch needed just to *display* it). Add:
  ```ts
  export interface AmexInsightsResponse {
    commentary: string;
    generatedAt: string;
    transactionCount: number;
  }

  refreshAmexInsights(): Observable<AmexInsightsResponse> {
    return this.http.post<AmexInsightsResponse>(`${this.baseUrl}/api/v1/amex/insights`, {});
  }
  ```
- **`dashboard.component.ts`**: add `refreshingInsights = signal(false)`. New method:
  ```ts
  refreshInsights(): void {
    this.refreshingInsights.set(true);
    this.error.set(null);
    this.netWorthService.refreshAmexInsights().subscribe({
      next: () => { this.refreshingInsights.set(false); this.loadHistory(false); },
      error: (err) => { this.refreshingInsights.set(false); this.error.set(this.extractError(err)); },
    });
  }
  ```
  Reloading history (rather than using the response body directly) keeps `latest()`'s
  `aiCommentary` as the single source of truth, same pattern already used by
  `takeSnapshot()`.
- **`dashboard.component.html`**: new card inside the existing `@if (latest(); as
  latestSnapshot)` block (or immediately after it), with its own header + "Refresh
  insights" button (spinner state identical to the existing "Take snapshot now" button)
  and a `white-space: pre-wrap` text block showing `latestSnapshot.aiCommentary`, falling
  back to a "No insights yet" message when null.

### Error handling summary

- Snapshot creation (cron or manual "Take snapshot now"): Gemini failure never blocks
  the snapshot — falls back to carried-forward commentary, logs a warning.
- `POST /api/v1/amex/insights`: Gemini failure (or missing API key, or YNAB fetch
  failure) propagates as a 500 with a message body, surfaced via the frontend's existing
  `extractError`. No snapshot rows are touched if this fails — `ai_commentary` on the
  latest row(s) is only overwritten after `geminiClient.generateContent` succeeds.

### Testing

No existing Java or Angular test suite in this project (consistent with prior specs,
e.g. `2026-08-07-history-projection-dto-design.md`) — no new test infrastructure is
scaffolded for this change.

## Out of scope

- Markdown rendering on the frontend.
- Using net-worth totals (beyond transactions) as additional LLM context.
- A history/list view of past commentary (only the latest per currency is ever surfaced
  in the UI, even though it's technically preserved per-row going forward).
- Retry logic for Gemini failures — a single attempt, fail-soft as described above.
