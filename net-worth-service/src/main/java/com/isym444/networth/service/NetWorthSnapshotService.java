package com.isym444.networth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.isym444.networth.client.CryptoComClient;
import com.isym444.networth.client.ExchangeRateClient;
import com.isym444.networth.client.Trading212Client;
import com.isym444.networth.client.YnabClient;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fetches balances from every source (Trading212 ISA, Trading212 Invest,
 * Crypto.com, YNAB) and persists one row per currency observed. Neither
 * upstream API exposes historical portfolio value, so this service is the
 * only place history gets built up — one snapshot at a time.
 *
 * <p>All sources are required: a missing credential or a failed API
 * call aborts the whole snapshot (nothing is saved) rather than silently
 * persisting a partial total. This is deliberate — a silently-partial total
 * once produced a real, wrong row in the history that went unnoticed for
 * hours because nothing distinguished it from a normal snapshot.
 */
@Service
public class NetWorthSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(NetWorthSnapshotService.class);

    private static final String CONVERSION_TARGET_CURRENCIES = "EUR,USD,MXN,JPY";

    private final Trading212Client trading212Client;
    private final CryptoComClient cryptoComClient;
    private final YnabClient ynabClient;
    private final ExchangeRateClient exchangeRateClient;
    private final NetWorthSnapshotRepository repository;
    private final AmexInsightService amexInsightService;

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

    @Value("${networth.exchange-rate.api-key}")
    private String exchangeRateApiKey;

    @Value("${networth.ynab.api-token}")
    private String ynabApiToken;

    @Value("${networth.ynab.budget-id}")
    private String ynabBudgetId;

    @Value("${networth.ynab.chase-account-ids}")
    private String ynabChaseAccountIds;

    @Value("${networth.ynab.amex-account-ids}")
    private String ynabAmexAccountIds;

    public NetWorthSnapshotService(Trading212Client trading212Client,
                                    CryptoComClient cryptoComClient,
                                    YnabClient ynabClient,
                                    ExchangeRateClient exchangeRateClient,
                                    NetWorthSnapshotRepository repository,
                                    AmexInsightService amexInsightService) {
        this.trading212Client = trading212Client;
        this.cryptoComClient = cryptoComClient;
        this.ynabClient = ynabClient;
        this.exchangeRateClient = exchangeRateClient;
        this.repository = repository;
        this.amexInsightService = amexInsightService;
    }

    @Scheduled(cron = "${networth.snapshot.cron}", zone = "${networth.snapshot.zone}")
    public void scheduledSnapshot() {
        log.info("Running scheduled net worth snapshot");
        try {
            List<NetWorthSnapshot> saved = takeSnapshot(true);
            log.info("Saved {} snapshot row(s)", saved.size());
        } catch (Exception e) {
            log.error("Scheduled net worth snapshot failed", e);
        }
    }

    public List<NetWorthSnapshot> takeSnapshot() {
        return takeSnapshot(false);
    }

    /**
     * @param regenerateCommentary if true, calls the LLM once for fresh Amex spending
     *                             commentary and stamps it onto every row in this batch
     *                             (used by the daily cron run). If false, each row's
     *                             commentary is copied forward from the previous row for
     *                             its currency instead (used by the manual "take snapshot"
     *                             button, to avoid an LLM call on every click).
     */
    public List<NetWorthSnapshot> takeSnapshot(boolean regenerateCommentary) {
        log.info("Taking net worth snapshot (trading212.env={}, regenerateCommentary={})",
                trading212Env, regenerateCommentary);
        String baseUrl = "demo".equalsIgnoreCase(trading212Env)
                ? "https://demo.trading212.com"
                : "https://live.trading212.com";

        // currency -> [t212Total, cryptoTotal, chaseTotal, amexDebtTotal]
        Map<String, double[]> totalsByCurrency = new LinkedHashMap<>();

        addTrading212Account(totalsByCurrency, baseUrl, "ISA", isaApiKey, isaApiSecret);
        addTrading212Account(totalsByCurrency, baseUrl, "Invest", investApiKey, investApiSecret);
        addCryptoCom(totalsByCurrency);
        addYnabAccounts(totalsByCurrency);

        String freshCommentary = null;
        if (regenerateCommentary) {
            try {
                freshCommentary = amexInsightService.generateCommentary();
            } catch (Exception e) {
                log.warn("AI commentary generation failed, falling back to carried-forward value", e);
            }
        }

        Instant now = Instant.now();
        List<NetWorthSnapshot> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : totalsByCurrency.entrySet()) {
            String currency = entry.getKey();
            double t212Total = entry.getValue()[0];
            double cryptoTotal = entry.getValue()[1];
            double chaseTotal = entry.getValue()[2];
            double amexDebtTotal = entry.getValue()[3];

            NetWorthSnapshot previous = repository.findFirstByCurrencyOrderByTimestampDescIdDesc(currency)
                    .orElse(null);
            double pensionTotal = previous != null && previous.getPensionTotal() != null
                    ? previous.getPensionTotal() : 0.0;
            double barclaysSharesTotal = previous != null && previous.getBarclaysSharesTotal() != null
                    ? previous.getBarclaysSharesTotal() : 0.0;
            double grandTotal = round2(t212Total + cryptoTotal + pensionTotal + barclaysSharesTotal
                    + chaseTotal - amexDebtTotal);
            double amountLeft = round2(chaseTotal - amexDebtTotal);

            CurrencyConversions conversions = fetchCurrencyConversions(currency, grandTotal);

            NetWorthSnapshot snapshot = new NetWorthSnapshot();
            snapshot.setTimestamp(now);
            snapshot.setCurrency(currency);
            snapshot.setT212Total(round2(t212Total));
            snapshot.setCryptoTotal(round2(cryptoTotal));
            snapshot.setPensionTotal(round2(pensionTotal));
            snapshot.setBarclaysSharesTotal(round2(barclaysSharesTotal));
            snapshot.setChaseTotal(round2(chaseTotal));
            snapshot.setAmexDebtTotal(round2(amexDebtTotal));
            snapshot.setAmountLeft(amountLeft);
            snapshot.setGrandTotal(grandTotal);
            snapshot.setGrandTotalEur(conversions.eur());
            snapshot.setGrandTotalUsd(conversions.usd());
            snapshot.setGrandTotalMxn(conversions.mxn());
            snapshot.setGrandTotalJpy(conversions.jpy());
            snapshot.setAiCommentary(freshCommentary != null
                    ? freshCommentary
                    : (previous != null ? previous.getAiCommentary() : null));
            rows.add(repository.save(snapshot));
            log.info("Saved snapshot: currency={} t212Total={} cryptoTotal={} pensionTotal={} barclaysSharesTotal={} "
                            + "chaseTotal={} amexDebtTotal={} amountLeft={} grandTotal={}",
                    snapshot.getCurrency(), snapshot.getT212Total(), snapshot.getCryptoTotal(),
                    snapshot.getPensionTotal(), snapshot.getBarclaysSharesTotal(),
                    snapshot.getChaseTotal(), snapshot.getAmexDebtTotal(), snapshot.getAmountLeft(),
                    snapshot.getGrandTotal());
        }
        return rows;
    }

    private void addTrading212Account(Map<String, double[]> totalsByCurrency, String baseUrl, String label,
                                       String apiKey, String apiSecret) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            log.warn("Trading212 {} account is not configured (missing API key/secret)", label);
            throw new IllegalStateException(
                    "Trading212 " + label + " account is not configured (missing API key/secret) — "
                            + "refusing to save a snapshot with a partial total. Set TRADING212_API_KEY_"
                            + label.toUpperCase() + " / TRADING212_API_SECRET_" + label.toUpperCase() + ".");
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
        totalsByCurrency.computeIfAbsent(currency, c -> new double[4])[0] += totalValue;
    }

    private void addCryptoCom(Map<String, double[]> totalsByCurrency) {
        if (cryptoComApiKey == null || cryptoComApiKey.isBlank()
                || cryptoComApiSecret == null || cryptoComApiSecret.isBlank()) {
            log.warn("Crypto.com is not configured (missing API key/secret)");
            throw new IllegalStateException(
                    "Crypto.com is not configured (missing API key/secret) — refusing to save a snapshot "
                            + "with a partial total. Set CRYPTO_COM_KEY / CRYPTO_COM_SECRET.");
        }

        log.info("Fetching Crypto.com fiat + portfolio balances");
        JsonNode fiat = cryptoComClient.get("/v1/fiat-account", cryptoComApiKey, cryptoComApiSecret);
        for (JsonNode balance : fiat.path("account").path("balances")) {
            double amount = balance.path("amount").path("amount").asDouble(0);
            if (amount <= 0) {
                continue;
            }
            String currency = balance.path("currency").asText("");
            totalsByCurrency.computeIfAbsent(currency, c -> new double[4])[1] += amount;
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
            totalsByCurrency.computeIfAbsent(currency, c -> new double[4])[1] += amount;
        }
        log.info("Crypto.com balances fetched");
    }

    /**
     * Sums YNAB account balances into chaseTotal/amexDebtTotal by matching a
     * configured set of account IDs (YNAB account names carry no bank identity,
     * e.g. "CreditCard – 1002", so name matching isn't viable here). Amex
     * balances are stored as debt magnitude (positive = owed), the inverse of
     * YNAB's own sign convention for credit card balances.
     */
    private void addYnabAccounts(Map<String, double[]> totalsByCurrency) {
        if (ynabApiToken == null || ynabApiToken.isBlank() || ynabBudgetId == null || ynabBudgetId.isBlank()) {
            log.warn("YNAB is not configured (missing API token/budget id)");
            throw new IllegalStateException(
                    "YNAB is not configured (missing API token/budget id) — refusing to save a snapshot "
                            + "with a partial total. Set YNAB_API_TOKEN / YNAB_BUDGET_ID.");
        }

        Set<String> chaseAccountIds = parseIdSet(ynabChaseAccountIds);
        Set<String> amexAccountIds = parseIdSet(ynabAmexAccountIds);

        log.info("Fetching YNAB accounts and budget currency");
        JsonNode settings = ynabClient.fetchBudgetSettings(ynabBudgetId, ynabApiToken);
        String currency = settings.path("data").path("settings").path("currency_format").path("iso_code").asText("");

        double chaseTotal = 0;
        double amexDebtTotal = 0;
        JsonNode accounts = ynabClient.fetchAccounts(ynabBudgetId, ynabApiToken).path("data").path("accounts");
        for (JsonNode account : accounts) {
            if (account.path("deleted").asBoolean(false) || account.path("closed").asBoolean(false)) {
                continue;
            }
            String id = account.path("id").asText("");
            double balance = account.path("balance").asLong(0) / 1000.0;
            if (chaseAccountIds.contains(id)) {
                chaseTotal += balance;
            } else if (amexAccountIds.contains(id)) {
                amexDebtTotal += -balance;
            }
        }

        log.info("YNAB totals: currency={} chaseTotal={} amexDebtTotal={}", currency, chaseTotal, amexDebtTotal);
        double[] totals = totalsByCurrency.computeIfAbsent(currency, c -> new double[4]);
        totals[2] += chaseTotal;
        totals[3] += amexDebtTotal;
    }

    private Set<String> parseIdSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Converts a snapshot's grand total into EUR/USD/MXN/JPY via a live exchange-rate
     * call. Deliberately never throws: a failure here (network error, API error, or a
     * blank API key) leaves all four conversions null and logs a warning, rather than
     * blocking the snapshot's core data from saving — unlike the Trading212/Crypto.com
     * calls above, these conversions are auxiliary, not core to grand_total or the chart.
     */
    private CurrencyConversions fetchCurrencyConversions(String baseCurrency, double grandTotal) {
        if (exchangeRateApiKey == null || exchangeRateApiKey.isBlank()) {
            log.warn("Exchange rate conversion skipped (no API key configured)");
            return CurrencyConversions.EMPTY;
        }
        try {
            JsonNode response = exchangeRateClient.fetchRates(baseCurrency, CONVERSION_TARGET_CURRENCIES, exchangeRateApiKey);
            JsonNode rates = response.path("rates");
            return new CurrencyConversions(
                    convert(rates, "EUR", grandTotal),
                    convert(rates, "USD", grandTotal),
                    convert(rates, "MXN", grandTotal),
                    convert(rates, "JPY", grandTotal));
        } catch (Exception e) {
            log.warn("Exchange rate conversion failed for currency={}, leaving converted totals null", baseCurrency, e);
            return CurrencyConversions.EMPTY;
        }
    }

    private Double convert(JsonNode rates, String code, double grandTotal) {
        JsonNode rate = rates.path(code);
        if (!rate.isNumber() || rate.asDouble() <= 0) {
            log.warn("Exchange rate response missing usable rate for {}", code);
            return null;
        }
        return round2(grandTotal * rate.asDouble());
    }

    private record CurrencyConversions(Double eur, Double usd, Double mxn, Double jpy) {
        static final CurrencyConversions EMPTY = new CurrencyConversions(null, null, null, null);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
