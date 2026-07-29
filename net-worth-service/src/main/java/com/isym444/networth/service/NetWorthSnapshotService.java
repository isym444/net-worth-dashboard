package com.isym444.networth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.isym444.networth.client.CryptoComClient;
import com.isym444.networth.client.Trading212Client;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches balances from every configured source (Trading212 ISA, Trading212
 * Invest, Crypto.com) and persists one row per currency observed. Neither
 * upstream API exposes historical portfolio value, so this service is the
 * only place history gets built up — one snapshot at a time.
 */
@Service
public class NetWorthSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(NetWorthSnapshotService.class);

    private final Trading212Client trading212Client;
    private final CryptoComClient cryptoComClient;
    private final NetWorthSnapshotRepository repository;

    @Value("${networth.trading212.env}")
    private String trading212Env;

    @Value("${networth.trading212.isa.api-key}")
    private String isaApiKey;

    @Value("${networth.trading212.isa.api-secret}")
    private String isaApiSecret;

    @Value("${networth.trading212.invest.api-key}")
    private String investApiKey;

    @Value("${networth.trading212.invest.api-secret}")
    private String investApiSecret;

    @Value("${networth.cryptocom.api-key}")
    private String cryptoComApiKey;

    @Value("${networth.cryptocom.api-secret}")
    private String cryptoComApiSecret;

    public NetWorthSnapshotService(Trading212Client trading212Client,
                                    CryptoComClient cryptoComClient,
                                    NetWorthSnapshotRepository repository) {
        this.trading212Client = trading212Client;
        this.cryptoComClient = cryptoComClient;
        this.repository = repository;
    }

    @Scheduled(cron = "${networth.snapshot.cron}", zone = "${networth.snapshot.zone}")
    public void scheduledSnapshot() {
        log.info("Running scheduled net worth snapshot");
        try {
            List<NetWorthSnapshot> saved = takeSnapshot();
            log.info("Saved {} snapshot row(s)", saved.size());
        } catch (Exception e) {
            log.error("Scheduled net worth snapshot failed", e);
        }
    }

    public List<NetWorthSnapshot> takeSnapshot() {
        log.info("Taking net worth snapshot (trading212.env={})", trading212Env);
        String baseUrl = "demo".equalsIgnoreCase(trading212Env)
                ? "https://demo.trading212.com"
                : "https://live.trading212.com";

        // currency -> [t212Total, cryptoTotal]
        Map<String, double[]> totalsByCurrency = new LinkedHashMap<>();

        addTrading212Account(totalsByCurrency, baseUrl, "ISA", isaApiKey, isaApiSecret);
        addTrading212Account(totalsByCurrency, baseUrl, "Invest", investApiKey, investApiSecret);
        addCryptoCom(totalsByCurrency);

        if (totalsByCurrency.isEmpty()) {
            throw new IllegalStateException(
                    "No API keys configured. Set TRADING212_API_KEY_ISA / TRADING212_API_KEY_INVEST "
                            + "and/or CRYPTO_COM_KEY + CRYPTO_COM_SECRET.");
        }

        Instant now = Instant.now();
        List<NetWorthSnapshot> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : totalsByCurrency.entrySet()) {
            String currency = entry.getKey();
            double t212Total = entry.getValue()[0];
            double cryptoTotal = entry.getValue()[1];

            double[] carriedForward = carryForwardManualTotals(currency);
            double pensionTotal = carriedForward[0];
            double barclaysSharesTotal = carriedForward[1];

            NetWorthSnapshot snapshot = new NetWorthSnapshot();
            snapshot.setTimestamp(now);
            snapshot.setCurrency(currency);
            snapshot.setT212Total(round2(t212Total));
            snapshot.setCryptoTotal(round2(cryptoTotal));
            snapshot.setPensionTotal(round2(pensionTotal));
            snapshot.setBarclaysSharesTotal(round2(barclaysSharesTotal));
            snapshot.setGrandTotal(round2(t212Total + cryptoTotal + pensionTotal + barclaysSharesTotal));
            rows.add(repository.save(snapshot));
            log.info("Saved snapshot: currency={} t212Total={} cryptoTotal={} pensionTotal={} barclaysSharesTotal={} grandTotal={}",
                    snapshot.getCurrency(), snapshot.getT212Total(), snapshot.getCryptoTotal(),
                    snapshot.getPensionTotal(), snapshot.getBarclaysSharesTotal(), snapshot.getGrandTotal());
        }
        return rows;
    }

    /**
     * Pension and Barclays shares aren't fetched from any API — they're maintained
     * by hand directly in the database. Each new snapshot carries forward whichever
     * values were most recently saved for this currency, rather than resetting them.
     */
    private double[] carryForwardManualTotals(String currency) {
        return repository.findFirstByCurrencyOrderByTimestampDesc(currency)
                .map(previous -> new double[] { previous.getPensionTotal(), previous.getBarclaysSharesTotal() })
                .orElse(new double[] { 0.0, 0.0 });
    }

    private void addTrading212Account(Map<String, double[]> totalsByCurrency, String baseUrl, String label,
                                       String apiKey, String apiSecret) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("Trading212 {} account skipped (no API key configured)", label);
            return;
        }
        log.info("Fetching Trading212 {} account summary from {}", label, baseUrl);
        JsonNode summary = trading212Client.fetchAccountSummary(baseUrl, apiKey, apiSecret);
        String currency = summary.path("currency").asText("");

        double available = summary.path("cash").path("availableToTrade").asDouble(0);
        double reserved = summary.path("cash").path("reservedForOrders").asDouble(0);
        double inPies = summary.path("cash").path("inPies").asDouble(0);
        double currentValue = summary.path("investments").path("currentValue").asDouble(0);
        double totalValue = summary.has("totalValue")
                ? summary.path("totalValue").asDouble()
                : currentValue + available + reserved + inPies;

        log.info("Trading212 {} account total: {} {}", label, totalValue, currency);
        totalsByCurrency.computeIfAbsent(currency, c -> new double[2])[0] += totalValue;
    }

    private void addCryptoCom(Map<String, double[]> totalsByCurrency) {
        if (cryptoComApiKey == null || cryptoComApiKey.isBlank()
                || cryptoComApiSecret == null || cryptoComApiSecret.isBlank()) {
            log.info("Crypto.com skipped (no API key/secret configured)");
            return;
        }

        log.info("Fetching Crypto.com fiat + portfolio balances");
        JsonNode fiat = cryptoComClient.get("/v1/fiat-account", cryptoComApiKey, cryptoComApiSecret);
        for (JsonNode balance : fiat.path("account").path("balances")) {
            double amount = balance.path("amount").path("amount").asDouble(0);
            if (amount <= 0) {
                continue;
            }
            String currency = balance.path("currency").asText("");
            totalsByCurrency.computeIfAbsent(currency, c -> new double[2])[1] += amount;
        }

        // Use the portfolio breakdown (not raw wallet balances) so staked/
        // locked funds across every product are included without double-counting.
        JsonNode portfolio = cryptoComClient.get("/v1/portfolio", cryptoComApiKey, cryptoComApiSecret);
        for (JsonNode product : portfolio.path("products")) {
            double amount = product.path("price_native").path("amount").asDouble(0);
            if (amount <= 0) {
                continue;
            }
            String currency = product.path("price_native").path("currency").asText("");
            totalsByCurrency.computeIfAbsent(currency, c -> new double[2])[1] += amount;
        }
        log.info("Crypto.com balances fetched");
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
