# Net Worth Dashboard Angular Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small Angular dashboard (net-worth-frontend) for the existing net-worth-service backend, deployed as its own Render static site alongside the Spring Boot service, both declared in one render.yaml Blueprint, with a shared-secret access key and CORS protecting the API.

**Architecture:** Two independently-deployed Render services from one `render.yaml`: the existing `net-worth-service` (Docker, unchanged behavior plus CORS + access-key filter) and a new `net-worth-frontend` (Angular, `runtime: static`). Both live as sibling folders under the same repo root, scoped via Render's `rootDir` monorepo support. The Angular app is a single view (no routing): an access-key gate, then a dashboard that reads `GET /api/v1/history`, triggers `POST /api/v1/snapshot`, and displays `GET /api/v1/chart` as an image.

**Tech Stack:** Angular 22 (standalone components, functional HTTP interceptors, signals, new `@if`/`@for` control flow), plain CSS, Spring Boot 3.3 (existing), Render Blueprint (`runtime: docker` + `runtime: static` + Postgres).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-29-net-worth-frontend-design.md` — every requirement in this plan traces back to it.
- No routing, no user accounts, no persistent client-side session (access key held in memory only, re-prompted on reload).
- No automated test suite for the frontend or the new backend filter/config — verified manually via `curl` and a real browser session against `docker compose`, consistent with the rest of this project's verification approach.
- `AccessKeyFilter` must no-op entirely when `DASHBOARD_ACCESS_KEY` is unset, so local `docker compose up` keeps working with zero extra setup.
- Backend JSON field names are fixed by the existing `NetWorthSnapshot` entity and must not change: `id`, `timestamp`, `currency`, `t212Total`, `cryptoTotal`, `grandTotal`.
- Angular scaffolding flags, Render Blueprint YAML keys (`runtime: docker`, `runtime: static`, `rootDir`, `dockerfilePath`, `staticPublishPath`), Node version requirements, and Angular's build output path (`dist/<project>/browser`) were all verified directly against a real `ng new` + `ng build` run and real GitHub `render.yaml` examples during planning — not guessed. Use the exact values given in each task.
- Visual/design polish (typography, layout, color system) is explicitly **out of scope** for this plan — ship a plain, functional UI. A follow-up pass with a design skill happens after this plan is deployed and verified working.

---

### Task 1: Repo foundation — gitignore, track net-worth-service, relocate render.yaml for the monorepo layout

`net-worth-service/` has never been committed (only the design spec has). `render.yaml` currently lives inside it and uses the legacy `env: docker` key. Render Blueprints read `render.yaml` from the repo root, and with two sibling service folders we need Render's `rootDir` field to scope each service to its own subdirectory.

**Files:**
- Create: `.gitignore` (repo root)
- Modify: `render.yaml` (moved from `net-worth-service/render.yaml` to repo root)
- Delete: `net-worth-service/render.yaml`

**Interfaces:**
- Produces: `render.yaml` at repo root with a `net-worth-service` block using `runtime: docker` + `rootDir: net-worth-service` — later tasks (7, 9) add to this same file.

- [ ] **Step 1: Create the root `.gitignore`**

```
.env
.claude/
.audit-key
audit.jsonl
.DS_Store
__pycache__/
```

- [ ] **Step 2: Move render.yaml to the repo root and update it**

```bash
mv net-worth-service/render.yaml render.yaml
```

Replace the full contents of `render.yaml` with:

```yaml
services:
  - type: web
    name: net-worth-service
    runtime: docker
    rootDir: net-worth-service
    dockerfilePath: ./Dockerfile
    plan: free
    healthCheckPath: /actuator/health
    envVars:
      - key: DATABASE_URL
        fromDatabase:
          name: net-worth-db
          property: connectionString
      - key: TRADING212_API_KEY_ISA
        sync: false
      - key: TRADING212_API_SECRET_ISA
        sync: false
      - key: TRADING212_API_KEY_INVEST
        sync: false
      - key: TRADING212_API_SECRET_INVEST
        sync: false
      - key: TRADING212_ENV
        value: live
      - key: CRYPTO_COM_KEY
        sync: false
      - key: CRYPTO_COM_SECRET
        sync: false
      - key: SNAPSHOT_CRON
        value: "0 0 7 * * *"
      - key: SNAPSHOT_ZONE
        value: UTC

databases:
  - name: net-worth-db
    plan: free
```

(This is Task 1's version — Task 9 adds the frontend service block and the two new env vars on top of this.)

- [ ] **Step 3: Verify the YAML is still valid and the old file is gone**

Run: `python3 -c "import yaml; yaml.safe_load(open('render.yaml'))" && echo OK`
Expected: `OK`

Run: `ls net-worth-service/render.yaml`
Expected: `No such file or directory`

- [ ] **Step 4: Commit**

```bash
git add .gitignore render.yaml net-worth-service/
git commit -m "Track net-worth-service and move render.yaml to repo root for monorepo layout"
```

---

### Task 2: Backend — CORS configuration

Allow the frontend's origin to call the API from a browser. Without this, every request from `net-worth-frontend` fails with a CORS error regardless of the access key.

**Files:**
- Create: `net-worth-service/src/main/java/com/isym444/networth/config/CorsConfig.java`
- Modify: `net-worth-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: nothing new.
- Produces: `networth.cors.frontend-origin` config property, read by `CorsConfig`.

- [ ] **Step 1: Add the config property**

In `net-worth-service/src/main/resources/application.yml`, add a new top-level `networth.cors` block right after the existing `networth.snapshot` block (i.e. as a sibling of `snapshot`, `trading212`, and `cryptocom` under `networth:`):

```yaml
  cors:
    frontend-origin: ${FRONTEND_ORIGIN:http://localhost:4200}
```

The full `networth:` section should now read:

```yaml
networth:
  snapshot:
    cron: "${SNAPSHOT_CRON:0 0 7 * * *}"
    zone: "${SNAPSHOT_ZONE:UTC}"
  cors:
    frontend-origin: ${FRONTEND_ORIGIN:http://localhost:4200}
  trading212:
    env: ${TRADING212_ENV:live}
    isa:
      api-key: ${TRADING212_API_KEY_ISA:}
      api-secret: ${TRADING212_API_SECRET_ISA:}
    invest:
      api-key: ${TRADING212_API_KEY_INVEST:}
      api-secret: ${TRADING212_API_SECRET_INVEST:}
  cryptocom:
    api-key: ${CRYPTO_COM_KEY:}
    api-secret: ${CRYPTO_COM_SECRET:}
```

- [ ] **Step 2: Create `CorsConfig.java`**

```java
package com.isym444.networth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Angular frontend's origin to call /api/**. Origin(s) come from
 * FRONTEND_ORIGIN (comma-separated if more than one), defaulting to the
 * local `ng serve` dev origin.
 */
@Configuration
public class CorsConfig {

    @Value("${networth.cors.frontend-origin}")
    private String frontendOrigin;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        String[] origins = frontendOrigin.split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST")
                        .allowedHeaders("X-Api-Key", "Content-Type")
                        .maxAge(3600);
            }
        };
    }
}
```

- [ ] **Step 3: Build and verify CORS headers**

```bash
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
cd net-worth-service
mvn -B -q package -DskipTests
```
Expected: no output (quiet success), `target/net-worth-service-0.0.1-SNAPSHOT.jar` exists.

Start it locally against nothing but a dummy datasource isn't practical without Postgres — instead verify via `docker compose` (Postgres included):

```bash
docker compose up --build -d
until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do sleep 1; done
curl -s -i -X OPTIONS http://localhost:8080/api/v1/history \
  -H "Origin: http://localhost:4200" \
  -H "Access-Control-Request-Method: GET" | grep -i "access-control-allow-origin"
```
Expected: `access-control-allow-origin: http://localhost:4200`

```bash
curl -s -i -X OPTIONS http://localhost:8080/api/v1/history \
  -H "Origin: http://evil-example.com" \
  -H "Access-Control-Request-Method: GET" | grep -i "access-control-allow-origin"
```
Expected: no output (origin not allowed, header absent).

- [ ] **Step 4: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/config/CorsConfig.java net-worth-service/src/main/resources/application.yml
git commit -m "Add CORS config for the Angular frontend origin"
```

---

### Task 3: Backend — shared-secret access filter + error message exposure

Protects `/api/**` behind a shared secret once `DASHBOARD_ACCESS_KEY` is set, and makes Spring Boot's default error responses include the actual exception message (e.g. "No API keys configured...") so the frontend can show it instead of a generic failure.

**Files:**
- Create: `net-worth-service/src/main/java/com/isym444/networth/config/AccessKeyFilter.java`
- Modify: `net-worth-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: nothing new.
- Produces: `networth.dashboard.access-key` config property. `401` response body `{"error":"Missing or invalid X-Api-Key header"}` when rejected — the frontend does not depend on this exact body (it has its own generic "access key rejected" message keyed off HTTP status 401), so this is informational only for whoever's watching logs/curl.

- [ ] **Step 1: Add config**

In `net-worth-service/src/main/resources/application.yml`, add a `dashboard` block as a sibling of `cors` under `networth:`:

```yaml
  dashboard:
    access-key: ${DASHBOARD_ACCESS_KEY:}
```

Also add error message exposure under the existing top-level `server:` block (currently just `port: ${PORT:8080}`):

```yaml
server:
  port: ${PORT:8080}
  error:
    include-message: always
```

- [ ] **Step 2: Create `AccessKeyFilter.java`**

```java
package com.isym444.networth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires header X-Api-Key to match DASHBOARD_ACCESS_KEY on every /api/**
 * request. No-ops entirely when DASHBOARD_ACCESS_KEY is blank/unset, so
 * local dev (docker-compose, no key configured) is unaffected. Exempts
 * CORS preflight (OPTIONS) requests, which never carry custom headers.
 */
@Component
public class AccessKeyFilter extends OncePerRequestFilter {

    @Value("${networth.dashboard.access-key}")
    private String accessKey;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        boolean protectedPath = request.getRequestURI().startsWith("/api/");
        boolean preflight = "OPTIONS".equalsIgnoreCase(request.getMethod());

        if (accessKey == null || accessKey.isBlank() || !protectedPath || preflight) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader("X-Api-Key");
        if (!accessKey.equals(providedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid X-Api-Key header\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 3: Verify no-op behavior (no key set) and enforced behavior (key set)**

Rebuild and restart against the existing `docker compose` stack, which has no `DASHBOARD_ACCESS_KEY` set:

```bash
docker compose up --build -d
until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do sleep 1; done
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/history
```
Expected: `200` (no-op confirmed — no key configured, request passes through).

Now start it with a key set, to confirm enforcement:

```bash
DASHBOARD_ACCESS_KEY=test-key docker compose up --build -d app
until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do sleep 1; done
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/history
```
Expected: `401`

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Api-Key: test-key" http://localhost:8080/api/v1/history
```
Expected: `200`

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health
```
Expected: `200` (actuator stays exempt even with a key configured)

Then tear the key back off so later tasks' local dev stays frictionless:

```bash
docker compose down -v
```

- [ ] **Step 4: Verify the "no API keys configured" error message is now exposed**

With no Trading212/Crypto.com keys in the environment, `POST /api/v1/snapshot` should return the real message, not a generic one:

```bash
docker compose up --build -d
until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do sleep 1; done
curl -s -X POST http://localhost:8080/api/v1/snapshot | python3 -m json.tool
docker compose down -v
```
Expected: JSON body includes `"message": "No API keys configured. Set TRADING212_API_KEY_ISA / TRADING212_API_KEY_INVEST and/or CRYPTO_COM_KEY + CRYPTO_COM_SECRET."`

- [ ] **Step 5: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/config/AccessKeyFilter.java net-worth-service/src/main/resources/application.yml
git commit -m "Add shared-secret access filter and expose error messages for the frontend"
```

---

### Task 4: Scaffold the Angular app

**Files:**
- Create: `net-worth-frontend/` (entire Angular CLI-generated workspace)

**Interfaces:**
- Produces: a building Angular app at `net-worth-frontend/`, output at `net-worth-frontend/dist/net-worth-frontend/browser/` after `ng build`. Later tasks add files under `net-worth-frontend/src/app/`.

- [ ] **Step 1: Scaffold with the exact verified flags**

Node 24 is required (Angular 22 needs `^22.22.3 || ^24.15.0 || >=26.0.0`; this machine's default `node` is v23.11, which does not qualify — `node@24` was installed via Homebrew specifically for this):

```bash
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
npx -y @angular/cli@22 new net-worth-frontend \
  --routing=false \
  --ssr=false \
  --zoneless=false \
  --style=css \
  --skip-tests \
  --skip-git \
  --package-manager=npm \
  --interactive=false \
  --defaults
```

`--skip-git` is required because this workspace already has its own repo — `ng new` must not try to init a nested one. `--skip-tests` matches this plan's "no automated test suite" constraint. `--zoneless=false` keeps traditional zone.js change detection (simpler, more broadly compatible than the newer zoneless mode, and this app has no need for it).

- [ ] **Step 2: Verify it builds**

```bash
cd net-worth-frontend
npx ng build
```
Expected: `Application bundle generation complete.` and output at `dist/net-worth-frontend/browser/index.html`.

Run: `ls dist/net-worth-frontend/browser/index.html`
Expected: file exists.

- [ ] **Step 3: Commit**

```bash
cd net-worth-frontend && rm -rf dist
git add net-worth-frontend/
git commit -m "Scaffold net-worth-frontend Angular app"
```

---

### Task 5: Frontend — environment config, API client, auth service/interceptor

**Files:**
- Modify: `net-worth-frontend/src/environments/environment.ts` (create `src/environments/` — Angular 22's default scaffold does not generate this folder; we add it ourselves)
- Create: `net-worth-frontend/src/environments/environment.prod.ts`
- Create: `net-worth-frontend/src/app/net-worth.service.ts`
- Create: `net-worth-frontend/src/app/auth.service.ts`
- Create: `net-worth-frontend/src/app/auth.interceptor.ts`
- Modify: `net-worth-frontend/src/app/app.config.ts`
- Modify: `net-worth-frontend/angular.json`

**Interfaces:**
- Produces:
  - `environment.apiBaseUrl: string`
  - `interface NetWorthSnapshot { id: number; timestamp: string; currency: string; t212Total: number; cryptoTotal: number; grandTotal: number; }`
  - `class NetWorthService { getHistory(currency?: string): Observable<NetWorthSnapshot[]>; takeSnapshot(): Observable<NetWorthSnapshot[]>; getChartBlob(currency?: string): Observable<Blob>; }`
  - `class AuthService { readonly apiKey: Signal<string | null>; readonly isUnlocked: Signal<boolean>; unlock(key: string): void; }`
  - `const authInterceptor: HttpInterceptorFn`
- Consumes: nothing from earlier tasks (this is the frontend's data layer, consumed by Tasks 6 and 7).

- [ ] **Step 1: Add environment files**

Create `net-worth-frontend/src/environments/environment.ts`:

```typescript
export const environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8080',
};
```

Create `net-worth-frontend/src/environments/environment.prod.ts`:

```typescript
export const environment = {
  production: true,
  apiBaseUrl: 'https://net-worth-service.onrender.com',
};
```

(`net-worth-service` is the exact `name` set on the backend service in `render.yaml` — Render assigns `https://<name>.onrender.com` deterministically, so this URL is correct as long as that name doesn't change. If the deployed backend ever reports a different URL, update this file and redeploy the frontend.)

- [ ] **Step 2: Wire the prod environment file swap into the build**

In `net-worth-frontend/angular.json`, find the `projects.net-worth-frontend.architect.build.configurations.production` block and add a `fileReplacements` entry so `ng build` (which defaults to the production configuration) swaps in `environment.prod.ts`:

```json
"fileReplacements": [
  {
    "replace": "src/environments/environment.ts",
    "with": "src/environments/environment.prod.ts"
  }
],
```

Add this as the first key inside that `production` configuration object (alongside whatever budgets/output-hashing settings the schematic already generated there — do not remove those, just add this key).

- [ ] **Step 3: Create the API client**

`net-worth-frontend/src/app/net-worth.service.ts`:

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

export interface NetWorthSnapshot {
  id: number;
  timestamp: string;
  currency: string;
  t212Total: number;
  cryptoTotal: number;
  grandTotal: number;
}

@Injectable({ providedIn: 'root' })
export class NetWorthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  getHistory(currency?: string): Observable<NetWorthSnapshot[]> {
    const url = currency
      ? `${this.baseUrl}/api/v1/history?currency=${encodeURIComponent(currency)}`
      : `${this.baseUrl}/api/v1/history`;
    return this.http.get<NetWorthSnapshot[]>(url);
  }

  takeSnapshot(): Observable<NetWorthSnapshot[]> {
    return this.http.post<NetWorthSnapshot[]>(`${this.baseUrl}/api/v1/snapshot`, {});
  }

  getChartBlob(currency?: string): Observable<Blob> {
    const url = currency
      ? `${this.baseUrl}/api/v1/chart?currency=${encodeURIComponent(currency)}`
      : `${this.baseUrl}/api/v1/chart`;
    return this.http.get(url, { responseType: 'blob' });
  }
}
```

- [ ] **Step 4: Create the auth service**

`net-worth-frontend/src/app/auth.service.ts`:

```typescript
import { Injectable, computed, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _apiKey = signal<string | null>(null);
  readonly apiKey = this._apiKey.asReadonly();
  readonly isUnlocked = computed(() => this._apiKey() !== null);

  unlock(key: string): void {
    this._apiKey.set(key);
  }
}
```

- [ ] **Step 5: Create the auth interceptor**

`net-worth-frontend/src/app/auth.interceptor.ts`:

```typescript
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const key = authService.apiKey();
  if (!key) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { 'X-Api-Key': key } }));
};
```

- [ ] **Step 6: Wire HttpClient + the interceptor into app.config.ts**

Modify `net-worth-frontend/src/app/app.config.ts` — add the import and the `provideHttpClient(...)` entry to the existing `providers` array (keep `provideBrowserGlobalErrorListeners()` and `provideZoneChangeDetection(...)` as they are):

```typescript
import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { authInterceptor } from './auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideHttpClient(withInterceptors([authInterceptor])),
  ]
};
```

- [ ] **Step 7: Verify it still builds**

```bash
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
cd net-worth-frontend
npx ng build
```
Expected: `Application bundle generation complete.`, no TypeScript errors.

- [ ] **Step 8: Commit**

```bash
cd net-worth-frontend && rm -rf dist
git add net-worth-frontend/src/environments net-worth-frontend/src/app/net-worth.service.ts net-worth-frontend/src/app/auth.service.ts net-worth-frontend/src/app/auth.interceptor.ts net-worth-frontend/src/app/app.config.ts net-worth-frontend/angular.json
git commit -m "Add API client, auth service/interceptor, and environment config"
```

---

### Task 6: Frontend — AccessGateComponent

**Files:**
- Create: `net-worth-frontend/src/app/access-gate.component.ts`
- Create: `net-worth-frontend/src/app/access-gate.component.html`
- Create: `net-worth-frontend/src/app/access-gate.component.css`

**Interfaces:**
- Consumes: `AuthService` (Task 5).
- Produces: `AccessGateComponent`, standalone, selector `app-access-gate` — consumed by `App` in Task 8.

- [ ] **Step 1: Create the component**

`net-worth-frontend/src/app/access-gate.component.ts`:

```typescript
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-access-gate',
  imports: [FormsModule],
  templateUrl: './access-gate.component.html',
  styleUrl: './access-gate.component.css',
})
export class AccessGateComponent {
  private readonly authService = inject(AuthService);
  protected readonly keyInput = signal('');

  unlock(): void {
    const trimmed = this.keyInput().trim();
    if (trimmed.length > 0) {
      this.authService.unlock(trimmed);
    }
  }
}
```

- [ ] **Step 2: Create the template**

`net-worth-frontend/src/app/access-gate.component.html`:

```html
<div class="gate">
  <h1>Net Worth Dashboard</h1>
  <p>Enter your access key to continue.</p>
  <input
    type="password"
    [ngModel]="keyInput()"
    (ngModelChange)="keyInput.set($event)"
    (keyup.enter)="unlock()"
    placeholder="Access key"
    autofocus
  />
  <button (click)="unlock()" [disabled]="keyInput().trim().length === 0">Unlock</button>
</div>
```

- [ ] **Step 3: Create minimal CSS**

`net-worth-frontend/src/app/access-gate.component.css`:

```css
.gate {
  max-width: 320px;
  margin: 4rem auto;
  padding: 1.5rem;
  text-align: center;
  font-family: system-ui, sans-serif;
}

.gate input {
  width: 100%;
  padding: 0.5rem;
  margin: 1rem 0;
  box-sizing: border-box;
}

.gate button {
  padding: 0.5rem 1rem;
  cursor: pointer;
}

.gate button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
```

- [ ] **Step 4: Verify it builds**

```bash
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
cd net-worth-frontend
npx ng build
```
Expected: `Application bundle generation complete.` (the component isn't referenced anywhere yet, so this only confirms it compiles standalone — full behavior is verified in Task 8).

- [ ] **Step 5: Commit**

```bash
cd net-worth-frontend && rm -rf dist
git add net-worth-frontend/src/app/access-gate.component.ts net-worth-frontend/src/app/access-gate.component.html net-worth-frontend/src/app/access-gate.component.css
git commit -m "Add AccessGateComponent"
```

---

### Task 7: Frontend — DashboardComponent

**Files:**
- Create: `net-worth-frontend/src/app/dashboard.component.ts`
- Create: `net-worth-frontend/src/app/dashboard.component.html`
- Create: `net-worth-frontend/src/app/dashboard.component.css`

**Interfaces:**
- Consumes: `NetWorthService`, `NetWorthSnapshot` (Task 5).
- Produces: `DashboardComponent`, standalone, selector `app-dashboard` — consumed by `App` in Task 8.

- [ ] **Step 1: Create the component**

`net-worth-frontend/src/app/dashboard.component.ts`:

```typescript
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { NetWorthService, NetWorthSnapshot } from './net-worth.service';

@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit {
  private readonly netWorthService = inject(NetWorthService);

  protected readonly snapshots = signal<NetWorthSnapshot[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly takingSnapshot = signal(false);
  protected readonly chartUrl = signal<string | null>(null);

  protected readonly latest = computed<NetWorthSnapshot | null>(() => {
    const list = this.snapshots();
    return list.length > 0 ? list[list.length - 1] : null;
  });

  protected readonly sortedDescending = computed(() =>
    [...this.snapshots()].sort((a, b) => b.timestamp.localeCompare(a.timestamp)),
  );

  ngOnInit(): void {
    this.loadHistory();
    this.loadChart();
  }

  loadHistory(): void {
    this.loading.set(true);
    this.error.set(null);
    this.netWorthService.getHistory().subscribe({
      next: (data) => {
        this.snapshots.set(data);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(this.extractError(err));
        this.loading.set(false);
      },
    });
  }

  loadChart(): void {
    this.netWorthService.getChartBlob().subscribe({
      next: (blob) => this.chartUrl.set(URL.createObjectURL(blob)),
      error: () => this.chartUrl.set(null),
    });
  }

  takeSnapshot(): void {
    this.takingSnapshot.set(true);
    this.error.set(null);
    this.netWorthService.takeSnapshot().subscribe({
      next: () => {
        this.takingSnapshot.set(false);
        this.loadHistory();
        this.loadChart();
      },
      error: (err: unknown) => {
        this.takingSnapshot.set(false);
        this.error.set(this.extractError(err));
      },
    });
  }

  private extractError(err: unknown): string {
    if (err && typeof err === 'object' && 'status' in err && (err as { status?: number }).status === 401) {
      return 'Access key rejected. Refresh and try again.';
    }
    if (err && typeof err === 'object' && 'error' in err) {
      const inner = (err as { error?: unknown }).error;
      if (inner && typeof inner === 'object' && 'message' in inner) {
        return String((inner as { message?: unknown }).message);
      }
      if (typeof inner === 'string' && inner.length > 0) {
        return inner;
      }
    }
    return 'Something went wrong talking to the backend.';
  }
}
```

- [ ] **Step 2: Create the template**

`net-worth-frontend/src/app/dashboard.component.html`:

```html
<header>
  <h1>Net Worth Dashboard</h1>
  <button (click)="takeSnapshot()" [disabled]="takingSnapshot()">
    {{ takingSnapshot() ? 'Taking snapshot…' : 'Take snapshot now' }}
  </button>
</header>

@if (error()) {
  <p class="error">{{ error() }}</p>
}

@if (loading()) {
  <p>Loading…</p>
} @else {
  @if (latest(); as latestSnapshot) {
    <section class="headline">
      <div><span>Trading212</span><strong>{{ latestSnapshot.t212Total | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong></div>
      <div><span>Crypto.com</span><strong>{{ latestSnapshot.cryptoTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong></div>
      <div><span>Net worth</span><strong>{{ latestSnapshot.grandTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong></div>
    </section>
  } @else {
    <p>No snapshots yet. Click "Take snapshot now" to get started.</p>
  }

  @if (chartUrl(); as url) {
    <img [src]="url" alt="Net worth over time" class="chart" />
  }

  @if (sortedDescending().length > 0) {
    <table>
      <thead>
        <tr>
          <th>Date</th>
          <th>Trading212</th>
          <th>Crypto.com</th>
          <th>Net worth</th>
        </tr>
      </thead>
      <tbody>
        @for (row of sortedDescending(); track row.id) {
          <tr>
            <td>{{ row.timestamp | date: 'medium' }}</td>
            <td>{{ row.t212Total | number: '1.2-2' }}</td>
            <td>{{ row.cryptoTotal | number: '1.2-2' }}</td>
            <td>{{ row.grandTotal | number: '1.2-2' }}</td>
          </tr>
        }
      </tbody>
    </table>
  }
}
```

- [ ] **Step 3: Create minimal CSS**

`net-worth-frontend/src/app/dashboard.component.css`:

```css
:host {
  display: block;
  max-width: 900px;
  margin: 2rem auto;
  padding: 0 1rem;
  font-family: system-ui, sans-serif;
}

header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.error {
  color: #b00020;
  font-weight: bold;
}

.headline {
  display: flex;
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}

.headline div {
  display: flex;
  flex-direction: column;
}

.headline strong {
  font-size: 1.25rem;
}

.chart {
  max-width: 100%;
  height: auto;
  margin-bottom: 1.5rem;
}

table {
  width: 100%;
  border-collapse: collapse;
}

th,
td {
  text-align: right;
  padding: 0.4rem 0.6rem;
  border-bottom: 1px solid #ddd;
}

th:first-child,
td:first-child {
  text-align: left;
}
```

- [ ] **Step 4: Verify it builds**

```bash
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
cd net-worth-frontend
npx ng build
```
Expected: `Application bundle generation complete.`

- [ ] **Step 5: Commit**

```bash
cd net-worth-frontend && rm -rf dist
git add net-worth-frontend/src/app/dashboard.component.ts net-worth-frontend/src/app/dashboard.component.html net-worth-frontend/src/app/dashboard.component.css
git commit -m "Add DashboardComponent"
```

---

### Task 8: Frontend — wire root App component; end-to-end local verification

**Files:**
- Modify: `net-worth-frontend/src/app/app.ts`
- Modify: `net-worth-frontend/src/app/app.html`
- Modify: `net-worth-frontend/src/app/app.css`

**Interfaces:**
- Consumes: `AuthService` (Task 5), `AccessGateComponent` (Task 6), `DashboardComponent` (Task 7).
- Produces: a fully working app — nothing downstream in this plan depends on `App` itself.

- [ ] **Step 1: Update the root component**

Replace the contents of `net-worth-frontend/src/app/app.ts`:

```typescript
import { Component, inject } from '@angular/core';
import { AccessGateComponent } from './access-gate.component';
import { AuthService } from './auth.service';
import { DashboardComponent } from './dashboard.component';

@Component({
  selector: 'app-root',
  imports: [AccessGateComponent, DashboardComponent],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly authService = inject(AuthService);
}
```

- [ ] **Step 2: Replace the root template**

Replace the entire contents of `net-worth-frontend/src/app/app.html` (the scaffolded default is the Angular CLI welcome page — delete all of it) with:

```html
@if (authService.isUnlocked()) {
  <app-dashboard />
} @else {
  <app-access-gate />
}
```

- [ ] **Step 3: Clear the root component's CSS**

Replace the contents of `net-worth-frontend/src/app/app.css` with an empty file (it's currently empty from scaffolding — confirm it stays that way; all real styling lives in the two child components).

- [ ] **Step 4: Build the frontend for production**

```bash
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
cd net-worth-frontend
npx ng build
```
Expected: `Application bundle generation complete.`

- [ ] **Step 5: End-to-end manual verification against the real backend**

Start the backend with Postgres and no access key (matching current local dev — Task 3 confirmed the no-op path already):

```bash
cd ../net-worth-service
set -a && source ../.env && set +a
export TRADING212_API_KEY_ISA="$TRADING212_API_KEY"
export TRADING212_API_SECRET_ISA="$TRADING212_API_SECRET"
docker compose up --build -d
until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do sleep 1; done
```

Serve the frontend against it:

```bash
cd ../net-worth-frontend
npx ng serve
```

Open `http://localhost:4200` in a real browser and verify by hand:
1. The access-key gate appears (dashboard is not visible yet).
2. Typing anything and clicking "Unlock" (or pressing Enter) reveals the dashboard — since `DASHBOARD_ACCESS_KEY` isn't set on the backend, any value works (the filter no-ops).
3. Headline stats show real numbers for Trading212, Crypto.com, and Net worth.
4. The chart image renders below the headline stats.
5. The history table lists past snapshots, newest first.
6. Clicking "Take snapshot now" disables the button, shows "Taking snapshot…", then re-enables it and the headline stats + table + chart all refresh with a new row.
7. Stop the backend (`docker compose down -v` in `net-worth-service/`) and click "Take snapshot now" again — the red error banner appears instead of a silent failure or crash.

Stop `ng serve` (Ctrl+C) once verified.

- [ ] **Step 6: Commit**

```bash
cd net-worth-frontend && rm -rf dist
git add net-worth-frontend/src/app/app.ts net-worth-frontend/src/app/app.html net-worth-frontend/src/app/app.css
git commit -m "Wire App root to switch between AccessGateComponent and DashboardComponent"
```

---

### Task 9: render.yaml — add the frontend static site service; update docs

**Files:**
- Modify: `render.yaml`
- Modify: `net-worth-service/README.md`
- Create: `net-worth-frontend/README.md`

**Interfaces:**
- Consumes: `net-worth-frontend`'s build output path (`dist/net-worth-frontend/browser`, confirmed in Task 4) and `net-worth-service`'s existing env var table (from Task 1's `render.yaml`).
- Produces: the final `render.yaml` deployable as a Blueprint.

- [ ] **Step 1: Add the frontend service block and two new backend env vars**

Replace the full contents of `render.yaml` with:

```yaml
services:
  - type: web
    name: net-worth-service
    runtime: docker
    rootDir: net-worth-service
    dockerfilePath: ./Dockerfile
    plan: free
    healthCheckPath: /actuator/health
    envVars:
      - key: DATABASE_URL
        fromDatabase:
          name: net-worth-db
          property: connectionString
      - key: TRADING212_API_KEY_ISA
        sync: false
      - key: TRADING212_API_SECRET_ISA
        sync: false
      - key: TRADING212_API_KEY_INVEST
        sync: false
      - key: TRADING212_API_SECRET_INVEST
        sync: false
      - key: TRADING212_ENV
        value: live
      - key: CRYPTO_COM_KEY
        sync: false
      - key: CRYPTO_COM_SECRET
        sync: false
      - key: SNAPSHOT_CRON
        value: "0 0 7 * * *"
      - key: SNAPSHOT_ZONE
        value: UTC
      - key: DASHBOARD_ACCESS_KEY
        sync: false
      - key: FRONTEND_ORIGIN
        value: https://net-worth-frontend.onrender.com

  - type: web
    name: net-worth-frontend
    runtime: static
    rootDir: net-worth-frontend
    buildCommand: npm ci && npm run build
    staticPublishPath: ./dist/net-worth-frontend/browser

databases:
  - name: net-worth-db
    plan: free
```

- [ ] **Step 2: Verify YAML validity and cross-check the two predicted URLs**

```bash
python3 -c "import yaml; yaml.safe_load(open('render.yaml'))" && echo OK
```
Expected: `OK`

Confirm the two service `name` values match the URLs hardcoded elsewhere:
- `net-worth-service` → must match `net-worth-frontend/src/environments/environment.prod.ts`'s `apiBaseUrl` (`https://net-worth-service.onrender.com`, set in Task 5).
- `net-worth-frontend` → must match `render.yaml`'s own `FRONTEND_ORIGIN` value (`https://net-worth-frontend.onrender.com`, set in this task).

Run: `grep -n "net-worth-service.onrender.com" net-worth-frontend/src/environments/environment.prod.ts`
Expected: one match.

Run: `grep -n "net-worth-frontend.onrender.com" render.yaml`
Expected: one match.

- [ ] **Step 3: Update net-worth-service/README.md**

Add a new row to the existing env var table (find the `| Variable | Required | Description |` table) for the two new variables:

```
| `DASHBOARD_ACCESS_KEY` | no | Shared secret required in the `X-Api-Key` header on every `/api/**` request. Leave unset to disable auth entirely (e.g. local dev). |
| `FRONTEND_ORIGIN` | no | Origin(s) allowed to call the API via CORS (comma-separated). Default `http://localhost:4200` for local `ng serve`. |
```

Add a new subsection right after the existing "## API" section:

```markdown
## Frontend

A small Angular dashboard lives at `../net-worth-frontend`. It's deployed as its own
Render static site (see the repo-root `render.yaml`) and talks to this API directly
from the browser, so both `DASHBOARD_ACCESS_KEY` and `FRONTEND_ORIGIN` matter once
it's live — see `net-worth-frontend/README.md`.
```

- [ ] **Step 4: Create net-worth-frontend/README.md**

```markdown
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
```

- [ ] **Step 5: Commit**

```bash
git add render.yaml net-worth-service/README.md net-worth-frontend/README.md
git commit -m "Add net-worth-frontend to render.yaml as a static site; update docs"
```

---

## Post-plan follow-ups (not part of this plan)

- Push the repo to GitHub and deploy the Blueprint on Render (a deployment action, not implementation — do this once all 9 tasks are verified working locally).
- Visual design pass on the dashboard (typography, layout, color system) using a design skill, per the spec's explicit deferral.
