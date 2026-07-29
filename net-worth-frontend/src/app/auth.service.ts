import { Injectable, computed, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _apiKey = signal<string | null>(null);
  readonly apiKey = this._apiKey.asReadonly();
  readonly isUnlocked = computed(() => this._apiKey() !== null);

  unlock(key: string): void {
    this._apiKey.set(key);
  }
}
