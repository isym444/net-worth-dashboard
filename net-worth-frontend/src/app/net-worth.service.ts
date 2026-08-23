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
  pensionTotal: number;
  barclaysSharesTotal: number;
  chaseTotal: number;
  amexDebtTotal: number;
  amountLeft: number;
  grandTotal: number;
  grandTotalEur: number | null;
  grandTotalUsd: number | null;
  grandTotalMxn: number | null;
  grandTotalJpy: number | null;
  aiCommentary: string | null;
}

export interface NetWorthHistoryResponse {
  snapshots: NetWorthSnapshot[];
  expectedNetWorthAt40: number | null;
  expectedNetWorthAt50: number | null;
  expectedNetWorthAt60: number | null;
  expectedNetWorthAt65: number | null;
  expectedNetWorthWithContributionsAt40: number | null;
  expectedNetWorthWithContributionsAt50: number | null;
  expectedNetWorthWithContributionsAt60: number | null;
  expectedNetWorthWithContributionsAt65: number | null;
  estimatedNetWorth1y: number | null;
  monthlyContribution: number | null;
}

export interface AmexInsightsResponse {
  commentary: string;
  generatedAt: string;
  transactionCount: number;
}

export interface HealthInsightResponse {
  commentary: string;
  generatedAt: string;
}

export interface MonthlyContributionResponse {
  monthlyContribution: number;
  estimatedNetWorth1y: number | null;
  expectedNetWorthWithContributionsAt40: number | null;
  expectedNetWorthWithContributionsAt50: number | null;
  expectedNetWorthWithContributionsAt60: number | null;
  expectedNetWorthWithContributionsAt65: number | null;
}

@Injectable({ providedIn: 'root' })
export class NetWorthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  getHistory(currency?: string): Observable<NetWorthHistoryResponse> {
    const url = currency
      ? `${this.baseUrl}/api/v1/history?currency=${encodeURIComponent(currency)}`
      : `${this.baseUrl}/api/v1/history`;
    return this.http.get<NetWorthHistoryResponse>(url);
  }

  takeSnapshot(): Observable<NetWorthSnapshot[]> {
    return this.http.post<NetWorthSnapshot[]>(`${this.baseUrl}/api/v1/snapshot`, {});
  }

  refreshAmexInsights(): Observable<AmexInsightsResponse> {
    return this.http.post<AmexInsightsResponse>(`${this.baseUrl}/api/v1/amex/insights`, {});
  }

  getHealthInsight(): Observable<HealthInsightResponse | null> {
    return this.http.get<HealthInsightResponse | null>(`${this.baseUrl}/api/v1/health/insight`);
  }

  refreshHealthInsight(): Observable<HealthInsightResponse> {
    return this.http.post<HealthInsightResponse>(`${this.baseUrl}/api/v1/health/insight/refresh`, {});
  }

  updateMonthlyContribution(monthlyContribution: number): Observable<MonthlyContributionResponse> {
    return this.http.post<MonthlyContributionResponse>(
      `${this.baseUrl}/api/v1/settings/monthly-contribution`,
      { monthlyContribution },
    );
  }

  getChartBlob(currency?: string): Observable<Blob> {
    const url = currency
      ? `${this.baseUrl}/api/v1/chart?currency=${encodeURIComponent(currency)}`
      : `${this.baseUrl}/api/v1/chart`;
    return this.http.get(url, { responseType: 'blob' });
  }
}
