import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { HealthResponse, HealthService } from '../../core/health/health.service';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * Shows the backend health check, proving the chain browser → frontend → backend → database.
 * Health is a public endpoint, so this page works even if the token has just expired.
 */
@Component({
  selector: 'mr-status-page',
  template: `
    <h1 class="text-2xl font-semibold tracking-tight">System status</h1>
    <p class="mt-1 text-sm text-slate-500">Live health of the API and its database.</p>
    <div class="mt-6 max-w-xl">
      <div class="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        @switch (state()) {
          @case ('loading') {
            <p class="animate-pulse text-slate-500">Checking backend…</p>
          }
          @case ('error') {
            <div class="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
              <p class="font-medium">Backend unreachable</p>
              <p class="mt-1">{{ errorMessage() }}</p>
            </div>
          }
          @case ('ready') {
            <ul class="divide-y divide-slate-100 rounded-lg border border-slate-200">
              <li class="flex items-center justify-between px-4 py-3">
                <span>Backend API</span>
                <span [class]="badgeClass(health()?.status)">{{ health()?.status }}</span>
              </li>
              @for (component of components(); track component.name) {
                <li class="flex items-center justify-between px-4 py-3">
                  <span class="capitalize">{{ component.name }}</span>
                  <span [class]="badgeClass(component.status)">{{ component.status }}</span>
                </li>
              }
            </ul>
          }
        }

        <div class="mt-6 flex items-center justify-between">
          <a href="/swagger-ui/index.html" class="text-sm font-medium text-teal-700 hover:underline">API docs (Swagger)</a>
          <button
            type="button"
            (click)="refresh()"
            class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
            [disabled]="state() === 'loading'"
          >
            Refresh
          </button>
        </div>
      </div>
    </div>
  `,
})
export class StatusPage implements OnInit {
  private readonly healthService = inject(HealthService);

  protected readonly state = signal<LoadState>('loading');
  protected readonly health = signal<HealthResponse | null>(null);
  protected readonly errorMessage = signal('');

  protected readonly components = computed(() =>
    Object.entries(this.health()?.components ?? {}).map(([name, value]) => ({ name, status: value.status })),
  );

  ngOnInit(): void {
    this.refresh();
  }

  protected refresh(): void {
    this.state.set('loading');
    this.healthService.check().subscribe({
      next: (response) => {
        this.health.set(response);
        this.state.set('ready');
      },
      error: (error: HttpErrorResponse) => {
        // Actuator returns 503 with a body when a component (e.g. the database) is DOWN.
        if (error.status === 503 && error.error?.status) {
          this.health.set(error.error as HealthResponse);
          this.state.set('ready');
          return;
        }
        this.errorMessage.set(error.status === 0 ? 'No response from the server.' : `HTTP ${error.status}`);
        this.state.set('error');
      },
    });
  }

  protected badgeClass(status: string | undefined): string {
    const base = 'rounded-full px-2.5 py-0.5 text-xs font-semibold';
    return status === 'UP' ? `${base} bg-emerald-100 text-emerald-700` : `${base} bg-red-100 text-red-700`;
  }
}
