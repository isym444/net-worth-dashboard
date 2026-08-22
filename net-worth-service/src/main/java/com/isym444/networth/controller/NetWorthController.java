package com.isym444.networth.controller;

import com.isym444.networth.dto.AmexInsightsResponse;
import com.isym444.networth.dto.AmexTransaction;
import com.isym444.networth.dto.MonthlyContributionRequest;
import com.isym444.networth.dto.MonthlyContributionResponse;
import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.service.AmexInsightService;
import com.isym444.networth.service.ChartService;
import com.isym444.networth.service.NetWorthHistoryService;
import com.isym444.networth.service.NetWorthSnapshotService;
import com.isym444.networth.service.PortfolioProjectionService;
import com.isym444.networth.service.YnabTransactionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class NetWorthController {

    private final NetWorthSnapshotService snapshotService;
    private final NetWorthHistoryService historyService;
    private final NetWorthSnapshotRepository repository;
    private final ChartService chartService;
    private final YnabTransactionService ynabTransactionService;
    private final AmexInsightService amexInsightService;
    private final PortfolioProjectionService portfolioProjectionService;

    public NetWorthController(NetWorthSnapshotService snapshotService,
                               NetWorthHistoryService historyService,
                               NetWorthSnapshotRepository repository,
                               ChartService chartService,
                               YnabTransactionService ynabTransactionService,
                               AmexInsightService amexInsightService,
                               PortfolioProjectionService portfolioProjectionService) {
        this.snapshotService = snapshotService;
        this.historyService = historyService;
        this.repository = repository;
        this.chartService = chartService;
        this.ynabTransactionService = ynabTransactionService;
        this.amexInsightService = amexInsightService;
        this.portfolioProjectionService = portfolioProjectionService;
    }

    /**
     * Trigger a snapshot on demand (the same logic the daily cron job runs), except this
     * copies the previous row's AI commentary forward instead of regenerating it — only the
     * scheduled cron job calls the LLM.
     */
    @PostMapping("/snapshot")
    public ResponseEntity<List<NetWorthSnapshot>> takeSnapshot() {
        return ResponseEntity.ok(snapshotService.takeSnapshot());
    }

    @GetMapping("/history")
    public ResponseEntity<NetWorthHistoryResponse> history(@RequestParam(required = false) String currency) {
        return ResponseEntity.ok(historyService.getHistory(currency));
    }

    @GetMapping("/amex/transactions")
    public ResponseEntity<List<AmexTransaction>> amexTransactions(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ynabTransactionService.getRecentAmexTransactions(days));
    }

    @PostMapping("/amex/insights")
    public ResponseEntity<AmexInsightsResponse> refreshAmexInsights() {
        return ResponseEntity.ok(amexInsightService.refreshAndPersist());
    }

    /**
     * Persists the new monthly contribution default and returns the recalculated
     * 1-year estimate plus the with-contributions net worth at 40/50/60/65, so the
     * dashboard can update all of those figures from this single response instead
     * of a full reload. Every one of those figures is computed fresh from a history
     * fetch taken after the setting is persisted — none of it is stored anywhere,
     * so there's nothing here that can go stale or miss a historical row.
     */
    @PostMapping("/settings/monthly-contribution")
    public ResponseEntity<MonthlyContributionResponse> updateMonthlyContribution(
            @RequestBody MonthlyContributionRequest request,
            @RequestParam(required = false) String currency) {
        double value = request.monthlyContribution() != null ? request.monthlyContribution() : 0.0;
        double persistedContribution = portfolioProjectionService.updateMonthlyContribution(value);
        NetWorthHistoryResponse history = historyService.getHistory(currency);
        return ResponseEntity.ok(new MonthlyContributionResponse(
                persistedContribution,
                history.estimatedNetWorth1y(),
                history.expectedNetWorthWithContributionsAt40(),
                history.expectedNetWorthWithContributionsAt50(),
                history.expectedNetWorthWithContributionsAt60(),
                history.expectedNetWorthWithContributionsAt65()));
    }

    @GetMapping(value = "/chart", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> chart(@RequestParam(required = false) String currency) throws IOException {
        String resolved = resolveCurrency(currency);
        if (resolved == null) {
            return ResponseEntity.noContent().build();
        }
        List<NetWorthSnapshot> rows = repository.findByCurrencyOrderByTimestampAsc(resolved);
        byte[] png = chartService.renderNetWorthChart(rows, resolved);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    // Assumes a single-currency dataset: with no ?currency= param, falls back to
    // whichever currency sorts first alphabetically among logged snapshots. Fine
    // for this deployment (single-currency accounts in practice) but if snapshots
    // ever span multiple currencies, pass ?currency= explicitly to pick one.
    private String resolveCurrency(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        List<String> currencies = repository.findDistinctCurrencies();
        return currencies.isEmpty() ? null : currencies.get(0);
    }
}
