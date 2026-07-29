import { Component, inject } from '@angular/core';
import { AccessGateComponent } from './access-gate.component';
import { AuthService } from './auth.service';
import { DashboardComponent } from './dashboard.component';

@Component({
  selector: 'app-root',
  imports: [AccessGateComponent, DashboardComponent],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly authService = inject(AuthService);
}
