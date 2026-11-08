import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { EMPTY, Observable, Subject, catchError, startWith, switchMap, tap, timer } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/problem';
import { ToastService } from '../../core/toast/toast.service';
import { ACTION_BADGE, AuditAction, actionLabel } from '../audit/audit.models';
import { ImportStatusBadge } from '../imports/import-status-badge';
import { BloodType, bloodTypeLabel } from '../patients/patient.models';
import { BloodTypeCount, Dashboard, barWidth, rejectionRate } from './dashboard.models';
import { DashboardService } from './dashboard.service';

/** While an import is queued or running, the figures refresh themselves at this interval. */
const LIVE_REFRESH_MS = 5000;

/** Home page: summary cards, patients per blood type, latest imports and (admins) latest changes. */
@Component({
  selector: 'mr-dashboard-page',
  imports: [DatePipe, DecimalPipe, RouterLink, ImportStatusBadge],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight">Welcome, {{ auth.user()?.fullName }}</h1>
        <p class="mt-1 text-sm text-slate-500">
          Clinic overview{{ isAdmin() ? '' : ' (read-only access)' }}.
          @if (data(); as current) { Figures as of {{ current.generatedAt | date: 'HH:mm' }}; weekly figures cover the last 7 days. }
        </p>
      </div>
      <button type="button" (click)="reload()" [disabled]="loading()"
              class="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 shadow-sm hover:bg-slate-50 disabled:opacity-50">
        <svg class="h-4 w-4" [class.animate-spin]="loading()" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M20 12a8 8 0 1 1-2.34-5.66M20 4v4h-4" /></svg>
        Refresh
      </button>
    </div>

    @if (loadError() && !data()) {
      <div class="mt-6 rounded-2xl border border-slate-200 bg-white px-6 py-16 text-center shadow-sm">
        <p class="font-medium text-slate-900">Could not load the dashboard</p>
        <p class="mt-1 text-sm text-slate-500">{{ loadError() }}</p>
        <button type="button" (click)="reload()" class="mt-4 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700">Try again</button>
      </div>
    } @else {
      <!-- Summary cards -->
      <dl class="mt-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4" [attr.aria-busy]="!data()">
        @if (data(); as d) {
          <div class="card">
            <dt class="card-label">Total patients</dt>
            <dd class="card-value">{{ d.patients.total | number }}</dd>
            <dd class="card-note">
              @if (d.patients.addedSince > 0) { <span class="font-medium text-emerald-700">+{{ d.patients.addedSince | number }}</span> in the last 7 days }
              @else { None added in the last 7 days }
            </dd>
          </div>
          <div class="card">
            <dt class="card-label">Imports this week</dt>
            <dd class="card-value">{{ d.imports.count | number }}</dd>
            <dd class="card-note">
              @if (d.imports.count === 0) { No CSV imports in the last 7 days }
              @else {
                {{ d.imports.validRows | number }} patients added
                @if (d.imports.failedImports > 0) { · <span class="font-medium text-red-700">{{ d.imports.failedImports }} failed</span> }
                @if (d.imports.inProgress > 0) { · <span class="font-medium text-sky-700">{{ d.imports.inProgress }} running</span> }
              }
            </dd>
          </div>
          <div class="card">
            <dt class="card-label">Failed rows this week</dt>
            <dd class="card-value" [class.text-amber-700]="d.imports.invalidRows > 0">{{ d.imports.invalidRows | number }}</dd>
            <dd class="card-note">
              @if (rate(d) !== null) { {{ rate(d) }}% of imported rows were rejected }
              @else { No rows imported in the last 7 days }
            </dd>
          </div>
          <div class="card">
            <dt class="card-label">Recent activity</dt>
            @if (isAdmin()) {
              <dd class="card-value">{{ d.recentActivity?.length ? (d.recentActivity![0].occurredAt | date: 'MMM d, HH:mm') : '—' }}</dd>
              <dd class="card-note truncate" [title]="d.recentActivity?.[0]?.summary ?? ''">
                @if (d.recentActivity?.[0]; as last) { Last change by {{ last.actor }}: {{ last.summary }} } @else { No changes recorded yet }
              </dd>
            } @else {
              <dd class="card-value">{{ d.recentImports.length ? (d.recentImports[0].createdAt | date: 'MMM d') : '—' }}</dd>
              <dd class="card-note truncate">
                @if (d.recentImports[0]; as last) { Latest import: {{ last.fileName }} } @else { No imports yet }
              </dd>
            }
          </div>
        } @else {
          @for (card of [0, 1, 2, 3]; track card) {
            <div class="card" aria-hidden="true">
              <div class="h-3 w-24 animate-pulse rounded bg-slate-200"></div>
              <div class="mt-3 h-7 w-16 animate-pulse rounded bg-slate-200"></div>
              <div class="mt-3 h-3 w-40 animate-pulse rounded bg-slate-100"></div>
            </div>
          }
        }
      </dl>

      <div class="mt-6 grid gap-6 lg:grid-cols-2">
        <!-- Chart: patients per blood type -->
        <section class="panel" aria-labelledby="blood-type-heading">
          <div class="panel-header">
            <h2 id="blood-type-heading" class="panel-title">Patients by blood type</h2>
            <a routerLink="/patients" class="panel-link">View patients</a>
          </div>
          <div class="p-5">
            @if (data(); as d) {
              @if (d.patients.total === 0) {
                <p class="py-10 text-center text-sm text-slate-500">No patients yet.</p>
              } @else {
                <ul class="relative space-y-2.5">
                  @for (group of d.patients.byBloodType; track group.bloodType) {
                    <li class="grid grid-cols-[3.75rem_1fr_3.25rem] items-center gap-2 text-sm sm:grid-cols-[4.5rem_1fr_3.5rem] sm:gap-3">
                      <span class="font-medium text-slate-700">{{ label(group.bloodType) }}</span>
                      <span class="h-5 overflow-hidden rounded bg-slate-100" aria-hidden="true">
                        <span class="block h-full rounded transition-[width] duration-500"
                              [class]="group.bloodType ? 'bg-teal-600' : 'bg-slate-400'"
                              [style.width.%]="width(group, d.patients.byBloodType)"></span>
                      </span>
                      <span class="text-right tabular-nums text-slate-600">
                        {{ group.count | number }}<span class="sr-only"> patients ({{ share(group.count, d.patients.total) }}%)</span>
                      </span>
                    </li>
                  }
                </ul>
              }
            } @else {
              <div class="space-y-2.5" aria-hidden="true">
                @for (row of [0, 1, 2, 3, 4, 5, 6, 7]; track row) { <div class="h-5 animate-pulse rounded bg-slate-100"></div> }
              </div>
            }
          </div>
        </section>

        <!-- Latest imports (everyone) -->
        <section class="panel" aria-labelledby="recent-imports-heading">
          <div class="panel-header">
            <h2 id="recent-imports-heading" class="panel-title">Latest imports</h2>
            <a routerLink="/imports" class="panel-link">Import history</a>
          </div>
          @if (data(); as d) {
            <ul class="divide-y divide-slate-100">
              @for (item of d.recentImports; track item.id) {
                <li>
                  <a [routerLink]="['/imports', item.id]" class="flex items-center justify-between gap-3 px-5 py-3 hover:bg-slate-50">
                    <span class="min-w-0">
                      <span class="block truncate text-sm font-medium text-slate-900" [title]="item.fileName">{{ item.fileName }}</span>
                      <span class="block text-xs text-slate-500">
                        {{ item.createdAt | date: 'MMM d, HH:mm' }} · {{ item.importedBy }} ·
                        {{ item.validRows | number }} valid<span [class.text-amber-700]="item.invalidRows > 0">, {{ item.invalidRows | number }} invalid</span>
                      </span>
                    </span>
                    <mr-import-status-badge [status]="item.status" />
                  </a>
                </li>
              } @empty {
                <li class="px-5 py-10 text-center text-sm text-slate-500">
                  No imports yet.
                  @if (isAdmin()) { <a routerLink="/import" class="font-medium text-teal-700 hover:underline">Import a CSV file</a> }
                </li>
              }
            </ul>
          } @else {
            <div class="space-y-3 p-5" aria-hidden="true">
              @for (row of [0, 1, 2, 3]; track row) { <div class="h-9 animate-pulse rounded bg-slate-100"></div> }
            </div>
          }
        </section>

        <!-- Latest changes from the audit log (admins only; the API omits it for viewers) -->
        @if (isAdmin()) {
          <section class="panel lg:col-span-2" aria-labelledby="activity-heading">
            <div class="panel-header">
              <h2 id="activity-heading" class="panel-title">Recent changes</h2>
              <a routerLink="/audit-log" class="panel-link">Full audit log</a>
            </div>
            @if (data(); as d) {
              <ul class="divide-y divide-slate-100">
                @for (entry of d.recentActivity ?? []; track entry.id) {
                  <li class="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 py-3 text-sm">
                    <span class="inline-flex w-20 justify-center rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset" [class]="badge(entry.action)">{{ actionName(entry.action) }}</span>
                    <span class="min-w-0 flex-1 truncate text-slate-800" [title]="entry.summary">
                      @if (entry.action === 'IMPORT' && entry.entityId !== null) {
                        <a [routerLink]="['/imports', entry.entityId]" class="hover:underline">{{ entry.summary }}</a>
                      } @else { {{ entry.summary }} }
                    </span>
                    <span class="whitespace-nowrap text-xs text-slate-500">{{ entry.actor }} · {{ entry.occurredAt | date: 'MMM d, HH:mm' }}</span>
                  </li>
                } @empty {
                  <li class="px-5 py-10 text-center text-sm text-slate-500">No changes recorded yet.</li>
                }
              </ul>
            } @else {
              <div class="space-y-3 p-5" aria-hidden="true">
                @for (row of [0, 1, 2]; track row) { <div class="h-6 animate-pulse rounded bg-slate-100"></div> }
              </div>
            }
          </section>
        }
      </div>
    }
  `,
  styles: `
    .card { border-radius: 1rem; border: 1px solid var(--color-slate-200); background: white; padding: 1rem 1.25rem; box-shadow: 0 1px 2px rgb(0 0 0 / 0.05); min-width: 0; }
    .card-label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; color: var(--color-slate-500); }
    .card-value { margin-top: 0.5rem; font-size: 1.875rem; font-weight: 600; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; color: var(--color-slate-900); }
    .card-note { margin-top: 0.25rem; font-size: 0.8125rem; color: var(--color-slate-500); }
    .panel { overflow: hidden; border-radius: 1rem; border: 1px solid var(--color-slate-200); background: white; box-shadow: 0 1px 2px rgb(0 0 0 / 0.05); }
    .panel-header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; border-bottom: 1px solid var(--color-slate-200); padding: 0.875rem 1.25rem; }
    .panel-title { font-size: 0.9375rem; font-weight: 600; color: var(--color-slate-900); }
    .panel-link { font-size: 0.8125rem; font-weight: 500; color: var(--color-teal-700); }
    .panel-link:hover { text-decoration: underline; }
  `,
})
export class DashboardPage {
  protected readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  private readonly toast = inject(ToastService);

  protected readonly isAdmin = this.auth.isAdmin;
  protected readonly data = signal<Dashboard | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal('');
  private readonly reloads = new Subject<void>();

  constructor() {
    this.reloads
      .pipe(
        startWith(undefined),
        switchMap(() => this.load()),
        takeUntilDestroyed(),
      )
      .subscribe();
  }

  protected reload(): void {
    this.reloads.next();
  }

  protected label(type: BloodType | null): string {
    return type ? bloodTypeLabel(type) : 'Unknown';
  }

  protected width(group: BloodTypeCount, groups: BloodTypeCount[]): number {
    return barWidth(group.count, groups);
  }

  protected share(count: number, total: number): number {
    return total === 0 ? 0 : Math.round((count * 100) / total);
  }

  protected rate(d: Dashboard): number | null {
    return rejectionRate(d.imports);
  }

  protected badge(action: AuditAction): string {
    return ACTION_BADGE[action];
  }

  protected actionName(action: AuditAction): string {
    return actionLabel(action);
  }

  /** Fetches the figures; while an import is still running, fetches them again after a short delay. */
  private load(): Observable<unknown> {
    this.loading.set(true);
    return this.dashboard.get().pipe(
      tap((result) => {
        this.data.set(result);
        this.loadError.set('');
        this.loading.set(false);
      }),
      switchMap((result) => (result.imports.inProgress > 0 ? timer(LIVE_REFRESH_MS).pipe(switchMap(() => this.load())) : EMPTY)),
      catchError((error: unknown) => {
        this.loading.set(false);
        this.loadError.set(errorMessage(error));
        if (this.data()) this.toast.error(errorMessage(error));
        return EMPTY;
      }),
    );
  }
}
