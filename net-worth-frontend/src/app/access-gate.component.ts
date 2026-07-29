import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-access-gate',
  imports: [FormsModule],
  templateUrl: './access-gate.component.html',
  styleUrl: './access-gate.component.css',
})
export class AccessGateComponent {
  private readonly authService = inject(AuthService);
  protected readonly keyInput = signal('');

  unlock(): void {
    const trimmed = this.keyInput().trim();
    if (trimmed.length > 0) {
      this.authService.unlock(trimmed);
    }
  }
}
