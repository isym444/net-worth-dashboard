package com.isym444.networth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "snapshots")
public class NetWorthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private String currency;

    @Column(name = "t212_total", nullable = false)
    private Double t212Total;

    @Column(name = "crypto_total", nullable = false)
    private Double cryptoTotal;

    @Column(name = "pension_total", nullable = false)
    private Double pensionTotal;

    @Column(name = "barclays_shares_total", nullable = false)
    private Double barclaysSharesTotal;

    // Nullable, unlike the columns above: added after the table already held
    // rows, and Postgres can't backfill a NOT NULL column with no default on
    // an existing table under ddl-auto: update. Every snapshot taken from
    // here on always sets a real value; only pre-existing history stays null.
    @Column(name = "chase_total")
    private Double chaseTotal;

    @Column(name = "amex_total")
    private Double amexDebtTotal;

    @Column(name = "amount_left")
    private Double amountLeft;

    @Column(name = "grand_total", nullable = false)
    private Double grandTotal;

    @Column(name = "`grand_total_EUR`")
    private Double grandTotalEur;

    @Column(name = "`grand_total_USD`")
    private Double grandTotalUsd;

    @Column(name = "`grand_total_MXN`")
    private Double grandTotalMxn;

    @Column(name = "`grand_total_JPY`")
    private Double grandTotalJpy;

    @Column(name = "ai_commentary", columnDefinition = "text")
    private String aiCommentary;

    // Nullable for the same reason as chaseTotal/amexTotal above: added after the
    // table already held rows. Recalculated on every new snapshot going forward.
    @Column(name = "estimated_net_worth_1y")
    private Double estimatedNetWorth1y;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Double getT212Total() {
        return t212Total;
    }

    public void setT212Total(Double t212Total) {
        this.t212Total = t212Total;
    }

    public Double getCryptoTotal() {
        return cryptoTotal;
    }

    public void setCryptoTotal(Double cryptoTotal) {
        this.cryptoTotal = cryptoTotal;
    }

    public Double getPensionTotal() {
        return pensionTotal;
    }

    public void setPensionTotal(Double pensionTotal) {
        this.pensionTotal = pensionTotal;
    }

    public Double getBarclaysSharesTotal() {
        return barclaysSharesTotal;
    }

    public void setBarclaysSharesTotal(Double barclaysSharesTotal) {
        this.barclaysSharesTotal = barclaysSharesTotal;
    }

    public Double getChaseTotal() {
        return chaseTotal;
    }

    public void setChaseTotal(Double chaseTotal) {
        this.chaseTotal = chaseTotal;
    }

    public Double getAmexDebtTotal() {
        return amexDebtTotal;
    }

    public void setAmexDebtTotal(Double amexDebtTotal) {
        this.amexDebtTotal = amexDebtTotal;
    }

    public Double getAmountLeft() {
        return amountLeft;
    }

    public void setAmountLeft(Double amountLeft) {
        this.amountLeft = amountLeft;
    }

    public Double getGrandTotal() {
        return grandTotal;
    }

    public void setGrandTotal(Double grandTotal) {
        this.grandTotal = grandTotal;
    }

    public Double getGrandTotalEur() {
        return grandTotalEur;
    }

    public void setGrandTotalEur(Double grandTotalEur) {
        this.grandTotalEur = grandTotalEur;
    }

    public Double getGrandTotalUsd() {
        return grandTotalUsd;
    }

    public void setGrandTotalUsd(Double grandTotalUsd) {
        this.grandTotalUsd = grandTotalUsd;
    }

    public Double getGrandTotalMxn() {
        return grandTotalMxn;
    }

    public void setGrandTotalMxn(Double grandTotalMxn) {
        this.grandTotalMxn = grandTotalMxn;
    }

    public Double getGrandTotalJpy() {
        return grandTotalJpy;
    }

    public void setGrandTotalJpy(Double grandTotalJpy) {
        this.grandTotalJpy = grandTotalJpy;
    }

    public String getAiCommentary() {
        return aiCommentary;
    }

    public void setAiCommentary(String aiCommentary) {
        this.aiCommentary = aiCommentary;
    }

    public Double getEstimatedNetWorth1y() {
        return estimatedNetWorth1y;
    }

    public void setEstimatedNetWorth1y(Double estimatedNetWorth1y) {
        this.estimatedNetWorth1y = estimatedNetWorth1y;
    }
}
