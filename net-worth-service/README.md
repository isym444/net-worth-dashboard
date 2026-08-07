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
| `SNAPSHOT_CRON` | no | Cron expression (6-field, Spring format: sec min hour day month weekday) for the daily job. Default `0 0 7 * * *` (07:00) |
| `SNAPSHOT_ZONE` | no | Timezone for `SNAPSHOT_CRON`. Default `UTC` |
| `PORT` | no | Listen port. Render sets this automatically |
| `DASHBOARD_ACCESS_KEY` | no | Shared secret required in the `X-Api-Key` header on every `/api/**` request. Leave unset to disable auth entirely (e.g. local dev). |
| `FRONTEND_ORIGIN` | no | Origin(s) allowed to call the API via CORS (comma-separated). Default `http://localhost:4200` for local `ng serve`. |

Every credential is optional in isolation — leave a pair blank to skip that
source. At least one Trading212 account or Crypto.com must be configured or
snapshots will fail with "No API keys configured".

## API

| Endpoint | Description |
|---|---|
| `POST /api/v1/snapshot` | Fetch balances right now and save a row (same logic the cron job runs). Returns the saved row(s) as JSON. |
| `GET /api/v1/history?currency=GBP` | Snapshot history for a currency plus projected net worth at a few future ages. `currency` defaults to whichever currency has been logged. See response shape below. |
| `GET /api/v1/chart?currency=GBP` | PNG line chart (Trading212 / Crypto.com / net worth) rendered from the history. |
| `GET /actuator/health` | Health check (used by Render) |

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
docker compose up --build
```

Then:

```bash
curl -X POST http://localhost:8080/api/v1/snapshot
curl http://localhost:8080/api/v1/history
# => {"snapshots": [...], "expectedNetWorthAt40": ..., "expectedNetWorthAt50": ..., "expectedNetWorthAt60": ..., "expectedNetWorthAt65": ...}
curl http://localhost:8080/api/v1/chart -o chart.png
```

## Deploy to Render

**Option A — Blueprint (`render.yaml`)**

1. Push the repo root (containing `net-worth-service/`, `net-worth-frontend/`,
   and `render.yaml`) to a GitHub repo.
2. In Render: New → Blueprint, point it at the repo. It provisions three
   resources: the backend web service (built from `Dockerfile`), the
   `net-worth-frontend` static site, and a free Postgres database — wiring
   `DATABASE_URL` between the backend and Postgres automatically.
3. In the backend service's Environment tab, fill in the credential
   variables marked `sync: false` in `render.yaml` (`TRADING212_API_KEY_ISA`,
   `CRYPTO_COM_KEY`, etc.) — Render leaves these blank for you to set
   manually so they're never committed to the repo.

If either service's actual Render-assigned URL ever differs from what's
hardcoded (`net-worth-frontend/src/environments/environment.prod.ts`'s
`apiBaseUrl`, and `render.yaml`'s `FRONTEND_ORIGIN` value), both must be
updated together and redeployed — a mismatch shows up as a CORS/connection
error in the browser.

**Option B — Manual**

1. New → PostgreSQL, note the "Internal Database URL".
2. New → Web Service → connect the repo (or "Existing image" if you push
   one yourself) → Environment: Docker → set `dockerfilePath` to
   `./Dockerfile`.
3. Add the env vars from the table above; set `DATABASE_URL` to the
   Postgres instance's Internal Database URL.
4. Set the health check path to `/actuator/health`.

## Adjusting the schedule

`SNAPSHOT_CRON` uses Spring's 6-field cron format (seconds first):
`0 0 7 * * *` = every day at 07:00 in `SNAPSHOT_ZONE`. Change both env vars
and redeploy to adjust.
