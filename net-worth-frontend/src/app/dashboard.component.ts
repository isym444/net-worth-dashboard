import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { daysUntilPaycheck, nextPaycheckDate } from './bank-holidays';
import { environment } from '../environments/environment';
import { HealthInsightResponse, MonthlyContributionResponse, NetWorthService, NetWorthSnapshot } from './net-worth.service';

@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit {
  private readonly netWorthService = inject(NetWorthService);

  protected readonly snapshots = signal<NetWorthSnapshot[]>([]);
  protected readonly expectedNetWorth = signal<{
    at40: number | null;
    at50: number | null;
    at60: number | null;
    at65: number | null;
    withContributionsAt40: number | null;
    withContributionsAt50: number | null;
    withContributionsAt60: number | null;
    withContributionsAt65: number | null;
  } | null>(null);
  protected readonly estimatedNetWorth1y = signal<number | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly takingSnapshot = signal(false);
  protected readonly refreshingInsights = signal(false);
  protected readonly healthInsight = signal<HealthInsightResponse | null>(null);
  protected readonly refreshingHealthInsight = signal(false);
  protected readonly chartUrl = signal<string | null>(null);
  protected readonly monthlyContributionInput = signal(0);
  protected readonly updatingContribution = signal(false);

  protected readonly latest = computed<NetWorthSnapshot | null>(() => {
    const list = this.snapshots();
    return list.length > 0 ? list[list.length - 1] : null;
  });

  protected readonly sortedDescending = computed(() =>
    [...this.snapshots()].sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()),
  );

  ngOnInit(): void {
    this.loadHistory();
    this.loadChart();
    this.loadHealthInsight();
  }

  loadHistory(showLoadingState = true): void {
    if (showLoadingState) {
      this.loading.set(true);
    }
    this.error.set(null);
    this.netWorthService.getHistory().subscribe({
      next: (data) => {
        this.snapshots.set(data.snapshots);
        this.expectedNetWorth.set({
          at40: data.expectedNetWorthAt40,
          at50: data.expectedNetWorthAt50,
          at60: data.expectedNetWorthAt60,
          at65: data.expectedNetWorthAt65,
          withContributionsAt40: data.expectedNetWorthWithContributionsAt40,
          withContributionsAt50: data.expectedNetWorthWithContributionsAt50,
          withContributionsAt60: data.expectedNetWorthWithContributionsAt60,
          withContributionsAt65: data.expectedNetWorthWithContributionsAt65,
        });
        this.estimatedNetWorth1y.set(data.estimatedNetWorth1y);
        this.monthlyContributionInput.set(data.monthlyContribution ?? 0);
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
      next: (blob) => this.chartUrl.set(blob && blob.size > 0 ? URL.createObjectURL(blob) : null),
      error: () => this.chartUrl.set(null),
    });
  }

  takeSnapshot(): void {
    this.takingSnapshot.set(true);
    this.error.set(null);
    this.netWorthService.takeSnapshot().subscribe({
      next: () => {
        this.takingSnapshot.set(false);
        this.loadHistory(false);
        this.loadChart();
      },
      error: (err: unknown) => {
        this.takingSnapshot.set(false);
        this.error.set(this.extractError(err));
      },
    });
  }

  refreshInsights(): void {
    this.refreshingInsights.set(true);
    this.error.set(null);
    this.netWorthService.refreshAmexInsights().subscribe({
      next: () => {
        this.refreshingInsights.set(false);
        this.loadHistory(false);
      },
      error: (err: unknown) => {
        this.refreshingInsights.set(false);
        this.error.set(this.extractError(err));
      },
    });
  }

  /** Loads whatever the daily cron last cached — never triggers an LLM call. */
  loadHealthInsight(): void {
    this.netWorthService.getHealthInsight().subscribe({
      next: (data) => this.healthInsight.set(data),
      error: () => this.healthInsight.set(null),
    });
  }

  /** Manual refresh button — the only other path (besides the daily cron) that calls the LLM. */
  refreshHealthInsight(): void {
    this.refreshingHealthInsight.set(true);
    this.error.set(null);
    this.netWorthService.refreshHealthInsight().subscribe({
      next: (data) => {
        this.refreshingHealthInsight.set(false);
        this.healthInsight.set(data);
      },
      error: (err: unknown) => {
        this.refreshingHealthInsight.set(false);
        this.error.set(this.extractError(err));
      },
    });
  }

  /** Persists the new monthly contribution and patches every dependent projection in place. */
  updateMonthlyContribution(rawValue: string): void {
    const value = Number(rawValue);
    if (!Number.isFinite(value) || value < 0) {
      this.error.set('Monthly contribution must be a non-negative number.');
      return;
    }

    this.updatingContribution.set(true);
    this.error.set(null);
    this.netWorthService.updateMonthlyContribution(value).subscribe({
      next: (response) => {
        this.updatingContribution.set(false);
        this.monthlyContributionInput.set(response.monthlyContribution);
        this.estimatedNetWorth1y.set(response.estimatedNetWorth1y);
        this.patchWithContributionsProjections(response);
      },
      error: (err: unknown) => {
        this.updatingContribution.set(false);
        this.error.set(this.extractError(err));
      },
    });
  }

  private patchWithContributionsProjections(response: MonthlyContributionResponse): void {
    const current = this.expectedNetWorth();
    if (current === null) {
      return;
    }
    this.expectedNetWorth.set({
      ...current,
      withContributionsAt40: response.expectedNetWorthWithContributionsAt40,
      withContributionsAt50: response.expectedNetWorthWithContributionsAt50,
      withContributionsAt60: response.expectedNetWorthWithContributionsAt60,
      withContributionsAt65: response.expectedNetWorthWithContributionsAt65,
    });
  }

  /**
   * Guaranteed monthly subscription spend — set aside before any daily-spend
   * math. Set via MONTHLY_SUBSCRIPTIONS at build time (see scripts/set-env.js);
   * change it in Render's env vars and redeploy, no code change needed.
   */
  private readonly monthlySubscriptions = environment.monthlySubscriptions;
  private readonly investBuffer = 400;

  /** Not persisted anywhere — purely derived from the latest snapshot + today's date. */
  protected dailySpend(snapshot: NetWorthSnapshot): number {
    const amountLeft = snapshot.chaseTotal - snapshot.amexDebtTotal;
    return (amountLeft - this.monthlySubscriptions) / this.daysUntilNextPaycheck();
  }

  /** Same as dailySpend, but also reserves £400 (chaseTotal - amexDebtTotal) to invest at month end. */
  protected dailySpendToInvestBuffer(snapshot: NetWorthSnapshot): number {
    const amountLeft = snapshot.chaseTotal - snapshot.amexDebtTotal;
    return (amountLeft - this.monthlySubscriptions - this.investBuffer) / this.daysUntilNextPaycheck();
  }

  protected daysUntilNextPaycheck(): number {
    const now = new Date();
    const payday = nextPaycheckDate(now);
    return daysUntilPaycheck(now, payday);
  }

  protected daysUntilNextPaycheckInWeeks(): string {
    const totalDays = this.daysUntilNextPaycheck();
    const weeks = Math.floor(totalDays / 7);
    const days = totalDays % 7;
    return `${weeks}w ${days}d`;
  }

  protected readonly annualIncomeTargets = [10000, 20000, 30000, 40000, 50000, 60000, 70000, 80000, 90000, 100000];
  protected readonly assumedAnnualReturn = 0.07;
  protected readonly safeWithdrawalRate = 0.04;
  protected readonly assumedAnnualReturnPercentLabel = `${Math.round(this.assumedAnnualReturn * 100)}%`;

  /** Matches NetWorthHistoryService.BIRTH_DATE on the backend, used only to label milestones with a projected age. */
  private readonly birthDate = new Date(1996, 5, 14);
  private readonly maxProjectionMonths = 1200;

  /**
   * Months of monthly-compounding growth (at assumedAnnualReturn), plus the current
   * monthly contribution deposited every month, until the portfolio can sustain
   * `targetAnnual` at `withdrawalRate`. Mirrors PortfolioProjectionService's
   * contribution model so this stays consistent with the backend's projections.
   */
  protected timeToSustainableIncome(snapshot: NetWorthSnapshot, targetAnnual: number, withdrawalRate: number): string {
    const requiredPortfolio = targetAnnual / withdrawalRate;
    return this.timeToReachPortfolio(snapshot.grandTotal, requiredPortfolio, this.monthlyContributionInput());
  }

  private timeToReachPortfolio(currentNetWorth: number, requiredPortfolio: number, monthlyContribution: number): string {
    if (currentNetWorth >= requiredPortfolio) {
      return `Reached (age ${this.ageAfterMonths(0)})`;
    }

    const monthlyGrowthFactor = Math.pow(1 + this.assumedAnnualReturn, 1 / 12);
    let balance = currentNetWorth;
    let months = 0;
    while (balance < requiredPortfolio && months < this.maxProjectionMonths) {
      balance = balance * monthlyGrowthFactor + monthlyContribution;
      months++;
    }
    if (balance < requiredPortfolio) {
      return '100y+';
    }
    return `${this.formatYearsMonths(months)} (age ${this.ageAfterMonths(months)})`;
  }

  private formatYearsMonths(totalMonths: number): string {
    const years = Math.floor(totalMonths / 12);
    const months = totalMonths % 12;
    return `${years}y ${months}m`;
  }

  private ageAfterMonths(months: number): number {
    const future = new Date();
    future.setMonth(future.getMonth() + months);
    let age = future.getFullYear() - this.birthDate.getFullYear();
    const hadBirthdayThisYear =
      future.getMonth() > this.birthDate.getMonth() ||
      (future.getMonth() === this.birthDate.getMonth() && future.getDate() >= this.birthDate.getDate());
    if (!hadBirthdayThisYear) {
      age--;
    }
    return age;
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
