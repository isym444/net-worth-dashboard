# Net Worth Dashboard Dark Mode + Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle `net-worth-frontend` (access gate + dashboard) from unstyled default browser chrome into a single polished dark theme, per `docs/superpowers/specs/2026-07-29-net-worth-frontend-dark-mode-design.md`.

**Architecture:** CSS custom properties (design tokens) defined once in global `styles.css`, consumed by both components' scoped CSS. Template (`.html`) files get minimal structural additions (wrapper `<div>`/`<section>` elements, a spinner `<span>`, CSS classes) to support the new card-based layout — no changes to component `.ts` logic, signals, or HTTP calls.

**Tech Stack:** Angular 18+ standalone components, plain CSS (no framework/library additions).

## Global Constraints

- Dark theme only — no light theme, no toggle (confirmed with user).
- No changes to any `.ts` file in this plan — CSS/HTML only.
- No automated frontend test suite exists and none is being added — verification is `ng build` (catches template/binding breakage) per task, plus a final manual browser pass (Task 4).
- All colors/spacing must come from the token table in the design spec — no ad hoc hex values outside Task 1's `:root` block.
- Chart `<img>` itself is not restyled (server-rendered PNG) — it's framed in a light card panel instead.

---

### Task 1: Global design tokens

**Files:**
- Modify: `net-worth-frontend/src/styles.css`

**Interfaces:**
- Produces: CSS custom properties `--bg`, `--surface`, `--surface-raised`, `--border`, `--text`, `--text-muted`, `--accent`, `--accent-hover`, `--error`, `--error-bg`, `--radius`, available globally via `:root` to every component's scoped stylesheet (Angular scoped styles still inherit `:root`-level custom properties).

- [ ] **Step 1: Replace the contents of `styles.css`**

```css
:root {
  --bg: #0b0e14;
  --surface: #12161f;
  --surface-raised: #181d29;
  --border: #232838;
  --text: #e8eaed;
  --text-muted: #8b92a3;
  --accent: #34d399;
  --accent-hover: #2bbf87;
  --error: #f87171;
  --error-bg: #2a1414;
  --radius: 10px;
}

* {
  box-sizing: border-box;
}

html,
body {
  margin: 0;
  padding: 0;
  background: var(--bg);
  color: var(--text);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    sans-serif;
  line-height: 1.5;
}
```

- [ ] **Step 2: Verify the app still builds**

Run: `cd net-worth-frontend && npx ng build`
Expected: build succeeds with no errors (a plain global stylesheet change cannot break compilation, but this confirms the toolchain is healthy before further edits).

- [ ] **Step 3: Commit**

```bash
git add net-worth-frontend/src/styles.css
git commit -m "Add dark theme design tokens to global styles"
```

---

### Task 2: Access gate dark styling

**Files:**
- Modify: `net-worth-frontend/src/app/access-gate.component.css`

**Interfaces:**
- Consumes: `--bg`, `--surface`, `--border`, `--radius`, `--text`, `--text-muted`, `--accent`, `--accent-hover` from Task 1.
- No template (`access-gate.component.html`) changes needed — existing markup (`.gate` div, `h1`, `p`, `input`, `button`) already has enough structure/classes for this styling.

- [ ] **Step 1: Replace the contents of `access-gate.component.css`**

```css
:host {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}

.gate {
  width: 100%;
  max-width: 340px;
  padding: 2rem;
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: 0 20px 40px -20px rgba(0, 0, 0, 0.6);
}

.gate h1 {
  margin: 0 0 0.5rem;
  font-size: 1.375rem;
  color: var(--text);
}

.gate p {
  margin: 0 0 1.5rem;
  color: var(--text-muted);
  font-size: 0.9rem;
}

.gate input {
  width: 100%;
  padding: 0.65rem 0.85rem;
  margin: 0 0 1rem;
  box-sizing: border-box;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: calc(var(--radius) - 4px);
  color: var(--text);
  font-size: 0.95rem;
}

.gate input::placeholder {
  color: var(--text-muted);
}

.gate input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(52, 211, 153, 0.25);
}

.gate button {
  width: 100%;
  padding: 0.65rem 1rem;
  border: none;
  border-radius: calc(var(--radius) - 4px);
  background: var(--accent);
  color: #05261b;
  font-weight: 600;
  font-size: 0.95rem;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.gate button:hover:not(:disabled) {
  background: var(--accent-hover);
}

.gate button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
```

- [ ] **Step 2: Verify the app still builds**

Run: `cd net-worth-frontend && npx ng build`
Expected: build succeeds with no errors.

- [ ] **Step 3: Commit**

```bash
git add net-worth-frontend/src/app/access-gate.component.css
git commit -m "Restyle access gate with dark theme"
```

---

### Task 3: Dashboard dark styling

**Files:**
- Modify: `net-worth-frontend/src/app/dashboard.component.html`
- Modify: `net-worth-frontend/src/app/dashboard.component.css`

**Interfaces:**
- Consumes: all tokens from Task 1. Consumes existing `DashboardComponent` signals/methods unchanged: `takingSnapshot()`, `takeSnapshot()`, `error()`, `loading()`, `latest()`, `chartUrl()`, `sortedDescending()` (all defined in `dashboard.component.ts`, not modified by this task).
- Template changes: adds a `<span class="spinner">` inside the snapshot button (shown only while `takingSnapshot()`), adds `stat-card` / `stat-card--primary` classes to the three headline `<div>`s, wraps the chart `<img>` in `.chart-card > .chart-panel`, wraps the `<table>` in `.table-card`, adds a `muted` class to the loading/empty-state `<p>` tags. No new component inputs/outputs — purely structural/class additions on top of existing bindings.

- [ ] **Step 1: Replace the contents of `dashboard.component.html`**

```html
<header>
  <h1>Net Worth Dashboard</h1>
  <button (click)="takeSnapshot()" [disabled]="takingSnapshot()">
    @if (takingSnapshot()) {
      <span class="spinner"></span>
    }
    {{ takingSnapshot() ? 'Taking snapshot…' : 'Take snapshot now' }}
  </button>
</header>

@if (error()) {
  <p class="error">{{ error() }}</p>
}

@if (loading()) {
  <p class="muted">Loading…</p>
} @else {
  @if (latest(); as latestSnapshot) {
    <section class="headline">
      <div class="stat-card">
        <span>Trading212</span>
        <strong>{{ latestSnapshot.t212Total | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
      </div>
      <div class="stat-card">
        <span>Crypto.com</span>
        <strong>{{ latestSnapshot.cryptoTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
      </div>
      <div class="stat-card stat-card--primary">
        <span>Net worth</span>
        <strong>{{ latestSnapshot.grandTotal | number: '1.2-2' }} {{ latestSnapshot.currency }}</strong>
      </div>
    </section>
  } @else {
    <p class="muted">No snapshots yet. Click "Take snapshot now" to get started.</p>
  }

  @if (chartUrl(); as url) {
    <section class="chart-card">
      <div class="chart-panel">
        <img [src]="url" alt="Net worth over time" class="chart" />
      </div>
    </section>
  }

  @if (sortedDescending().length > 0) {
    <section class="table-card">
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
    </section>
  }
}
```

- [ ] **Step 2: Replace the contents of `dashboard.component.css`**

```css
:host {
  display: block;
  max-width: 900px;
  margin: 0 auto;
  padding: 2rem 1rem 4rem;
}

header {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

header h1 {
  font-size: 1.5rem;
  margin: 0;
  color: var(--text);
}

header button {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.55rem 1.1rem;
  border: none;
  border-radius: 999px;
  background: var(--accent);
  color: #05261b;
  font-weight: 600;
  font-size: 0.9rem;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

header button:hover:not(:disabled) {
  background: var(--accent-hover);
}

header button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.spinner {
  width: 0.9rem;
  height: 0.9rem;
  border: 2px solid rgba(5, 38, 27, 0.35);
  border-top-color: #05261b;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.muted {
  color: var(--text-muted);
  padding: 1rem 0;
}

.error {
  color: var(--error);
  background: var(--error-bg);
  border-left: 3px solid var(--error);
  border-radius: 6px;
  padding: 0.75rem 1rem;
  margin-bottom: 1.5rem;
  font-weight: 500;
}

.headline {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  padding: 1.1rem 1.25rem;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.stat-card span {
  color: var(--text-muted);
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.stat-card strong {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text);
}

.stat-card--primary {
  border-color: var(--accent);
  background: var(--surface-raised);
}

.stat-card--primary strong {
  font-size: 1.5rem;
  color: var(--accent);
}

.chart-card {
  margin-bottom: 1.5rem;
}

.chart-panel {
  padding: 1rem;
  background: #f4f5f7;
  border-radius: var(--radius);
}

.chart {
  display: block;
  max-width: 100%;
  height: auto;
  border-radius: calc(var(--radius) - 4px);
}

.table-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

table {
  width: 100%;
  border-collapse: collapse;
}

th,
td {
  text-align: right;
  padding: 0.6rem 1rem;
  font-variant-numeric: tabular-nums;
}

th:first-child,
td:first-child {
  text-align: left;
  font-variant-numeric: normal;
}

thead th {
  background: var(--surface-raised);
  color: var(--text-muted);
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  font-weight: 600;
  border-bottom: 1px solid var(--border);
}

tbody tr {
  border-bottom: 1px solid var(--border);
}

tbody tr:last-child {
  border-bottom: none;
}

tbody tr:hover {
  background: var(--surface-raised);
}

@media (max-width: 640px) {
  .headline {
    grid-template-columns: 1fr;
  }
}
```

- [ ] **Step 3: Verify the app still builds**

Run: `cd net-worth-frontend && npx ng build`
Expected: build succeeds with no errors. This is the step most likely to catch a mistake, since the template changed structurally — a broken `@if`/`@for` block or mismatched tag will fail compilation here.

- [ ] **Step 4: Commit**

```bash
git add net-worth-frontend/src/app/dashboard.component.html net-worth-frontend/src/app/dashboard.component.css
git commit -m "Restyle dashboard with dark theme card layout"
```

---

### Task 4: Manual visual verification

**Files:** none (verification only)

**Interfaces:** none — this task exercises the running app built by Tasks 1–3.

- [ ] **Step 1: Start the dev server**

Run: `cd net-worth-frontend && npx ng serve` (leave running)

- [ ] **Step 2: Check the access gate**

Open `http://localhost:4200` in a browser. Confirm: dark card centered on a dark page, input has visible focus ring on click, "Unlock" button is disabled/dimmed with empty input and becomes solid green when text is entered.

- [ ] **Step 3: Check the dashboard states**

With a valid access key (or backend running with no `DASHBOARD_ACCESS_KEY` set), confirm:
- Three headline stat cards render side by side, with "Net worth" visually emphasized (green border/larger number).
- Chart renders inside a light panel card that doesn't clash with the dark page background.
- History table has a dark card wrapper, muted uppercase header row, hairline row dividers, and a hover highlight per row.
- Clicking "Take snapshot now" shows the spinner + "Taking snapshot…" text and the button is disabled while in flight.

- [ ] **Step 4: Check empty and error states**

Confirm the "no snapshots yet" message and any `.error` banner (e.g. trigger by entering a wrong access key if `DASHBOARD_ACCESS_KEY` is set) render as muted text / a red-tinted banner respectively, not unstyled black-on-white text.

- [ ] **Step 5: Stop the dev server**

Kill the `ng serve` process (Ctrl+C or corresponding background-process stop) once verification is complete.
