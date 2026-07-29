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

Every credential is optional in isolation — leave a pair blank to skip that
source. At least one Trading212 account or Crypto.com must be configured or
snapshots will fail with "No API keys configured".

## API

| Endpoint | Description |
|---|---|
| `POST /api/v1/snapshot` | Fetch balances right now and save a row (same logic the cron job runs). Returns the saved row(s) as JSON. |
| `GET /api/v1/history?currency=GBP` | Full snapshot history for a currency, oldest first. `currency` defaults to whichever currency has been logged. |
| `GET /api/v1/chart?currency=GBP` | PNG line chart (Trading212 / Crypto.com / net worth) rendered from the history. |
| `GET /actuator/health` | Health check (used by Render) |

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
curl http://localhost:8080/api/v1/chart -o chart.png
```

## Deploy to Render

**Option A — Blueprint (`render.yaml`)**

1. Push this folder to a GitHub repo.
2. In Render: New → Blueprint, point it at the repo. It provisions the web
   service (built from the `Dockerfile`) and a free Postgres database, and
   wires `DATABASE_URL` between them automatically.
3. In the service's Environment tab, fill in the credential variables
   marked `sync: false` in `render.yaml` (`TRADING212_API_KEY_ISA`,
   `CRYPTO_COM_KEY`, etc.) — Render leaves these blank for you to set
   manually so they're never committed to the repo.

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
