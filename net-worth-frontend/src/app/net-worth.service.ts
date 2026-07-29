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
  grandTotal: number;
}

@Injectable({ providedIn: 'root' })
export class NetWorthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  getHistory(currency?: string): Observable<NetWorthSnapshot[]> {
    const url = currency
      ? `${this.baseUrl}/api/v1/history?currency=${encodeURIComponent(currency)}`
      : `${this.baseUrl}/api/v1/history`;
    return this.http.get<NetWorthSnapshot[]>(url);
  }

  takeSnapshot(): Observable<NetWorthSnapshot[]> {
    return this.http.post<NetWorthSnapshot[]>(`${this.baseUrl}/api/v1/snapshot`, {});
  }

  getChartBlob(currency?: string): Observable<Blob> {
    const url = currency
      ? `${this.baseUrl}/api/v1/chart?currency=${encodeURIComponent(currency)}`
      : `${this.baseUrl}/api/v1/chart`;
    return this.http.get(url, { responseType: 'blob' });
  }
}
