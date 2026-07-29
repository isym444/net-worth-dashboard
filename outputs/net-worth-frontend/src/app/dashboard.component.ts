import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { NetWorthService, NetWorthSnapshot } from './net-worth.service';

@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit {
  private readonly netWorthService = inject(NetWorthService);

  protected readonly snapshots = signal<NetWorthSnapshot[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly takingSnapshot = signal(false);
  protected readonly chartUrl = signal<string | null>(null);

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
  }

  loadHistory(showLoadingState = true): void {
    if (showLoadingState) {
      this.loading.set(true);
    }
    this.error.set(null);
    this.netWorthService.getHistory().subscribe({
      next: (data) => {
        this.snapshots.set(data);
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
