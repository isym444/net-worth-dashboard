import { PercentPipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { GoalProgress } from './goal-progress';

/** The two progress bars toward a target: share from existing assets, and share of the journey already travelled. */
@Component({
  selector: 'app-goal-bars',
  imports: [PercentPipe],
  template: `
    <div class="goal-bar" title="Share of the target that will come from the money you already hold, once it has grown">
      <span class="goal-bar__label">From existing assets</span>
      <div class="goal-bar__track"><div class="goal-bar__fill goal-bar__fill--growth" [style.width.%]="progress().growthShare * 100"></div></div>
      <strong>{{ progress().growthShare | percent: '1.1-1' }}</strong>
    </div>
    <div class="goal-bar" title="Share of the whole journey from zero (same contribution and return) already behind you">
      <span class="goal-bar__label">Time progress</span>
      @if (progress().timeShare !== null) {
        <div class="goal-bar__track"><div class="goal-bar__fill goal-bar__fill--time" [style.width.%]="(progress().timeShare ?? 0) * 100"></div></div>
        <strong>{{ progress().timeShare | percent: '1.1-1' }}</strong>
      } @else {
        <div class="goal-bar__track"></div>
        <strong class="goal-bar__na">n/a</strong>
      }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 0.5rem; }
    .goal-bar { display: grid; grid-template-columns: 9.5rem 1fr 4rem; align-items: center; gap: 0.75rem; }
    .goal-bar__label { color: var(--text-muted); font-size: 0.85rem; }
    .goal-bar__track { height: 8px; border-radius: 999px; background: var(--border); overflow: hidden; }
    .goal-bar__fill { height: 100%; border-radius: 999px; min-width: 6px; }
    .goal-bar__fill--growth { background: var(--accent); }
    .goal-bar__fill--time { background: var(--info); }
    .goal-bar strong { text-align: right; font-size: 0.95rem; font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }
    .goal-bar__na { color: var(--text-muted); font-weight: 500; }
    @media (max-width: 520px) { .goal-bar { grid-template-columns: 7rem 1fr 3.5rem; gap: 0.5rem; } }
  `,
})
export class GoalBarsComponent {
  readonly progress = input.required<GoalProgress>();
}
