# Net Worth Chart Dark Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recolor the server-rendered net worth chart PNG to match the app's dark theme, and remove the frontend's now-unneeded light wrapper panel, per `docs/superpowers/specs/2026-07-29-net-worth-chart-dark-theme-design.md`.

**Architecture:** All chart styling is applied to the `JFreeChart`/`XYPlot` objects already returned by `ChartFactory.createTimeSeriesChart(...)` in `ChartService.renderNetWorthChart` — no new dependency, no theme class, no change to the chart's data or the `/api/v1/chart` endpoint contract. The frontend's `.chart-panel` light-background wrapper is deleted since the PNG itself is now dark.

**Tech Stack:** JFreeChart 1.5.4 (Java/Spring Boot, already a dependency), Angular 18+ (CSS/HTML only, no `.ts` changes).

## Global Constraints

- No new dependencies (backend or frontend).
- No change to chart data, image dimensions (1000×550), output format (PNG), or the `/api/v1/chart` endpoint contract.
- No change to date-axis tick formatting — out of scope per the spec's non-goals.
- Colors must match the exact hex values in the spec's token table — no ad hoc values.

---

### Task 1: Dark-theme the chart PNG

**Files:**
- Modify: `net-worth-service/src/main/java/com/isym444/networth/service/ChartService.java`

**Interfaces:**
- `renderNetWorthChart(List<NetWorthSnapshot> rows, String currency)` signature, return type (`byte[]`), and thrown exception (`IOException`) are unchanged — this is an internal styling-only change, no caller (`NetWorthController`) needs to change.
- Produces: a private `applyDarkTheme(JFreeChart chart)` helper method, called once from `renderNetWorthChart` right after the chart is built and before it's written to PNG.

- [ ] **Step 1: Replace the contents of `ChartService.java`**

```java
package com.isym444.networth.service;

import com.isym444.networth.model.NetWorthSnapshot;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.LegendTitle;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.springframework.stereotype.Service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

@Service
public class ChartService {

    private static final Color SURFACE = new Color(0x12, 0x16, 0x1f);
    private static final Color BORDER = new Color(0x23, 0x28, 0x38);
    private static final Color TEXT = new Color(0xe8, 0xea, 0xed);
    private static final Color TEXT_MUTED = new Color(0x8b, 0x92, 0xa3);
    private static final Color ACCENT = new Color(0x34, 0xd3, 0x99);
    private static final Color TRADING212_COLOR = new Color(0x60, 0xa5, 0xfa);
    private static final Color CRYPTO_COLOR = new Color(0xfb, 0xbf, 0x24);

    public byte[] renderNetWorthChart(List<NetWorthSnapshot> rows, String currency) throws IOException {
        TimeSeries t212Series = new TimeSeries("Trading212");
        TimeSeries cryptoSeries = new TimeSeries("Crypto.com");
        TimeSeries grandSeries = new TimeSeries("Net worth");

        for (NetWorthSnapshot row : rows) {
            Millisecond point = new Millisecond(Date.from(row.getTimestamp()));
            t212Series.addOrUpdate(point, row.getT212Total());
            cryptoSeries.addOrUpdate(point, row.getCryptoTotal());
            grandSeries.addOrUpdate(point, row.getGrandTotal());
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(t212Series);
        dataset.addSeries(cryptoSeries);
        dataset.addSeries(grandSeries);

        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                "Net worth over time (" + currency + ")",
                "Date",
                currency,
                dataset,
                true,
                false,
                false
        );

        applyDarkTheme(chart);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChartUtils.writeChartAsPNG(out, chart, 1000, 550);
        return out.toByteArray();
    }

    private void applyDarkTheme(JFreeChart chart) {
        chart.setBackgroundPaint(SURFACE);
        chart.setBorderVisible(false);

        chart.getTitle().setPaint(TEXT);
        chart.getTitle().setFont(new Font("SansSerif", Font.BOLD, 16));

        LegendTitle legend = chart.getLegend();
        if (legend != null) {
            legend.setBackgroundPaint(SURFACE);
            legend.setItemPaint(TEXT_MUTED);
        }

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(SURFACE);
        plot.setDomainGridlinePaint(BORDER);
        plot.setRangeGridlinePaint(BORDER);
        plot.setOutlinePaint(BORDER);

        ValueAxis domainAxis = plot.getDomainAxis();
        domainAxis.setAxisLinePaint(BORDER);
        domainAxis.setTickMarkPaint(BORDER);
        domainAxis.setTickLabelPaint(TEXT_MUTED);
        domainAxis.setLabelPaint(TEXT_MUTED);

        ValueAxis rangeAxis = plot.getRangeAxis();
        rangeAxis.setAxisLinePaint(BORDER);
        rangeAxis.setTickMarkPaint(BORDER);
        rangeAxis.setTickLabelPaint(TEXT_MUTED);
        rangeAxis.setLabelPaint(TEXT_MUTED);

        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
        Color[] seriesColors = { TRADING212_COLOR, CRYPTO_COLOR, ACCENT };
        float[] seriesStrokes = { 2.0f, 2.0f, 2.5f };
        for (int i = 0; i < seriesColors.length; i++) {
            renderer.setSeriesPaint(i, seriesColors[i]);
            renderer.setSeriesStroke(i, new BasicStroke(seriesStrokes[i]));
            renderer.setSeriesShapesVisible(i, false);
        }
    }
}
```

- [ ] **Step 2: Verify the service compiles**

Run: `cd net-worth-service && ./mvnw -q compile` (or `mvn -q compile` if no wrapper is present — check for `mvnw` in the directory first)
Expected: `BUILD SUCCESS`, no compilation errors (this catches any JFreeChart API mismatch, e.g. a method not present in 1.5.4).

- [ ] **Step 3: Commit**

```bash
git add net-worth-service/src/main/java/com/isym444/networth/service/ChartService.java
git commit -m "Dark-theme the net worth chart rendering"
```

---

### Task 2: Remove the frontend's light chart wrapper

**Files:**
- Modify: `net-worth-frontend/src/app/dashboard.component.html`
- Modify: `net-worth-frontend/src/app/dashboard.component.css`

**Interfaces:**
- Consumes: `chartUrl()` signal from `DashboardComponent` (unchanged) — only the markup wrapping the `<img [src]="url">` changes, the binding itself does not.
- No new classes introduced; `.chart-panel` is deleted (no other file references this class after this task).

- [ ] **Step 1: Edit `dashboard.component.html`** — remove the `.chart-panel` wrapper `<div>` so the `<img>` sits directly inside `.chart-card`

Find:
```html
  @if (chartUrl(); as url) {
    <section class="chart-card">
      <div class="chart-panel">
        <img [src]="url" alt="Net worth over time" class="chart" />
      </div>
    </section>
  }
```

Replace with:
```html
  @if (chartUrl(); as url) {
    <section class="chart-card">
      <img [src]="url" alt="Net worth over time" class="chart" />
    </section>
  }
```

- [ ] **Step 2: Edit `dashboard.component.css`** — delete the `.chart-panel` rule

Find and delete this block entirely:
```css
.chart-panel {
  padding: 1rem;
  background: #f4f5f7;
  border-radius: var(--radius);
}
```

`.chart-card` (which keeps `padding: 1rem; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);`) and `.chart` (unchanged) stay exactly as they are — no other edits in this file.

- [ ] **Step 3: Verify the app still builds**

Run: `cd net-worth-frontend && npx ng build`
Expected: build succeeds with no errors.

- [ ] **Step 4: Commit**

```bash
git add net-worth-frontend/src/app/dashboard.component.html net-worth-frontend/src/app/dashboard.component.css
git commit -m "Remove light chart wrapper now that the chart is dark-native"
```

---

### Task 3: Manual visual verification

**Files:** none (verification only)

**Interfaces:** none — exercises the backend and frontend built by Tasks 1–2.

- [ ] **Step 1: Rebuild and restart the backend**

Run: `cd net-worth-service && ./mvnw -q spring-boot:run` (or restart however it's currently running locally) so the new `ChartService` code is live.

- [ ] **Step 2: Fetch the chart directly and inspect it**

Run: `curl -s http://localhost:8080/api/v1/chart -o /tmp/chart-check.png` then view the PNG.
Confirm: dark (`#12161f`) background, no white anywhere, three distinctly colored lines (blue, amber, thicker emerald), muted gridlines, readable light title/axis/legend text, no black outline box around the plot.

- [ ] **Step 3: Check it in the running dashboard**

With the frontend dev server running (`ng serve`) and the browser pointed at the dashboard (unlocked, with at least one snapshot), confirm the chart sits flush inside its dark `.chart-card` with no visible seam, light rectangle, or border mismatch between the card and the image edge.
