# Net worth dashboard: Angular frontend

## Context

`net-worth-service` (Spring Boot + Postgres, deployed on Render) already exposes:

- `POST /api/v1/snapshot` — fetch current Trading212 + Crypto.com balances and save a row
- `GET /api/v1/history?currency=GBP` — full snapshot history, oldest first
- `GET /api/v1/chart?currency=GBP` — PNG line chart (Trading212 / Crypto.com / net worth) rendered via JFreeChart
- `GET /actuator/health` — health check

It has no UI and no authentication today. This spec adds a small Angular dashboard as a second, independently-deployed Render service, plus the minimum backend changes needed to support it safely.

## Goals

- A single-page dashboard showing current net worth, a history table, the chart image, and a button to trigger a fresh snapshot on demand.
- Deployed as its own Render **static site**, alongside the existing `net-worth-service` web service, both declared in one `render.yaml` blueprint.
- Basic protection so the dashboard isn't fully open to the public once it has a shareable URL.

## Non-goals

- No routing / multiple pages — one dashboard view is enough for this use case.
- No user accounts, no per-user data — this is a single-operator personal tool.
- No persistent client-side session — the access key is held in memory only and re-prompted on page reload. Acceptable friction for a personal dashboard.
- No automated test suite for the frontend or the new backend filter — verified manually (same approach used for the rest of this project), consistent with its scope.

## Architecture

Two Render services declared in one `render.yaml`, alongside the existing Postgres database:

```
render.yaml
├─ net-worth-service    (existing: Docker web service, Spring Boot + Postgres)
└─ net-worth-frontend   (new: static site, Angular build output)
```

Repo layout (single repo, no remote configured yet — see "Open items"):

```
outputs/
├─ net-worth-service/     (existing)
├─ net-worth-frontend/    (new)
└─ render.yaml            (moves to repo root, or stays under net-worth-service —
                            see "Open items")
```

## Backend changes (net-worth-service)

### CORS

New CORS configuration (a `WebMvcConfigurer` bean or equivalent) allowing the frontend's origin to call `/api/**` with `GET`/`POST` and the `X-Api-Key` header. Allowed origin(s) come from a new `FRONTEND_ORIGIN` env var (comma-separated if more than one), defaulting to including `http://localhost:4200` for local `ng serve` dev.

### Shared-secret access control

New `AccessKeyFilter` (same `OncePerRequestFilter` pattern as the existing `RequestLoggingFilter`):

- Reads a new `DASHBOARD_ACCESS_KEY` env var at request time.
- If that env var is blank/unset, the filter is a no-op — every request passes through unchanged. This keeps local `docker compose` dev working with zero extra setup.
- If set, every request under `/api/**` must include header `X-Api-Key` matching it exactly, or the filter short-circuits with `401 Unauthorized`.
- Exempts `/actuator/**` (Render's health check has no way to send custom headers) and `OPTIONS` requests (CORS preflight requests never carry the app's custom headers).

This is deliberately simple — one shared secret, no per-user accounts, no token expiry — matching the "personal tool with a public-ish URL" threat model. It stops casual/accidental exposure, not a determined attacker.

## Frontend (net-worth-frontend)

Standard Angular CLI app (latest stable, standalone components, no NgModules).

### Structure

- `AuthService` — holds the entered access key in memory; exposes whether the user is "unlocked".
- `AuthInterceptor` (`HttpInterceptor`) — attaches `X-Api-Key: <key>` to every outgoing request automatically once set.
- `AccessGateComponent` — shown until a key has been entered; a single password-style input, no validation beyond "non-empty" (the backend is the source of truth — a wrong key just makes subsequent API calls 401, surfaced as a normal error state).
- `DashboardComponent` — the main (only) view:
  - Fetches `GET /api/v1/history` on load.
  - Headline stats block: latest snapshot's Trading212 total, Crypto.com total, grand total, and currency.
  - History table: timestamp, Trading212 total, Crypto.com total, grand total — one row per snapshot, oldest or newest first (newest first is more useful at a glance).
  - "Take snapshot now" button → `POST /api/v1/snapshot`, disabled while in flight, refetches history on success.
  - Chart: fetched via `HttpClient.get(url, { responseType: 'blob' })` (so `AuthInterceptor` applies — a plain `<img src>` can't send custom headers), converted to an object URL, bound to an `<img>`.
  - Loading state while the initial history fetch is in flight; explicit error banner (not a silent failure) for fetch failures, including surfacing the backend's own error message (e.g. "No API keys configured...") when present.

### Config

- `environment.ts` (dev): `apiBaseUrl: 'http://localhost:8080'`
- `environment.prod.ts`: `apiBaseUrl` set to the deployed backend's Render URL, baked in at build time. If the backend's URL ever changes, this requires a rebuild + redeploy of the frontend (accepted tradeoff — see brainstorm discussion).

### Styling

This spec covers structure and behavior only. Visual design (typography, layout, color system) is a separate implementation pass using a design skill, per the "polished" preference from brainstorming — not detailed further here.

## render.yaml changes

- Add a static-site service block for `net-worth-frontend`. Exact current Render Blueprint YAML syntax for static sites (`runtime: static` vs `type: static`, `staticPublishPath`, build command, SPA rewrite rules if any) will be confirmed against Render's live documentation during implementation rather than guessed here, since getting this wrong would only surface as a failed deploy.
- New env vars:
  - `DASHBOARD_ACCESS_KEY` — one shared secret, `sync: false`, set once by the user after blueprint deploy, referenced by both services (backend reads it directly; frontend does **not** need it baked in — the user types it into the `AccessGateComponent` at runtime).
  - `FRONTEND_ORIGIN` on `net-worth-service` — the frontend's deployed URL, so CORS allows it.

## Local development

- `docker-compose.yml` is unaffected — `AccessKeyFilter` no-ops without `DASHBOARD_ACCESS_KEY` set, so `docker compose up --build` keeps working exactly as it does today.
- Frontend: `ng serve` against `environment.ts` (`http://localhost:8080`), talking to the locally running backend.
- README updates on both sides documenting the new env vars and how to run both together locally.

## Open items to resolve during implementation

1. **Where `render.yaml` should live.** It currently lives inside `net-worth-service/`. Render Blueprints expect `render.yaml` at the repo root. Since the frontend is a sibling folder in the same repo, `render.yaml` should move to the repo root (`outputs/render.yaml`) and its `dockerfilePath`/build paths adjust accordingly. Flagging this now so it isn't missed — not a design decision, just a mechanical fix needed for the blueprint to work with two services.
2. **No git remote configured yet.** The repo exists locally with no commits and no remote. Pushing to GitHub (or wherever Render pulls from) is a prerequisite for the Blueprint deploy and is out of scope for this spec — it's a deployment step, not a design decision.
3. Exact Angular CLI version and exact Render static-site YAML keys are implementation details confirmed at build time, not pinned here.
