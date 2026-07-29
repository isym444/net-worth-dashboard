package com.isym444.networth.controller;

import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.service.ChartService;
import com.isym444.networth.service.NetWorthSnapshotService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class NetWorthController {

    private final NetWorthSnapshotService snapshotService;
    private final NetWorthSnapshotRepository repository;
    private final ChartService chartService;

    public NetWorthController(NetWorthSnapshotService snapshotService,
                               NetWorthSnapshotRepository repository,
                               ChartService chartService) {
        this.snapshotService = snapshotService;
        this.repository = repository;
        this.chartService = chartService;
    }

    /** Trigger a snapshot on demand (the same logic the daily cron job runs). */
    @PostMapping("/snapshot")
    public ResponseEntity<List<NetWorthSnapshot>> takeSnapshot() {
        return ResponseEntity.ok(snapshotService.takeSnapshot());
    }

    @GetMapping("/history")
    public ResponseEntity<List<NetWorthSnapshot>> history(@RequestParam(required = false) String currency) {
        String resolved = resolveCurrency(currency);
        if (resolved == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(repository.findByCurrencyOrderByTimestampAsc(resolved));
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
