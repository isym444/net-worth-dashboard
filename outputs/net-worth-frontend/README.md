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

`src/environments/environment.ts` points at `http://localhost:8080` for dev.
`src/environments/environment.prod.ts` (used by `npm run build`) points at
the deployed backend's Render URL — update it if that URL ever changes, then
rebuild and redeploy.

## Deploy to Render

Deployed as a `runtime: static` service from the repo-root `render.yaml`,
alongside `net-worth-service`, via Blueprint (see `net-worth-service/README.md`
for the full Blueprint walkthrough). Build command `npm ci && npm run build`,
publish path `./dist/net-worth-frontend/browser`. No environment variables
are required on this service — the access key is entered at runtime in the
browser, not baked into the build.
