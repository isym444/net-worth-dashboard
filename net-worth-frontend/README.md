# net-worth-frontend

Single-page Angular dashboard for `net-worth-service`: current net worth, a
"Take snapshot now" button, a history table, and the net worth chart. Gated
behind a shared access key (see `DASHBOARD_ACCESS_KEY` on the backend) —
enter it once per page load, held in memory only.

## Requirements

Node 24 (Angular 22 requires `^22.22.3 || ^24.15.0 || >=26.0.0`). If your
system's default `node` doesn't satisfy that, install a matching version
without disturbing the default, e.g.:

```bash
brew install node@24
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
```

## Run locally

Requires the backend running locally too (see `../net-worth-service/README.md`,
`docker compose up --build`, default `http://localhost:8080`):

```bash
npm ci
npm start   # ng serve, http://localhost:4200
```

`src/environments/environment.ts` points at `http://localhost:8080` for dev,
with `monthlySubscriptions` defaulting to `310`.
`src/environments/environment.prod.ts` is regenerated at build time by
`scripts/set-env.js` from the `API_BASE_URL` / `MONTHLY_SUBSCRIPTIONS` env
vars set in Render (see below) — don't hand-edit it, changes get overwritten
on the next deploy.

## Deploy to Render

Deployed as a `runtime: static` service from the repo-root `render.yaml`,
alongside `net-worth-service`, via Blueprint (see `net-worth-service/README.md`
for the full Blueprint walkthrough). Build command
`npm ci && node scripts/set-env.js && npm run build`, publish path
`./dist/net-worth-frontend/browser`. `NODE_VERSION` is set to `24` for you
via `render.yaml` (Angular's build tooling requires a narrow Node version
range that most platform defaults miss).

Two build-time env vars, both set in `render.yaml` and editable from Render's
Environment tab without touching code — changing either and redeploying is
enough, no rebuild-and-push needed:

| Variable | Description |
|---|---|
| `API_BASE_URL` | Backend URL the dashboard talks to |
| `MONTHLY_SUBSCRIPTIONS` | Guaranteed monthly subscription spend, subtracted before the Max Daily Spend cards divide by days remaining. Default `310`. |

The dashboard access key is entered at runtime in the browser, not baked
into the build.
