package com.isym444.networth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Single-row settings table (id always {@link #SINGLETON_ID}) for portfolio
 * projection inputs the user can adjust from the dashboard, currently just
 * the assumed monthly contribution.
 */
@Entity
@Table(name = "portfolio_settings")
public class PortfolioSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "monthly_contribution", nullable = false)
    private Double monthlyContribution;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Double getMonthlyContribution() {
        return monthlyContribution;
    }

    public void setMonthlyContribution(Double monthlyContribution) {
        this.monthlyContribution = monthlyContribution;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
