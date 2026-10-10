# net-worth-service

Spring Boot microservice that snapshots your Trading212 (ISA + Invest) and
Crypto.com balances once a day, stores each snapshot as a row in Postgres,
and serves the history back as JSON or a PNG line chart. Same idea as the
local `trading212_balance.py` / `net_worth_history.py` scripts in this
workspace, rebuilt as a deployable service with Postgres instead of SQLite.

Neither Trading212 nor Crypto.com expose a "portfolio value over time" API —
both only return a live snapshot. This service is what builds the history:
it calls both APIs once a day (configurable), and each call adds one more
data point.

## Stack

- Java 21, Spring Boot 3.3 (Web, Data JPA, Actuator, Scheduling)
- Postgres (via Spring Data JPA)
- JFreeChart for the PNG line chart
- Multi-stage Dockerfile (Maven build → JRE runtime)

## Configuration (environment variables)

| Variable | Required | Description |
|---|---|---|
| `DATABASE_URL` | one of these | Postgres connection as `postgres://user:pass@host:port/db` (Render's default format — auto-translated to a JDBC URL at startup) |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | two DB options | Set these directly instead if you'd rather not rely on `DATABASE_URL` parsing |
| `TRADING212_API_KEY_ISA` / `TRADING212_API_SECRET_ISA` | no | Trading212 Stocks ISA account key (secret only needed for newer key/secret pairs) |
| `TRADING212_API_KEY_INVEST` / `TRADING212_API_SECRET_INVEST` | no | Trading212 Invest account key |
| `TRADING212_ENV` | no | `live` (default) or `demo` |
| `CRYPTO_COM_KEY` / `CRYPTO_COM_SECRET` | no | Crypto.com App API key pair |
| `YNAB_API_TOKEN` | no | YNAB Personal Access Token (Account Settings → Developer Settings in the YNAB web app) |
| `YNAB_BUDGET_ID` | no | YNAB budget ID to read from. Default `last-used` (YNAB's alias for your most recently used budget) |
| `YNAB_CHASE_ACCOUNT_IDS` | no | Comma-separated YNAB account IDs summed into `chase_total`. YNAB account names carry no bank identity (e.g. "CreditCard – 1002"), so this is matched by ID, not name — find IDs via `GET /budgets/{budget_id}/accounts`. |
| `YNAB_AMEX_ACCOUNT_IDS` | no | Comma-separated YNAB account IDs summed into `amex_total` (stored as debt magnitude) and returned by `/api/v1/amex/transactions`. |
| `SNAPSHOT_CRON` | no | Cron expression (6-field, Spring format: sec min hour day month weekday) for the daily job. Default `0 0 7 * * *` (07:00) |
| `SNAPSHOT_ZONE` | no | Timezone for `SNAPSHOT_CRON`. Default `UTC` |
| `PORT` | no | Listen port. Default `8080`; unset in production (the VPS compose file doesn't set it) |
| `DASHBOARD_ACCESS_KEY` | no | Shared secret required in the `X-Api-Key` header on every `/api/**` request. Leave unset to disable auth entirely (e.g. local dev). |
| `LLM_BASE_URL` | no | Base URL of your local OpenAI-compatible LLM server (calls `{base}/v1/chat/completions`), used for Amex/health insights. Default `http://llm.isym.uk:11434/v1`. |
| `LLM_MODEL` | no | Model name sent to the local server. Default `qwen3.8:27b-q4_K_M` |
| `LLM_API_KEY` | no | Optional bearer token, only if your local server requires one. |
| `LLM_REASONING_EFFORT` | no | Sent as `reasoning_effort` to the server. Default `none` disables Qwen3-style thinking (much faster). Set blank to omit the field. |
| `FRONTEND_ORIGIN` | no | Origin(s) allowed to call the API via CORS (comma-separated). Default `http://localhost:4200` for local `ng serve`. |

Every credential is optional in isolation — leave a pair blank to skip that
source. At least one Trading212 account or Crypto.com must be configured or
snapshots will fail with "No API keys configured".

## API

| Endpoint | Description |
|---|---|
| `POST /api/v1/snapshot` | Fetch balances right now and save a row (same logic the cron job runs), except it copies the previous row's AI commentary forward instead of regenerating it — only the cron job calls the LLM. Returns the saved row(s) as JSON. |
| `GET /api/v1/history?currency=GBP` | Snapshot history for a currency plus projected net worth at a few future ages. `currency` defaults to whichever currency has been logged. See response shape below. |
| `GET /api/v1/chart?currency=GBP` | PNG line chart (Trading212 / Crypto.com / net worth) rendered from the history. |
| `GET /api/v1/amex/transactions?days=30` | Recent transactions from any YNAB account with "amex" in its name, newest first. `days` defaults to 30. |
| `POST /api/v1/amex/insights` | Fetch recent Amex transactions, ask the local LLM to analyze them, and overwrite `ai_commentary` on the latest snapshot row for every currency. Returns `{"commentary": "...", "generatedAt": "...", "transactionCount": 12}`. Requires a reachable local LLM (`LLM_BASE_URL`). |
| `GET /actuator/health` | Health check |

`GET /api/v1/history` returns a JSON object, not a bare array:

```json
{
  "snapshots": [ /* oldest first — same rows the array used to contain */ ],
  "expectedNetWorthAt40": 123456.78,
  "expectedNetWorthAt50": 234567.89,
  "expectedNetWorthAt60": 345678.90,
  "expectedNetWorthAt65": 400000.00
}
```

The four `expectedNetWorthAt*` fields are a simple compound-interest projection
of the latest snapshot's grand total (fields are `null` if there's no snapshot
history yet to project from).

Note: if snapshots ever span multiple currencies, only one (the alphabetically
first, or whichever `?currency=` is passed) is shown by default — see
`resolveCurrency()` in `NetWorthHistoryService` (for `/history`) and the
identical fallback copied into `NetWorthController` (for `/chart`).

## Frontend

A small Angular dashboard lives at `../net-worth-frontend`. It's deployed as its own
Render static site (see the repo-root `render.yaml`) and talks to this API directly
from the browser, so both `DASHBOARD_ACCESS_KEY` and `FRONTEND_ORIGIN` matter once
it's live — see `net-worth-frontend/README.md`.

## Run locally

Requires Docker (bundles its own Postgres via Compose — no local Java/Maven needed):

```bash
export TRADING212_API_KEY_ISA=...
export TRADING212_API_KEY_INVEST=...
export CRYPTO_COM_KEY=...
export CRYPTO_COM_SECRET=...
export YNAB_API_TOKEN=...
export YNAB_CHASE_ACCOUNT_IDS=...
export YNAB_AMEX_ACCOUNT_IDS=...
export LLM_BASE_URL=http://host.docker.internal:11434
docker compose up --build
```

Then:

```bash
curl -X POST http://localhost:8080/api/v1/snapshot
curl http://localhost:8080/api/v1/history
# => {"snapshots": [...], "expectedNetWorthAt40": ..., "expectedNetWorthAt50": ..., "expectedNetWorthAt60": ..., "expectedNetWorthAt65": ...}
curl http://localhost:8080/api/v1/chart -o chart.png
curl "http://localhost:8080/api/v1/amex/transactions?days=30"
curl -X POST http://localhost:8080/api/v1/amex/insights
```

## Deploy

The backend runs as a Docker container on a Hostinger VPS, behind Traefik
(auto HTTPS via Let's Encrypt) at **https://nwapi.isym.uk**. The database
stays on Supabase regardless — only the backend container moved.

1. **Image build**: `.github/workflows/backend-docker-publish.yml` builds
   this directory's `Dockerfile` and pushes to
   `ghcr.io/isym444/net-worth-tracker-backend` on every push touching
   `net-worth-service/**`, then calls the Hostinger VPS API to redeploy.
   The GHCR package must be public (Package settings → Visibility) so the
   VPS can pull without registry credentials.
2. **Hostinger Docker Manager**: project `net-worth-api` on VPS `1995417`,
   configured via the **`.yaml editor` tab** (not the visual editor's
   Environment fields — those silently fail to apply env vars). See
   `deploy/hostinger-docker-compose.template.yml` for the compose shape;
   paste a filled-in copy (real secret values, never committed) into that
   editor.
3. **Database**: `SPRING_DATASOURCE_URL` points at Supabase's **Session
   Pooler** host (IPv4-compatible), not the direct `db.<ref>.supabase.co`
   host (IPv6-only without a paid add-on).
4. **Auto-redeploy**: the workflow's `redeploy` job calls
   `POST /vps/v1/virtual-machines/1995417/docker/net-worth-api/update`
   (Hostinger API, token in the `HOSTINGER_API_TOKEN` repo secret) to pull
   the new image and recreate the container after each push.
5. **Verifying env vars actually landed**: `docker inspect <container>
   --format '{{range .Config.Env}}{{println .}}{{end}}'` on the VPS — don't
   trust the Docker Manager UI or app logs alone.

The frontend stays on Render (`net-worth-frontend`, see
`net-worth-frontend/README.md`) and reaches this API directly cross-origin
via `API_BASE_URL` (baked in at build time — see `render.yaml` and
`scripts/set-env.js`), so `FRONTEND_ORIGIN` here and `API_BASE_URL` there
must stay in sync with wherever each side actually lives.

## Adjusting the schedule

`SNAPSHOT_CRON` uses Spring's 6-field cron format (seconds first):
`0 0 7 * * *` = every day at 07:00 in `SNAPSHOT_ZONE`. Change both env vars
and redeploy to adjust.
