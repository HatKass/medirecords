import { DatePipe, DecimalPipe, NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Params, Router, RouterLink } from '@angular/router';
import { catchError, map, of, switchMap, tap } from 'rxjs';
import { errorMessage } from '../../core/http/problem';
import { ToastService } from '../../core/toast/toast.service';
import { Page } from '../patients/patient.models';
import {
  ACTION_BADGE,
  AUDIT_ACTIONS,
  AuditAction,
  AuditEntry,
  AuditQuery,
  actionLabel,
  blockedMinutes,
  displayValue,
  fieldLabel,
  isAuthenticationEvent,
} from './audit.models';
import { AuditService } from './audit.service';

const PAGE_SIZE = 25;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Filters live in the URL (?actor=admin&action=IMPORT&from=2026-11-01), so links and reloads keep them. */
function parseQuery(params: ParamMap): AuditQuery {
  const page = Number(params.get('page'));
  const action = params.get('action') as AuditAction;
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  return {
    page: Number.isInteger(page) && page > 0 ? page : 0,
    actor: params.get('actor') ?? '',
    action: AUDIT_ACTIONS.some((item) => item.value === action) ? action : '',
    from: ISO_DATE.test(from) ? from : '',
    to: ISO_DATE.test(to) ? to : '',
  };
}

function toParams(query: AuditQuery): Params {
  return {
    page: query.page || null,
    actor: query.actor || null,
    action: query.action || null,
    from: query.from || null,
    to: query.to || null,
  };
}

type LoadResult = { page: Page<AuditEntry> } | { error: string } | { invalidRange: true };

@Component({
  selector: 'mr-audit-log-page',
  imports: [DatePipe, DecimalPipe, NgTemplateOutlet, RouterLink],
  template: `
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">Audit log</h1>
      <p class="mt-1 text-sm text-slate-500">
        Every change to patient records, with who made it and what changed, plus sign-ins and sign-outs.
        A CSV import is one entry with its counts.
      </p>
    </div>

    <!-- Filters -->
    <div class="mt-6 grid grid-cols-2 gap-3 rounded-2xl border border-slate-200 bg-white p-3 shadow-sm sm:p-4 lg:grid-cols-[repeat(4,minmax(0,1fr))_auto]">
      <label class="block">
        <span class="filter-label">User</span>
        <select class="filter-input" (change)="update({ actor: $any($event.target).value, page: 0 })">
          <option value="">All users</option>
          @for (actor of actors(); track actor) { <option [value]="actor" [selected]="actor === query().actor">{{ actor }}</option> }
        </select>
      </label>
      <label class="block">
        <span class="filter-label">Action</span>
        <select class="filter-input" (change)="update({ action: $any($event.target).value, page: 0 })">
          <option value="">All actions</option>
          @for (action of actions; track action.value) { <option [value]="action.value" [selected]="action.value === query().action">{{ action.label }}</option> }
        </select>
      </label>
      <label class="block">
        <span class="filter-label">From</span>
        <input type="date" class="filter-input" [value]="query().from" [max]="query().to || null"
               (change)="update({ from: $any($event.target).value, page: 0 })" />
      </label>
      <label class="block">
        <span class="filter-label">To</span>
        <input type="date" class="filter-input" [value]="query().to" [min]="query().from || null"
               (change)="update({ to: $any($event.target).value, page: 0 })" />
      </label>
      <div class="col-span-2 flex items-end lg:col-span-1">
        <button type="button" (click)="clearFilters()" [disabled]="!hasFilters()"
                class="w-full rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40">
          Clear filters
        </button>
      </div>
    </div>

    <!-- Results -->
    <div class="mt-4 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
      @if (invalidRange()) {
        <div class="px-6 py-16 text-center">
          <p class="font-medium text-slate-900">The date range is backwards</p>
          <p class="mt-1 text-sm text-slate-500">"From" must be on or before "To".</p>
        </div>
      } @else if (loadError() && !page()) {
        <div class="px-6 py-16 text-center">
          <p class="font-medium text-slate-900">Could not load the audit log</p>
          <p class="mt-1 text-sm text-slate-500">{{ loadError() }}</p>
          <button type="button" (click)="reload()" class="mt-4 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700">Try again</button>
        </div>
      } @else {
        <!-- Phones: one card per entry; tap to expand -->
        <ul class="divide-y divide-slate-100 md:hidden" [class.opacity-60]="loading() && page()" [attr.aria-busy]="loading()">
          @if (!page()) {
            @for (row of [0, 1, 2, 3, 4]; track row) {
              <li class="space-y-2 px-4 py-4" aria-hidden="true">
                <div class="h-3 w-1/3 animate-pulse rounded bg-slate-200"></div>
                <div class="h-4 w-3/4 animate-pulse rounded bg-slate-100"></div>
              </li>
            }
          } @else {
            @for (entry of page()!.content; track entry.id) {
              <li>
                <button type="button" class="flex w-full items-start gap-3 px-4 py-3 text-left" (click)="toggle(entry)"
                        [attr.aria-expanded]="expandedId() === entry.id">
                  <span class="min-w-0 flex-1">
                    <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
                      <span class="inline-flex rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset" [class]="badge(entry.action)">{{ label(entry.action) }}</span>
                      <span class="text-xs text-slate-500">{{ entry.actor }} · {{ entry.occurredAt | date: 'MMM d, HH:mm:ss' }}</span>
                    </span>
                    <span class="mt-1 block text-sm text-slate-800">{{ entry.summary }}</span>
                    @if (entry.entityId !== null) {
                      <span class="block text-xs text-slate-400">{{ entry.entityType }} #{{ entry.entityId }}</span>
                    }
                  </span>
                  <svg class="mt-1 h-4 w-4 shrink-0 text-slate-400 transition-transform" [class.rotate-180]="expandedId() === entry.id" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
                </button>
                @if (expandedId() === entry.id) {
                  <div class="overflow-x-auto bg-slate-50/70 px-4 pb-4 pt-1">
                    <ng-container *ngTemplateOutlet="entryDetails; context: { $implicit: entry }" />
                  </div>
                }
              </li>
            } @empty {
              <li class="px-6 py-14 text-center">
                <p class="font-medium text-slate-900">{{ hasFilters() ? 'No entries match these filters' : 'No activity yet' }}</p>
                <p class="mt-1 text-sm text-slate-500">
                  {{ hasFilters() ? 'Try a wider date range or clear the filters.' : 'Sign-ins and changes to patients will show up here.' }}
                </p>
              </li>
            }
          }
        </ul>

        <!-- Tablets and up: table. "relative" keeps the sr-only header label inside the scroll box. -->
        <div class="relative hidden overflow-x-auto md:block" [class.opacity-60]="loading() && page()" [attr.aria-busy]="loading()">
          <table class="min-w-full divide-y divide-slate-200 text-sm">
            <thead class="bg-slate-50 text-left text-xs font-semibold uppercase tracking-wide text-slate-500">
              <tr>
                <th scope="col" class="px-4 py-3">When</th>
                <th scope="col" class="px-4 py-3">User</th>
                <th scope="col" class="px-4 py-3">Action</th>
                <th scope="col" class="px-4 py-3">Summary</th>
                <th scope="col" class="w-10 px-4 py-3"><span class="sr-only">Details</span></th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @if (!page()) {
                @for (row of [0, 1, 2, 3, 4]; track row) {
                  <tr>
                    @for (cell of [0, 1, 2, 3, 4]; track cell) {
                      <td class="px-4 py-4"><div class="h-3.5 animate-pulse rounded bg-slate-200"></div></td>
                    }
                  </tr>
                }
              } @else {
                @for (entry of page()!.content; track entry.id) {
                  <tr class="cursor-pointer hover:bg-slate-50" (click)="toggle(entry)">
                    <td class="whitespace-nowrap px-4 py-3 text-slate-600" [title]="entry.occurredAt">{{ entry.occurredAt | date: 'MMM d, y, HH:mm:ss' }}</td>
                    <td class="whitespace-nowrap px-4 py-3 font-medium text-slate-900">{{ entry.actor }}</td>
                    <td class="whitespace-nowrap px-4 py-3">
                      <span class="inline-flex rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset" [class]="badge(entry.action)">{{ label(entry.action) }}</span>
                    </td>
                    <td class="px-4 py-3 text-slate-700">
                      <p class="max-w-xl">{{ entry.summary }}</p>
                      @if (entry.entityId !== null) {
                        <p class="text-xs text-slate-400">{{ entry.entityType }} #{{ entry.entityId }}</p>
                      }
                    </td>
                    <td class="px-4 py-3 text-right">
                      <button type="button" class="rounded-md p-1 text-slate-500 hover:bg-slate-100" (click)="$event.stopPropagation(); toggle(entry)"
                              [attr.aria-expanded]="expandedId() === entry.id" [attr.aria-label]="(expandedId() === entry.id ? 'Hide' : 'Show') + ' details'">
                        <svg class="h-4 w-4 transition-transform" [class.rotate-180]="expandedId() === entry.id" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
                      </button>
                    </td>
                  </tr>
                  @if (expandedId() === entry.id) {
                    <tr class="bg-slate-50/70">
                      <td colspan="5" class="px-4 pb-4 pt-1">
                        <ng-container *ngTemplateOutlet="entryDetails; context: { $implicit: entry }" />
                      </td>
                    </tr>
                  }
                } @empty {
                  <tr>
                    <td colspan="5" class="px-6 py-16 text-center">
                      <p class="font-medium text-slate-900">{{ hasFilters() ? 'No entries match these filters' : 'No activity yet' }}</p>
                      <p class="mt-1 text-sm text-slate-500">
                        {{ hasFilters() ? 'Try a wider date range or clear the filters.' : 'Sign-ins and changes to patients will show up here.' }}
                      </p>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>

        @if (page(); as current) {
          @if (current.totalPages > 1) {
            <div class="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 px-4 py-3 text-sm text-slate-600">
              <p>{{ current.totalElements | number }} entries</p>
              <div class="flex items-center gap-3">
                <span class="text-slate-500">Page {{ current.page + 1 }} of {{ current.totalPages }}</span>
                <button type="button" class="pager-button" [disabled]="current.page === 0" (click)="update({ page: current.page - 1 })" aria-label="Previous page">‹</button>
                <button type="button" class="pager-button" [disabled]="current.page + 1 >= current.totalPages" (click)="update({ page: current.page + 1 })" aria-label="Next page">›</button>
              </div>
            </div>
          }
        }
      }
    </div>

    <!-- Details of one entry: shown under its row (table) or its card (phones). -->
    <ng-template #entryDetails let-entry>
      @if (entry.action === 'IMPORT') {
        <dl class="grid max-w-2xl grid-cols-2 gap-3 sm:grid-cols-4">
          <div class="detail-card"><dt>Status</dt><dd [class.text-rose-700]="entry.details.status === 'FAILED'">{{ entry.details.status }}</dd></div>
          <div class="detail-card"><dt>Total rows</dt><dd>{{ entry.details.totalRows | number }}</dd></div>
          <div class="detail-card"><dt>Valid</dt><dd class="text-emerald-700">{{ entry.details.validRows | number }}</dd></div>
          <div class="detail-card"><dt>Invalid</dt><dd [class.text-amber-700]="(entry.details.invalidRows ?? 0) > 0">{{ entry.details.invalidRows | number }}</dd></div>
        </dl>
        @if (entry.details.failureMessage) {
          <p class="mt-3 max-w-2xl text-sm text-rose-700">{{ entry.details.failureMessage }}</p>
        }
        <a [routerLink]="['/imports', entry.entityId]" class="mt-3 inline-block text-sm font-medium text-teal-700 hover:underline">
          View import #{{ entry.entityId }} and its rejected rows →
        </a>
      } @else if (isAuthEvent(entry.action)) {
        <p class="text-sm text-slate-500">
          {{ entry.action === 'LOGIN_FAILED' || entry.action === 'LOGIN_BLOCKED' ? 'Username as typed' : 'User' }}: <span class="font-medium text-slate-700">{{ entry.actor }}</span>
          · {{ entry.occurredAt | date: 'MMM d, y, HH:mm:ss.SSS' }}.
          @if (entry.action === 'LOGIN_BLOCKED') {
            Further attempts from <span class="font-medium text-slate-700">{{ entry.details.clientIp }}</span> were refused
            for {{ blockedMinutes(entry.details.retryAfterSeconds) }} after too many failed sign-ins (recorded once per block).
          }
          Sign-in events never record a password.
        </p>
      } @else if (entry.changes.length > 0) {
        <table class="w-full max-w-3xl overflow-hidden rounded-lg border border-slate-200 bg-white text-sm">
          <thead class="bg-slate-50 text-left text-xs font-semibold text-slate-500">
            <tr>
              <th scope="col" class="px-3 py-2">Field</th>
              <th scope="col" class="px-3 py-2">{{ entry.action === 'CREATE' ? '' : 'Before' }}</th>
              <th scope="col" class="px-3 py-2">{{ entry.action === 'DELETE' ? '' : 'After' }}</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            @for (change of entry.changes; track change.field) {
              <tr>
                <td class="whitespace-nowrap px-3 py-2 font-medium text-slate-700">{{ field(change.field) }}</td>
                <td class="px-3 py-2 break-all" [class]="entry.action === 'UPDATE' ? 'text-rose-700 line-through decoration-rose-300' : 'text-slate-700'">
                  @if (entry.action !== 'CREATE') { {{ value(change.field, change.oldValue) }} }
                </td>
                <td class="px-3 py-2 break-all" [class]="entry.action === 'UPDATE' ? 'text-emerald-700' : 'text-slate-700'">
                  @if (entry.action !== 'DELETE') { {{ value(change.field, change.newValue) }} }
                </td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <p class="text-sm text-slate-500">No field details were recorded for this entry.</p>
      }
    </ng-template>
  `,
  styles: `
    .filter-label { display: block; margin-bottom: 0.25rem; font-size: 0.75rem; font-weight: 500; color: var(--color-slate-500); }
    .filter-input {
      display: block; width: 100%; border-radius: 0.5rem; border: 1px solid var(--color-slate-300); background: white;
      padding: 0.5rem 0.75rem; font-size: 0.875rem; outline: none;
    }
    .filter-input:focus { border-color: var(--color-teal-600); }
    .detail-card { border-radius: 0.5rem; border: 1px solid var(--color-slate-200); background: white; padding: 0.5rem 0.75rem; }
    .detail-card dt { font-size: 0.75rem; color: var(--color-slate-500); }
    .detail-card dd { font-size: 1rem; font-weight: 600; font-variant-numeric: tabular-nums; }
    .pager-button { border-radius: 0.375rem; border: 1px solid var(--color-slate-300); padding: 0.25rem 0.75rem; font-size: 1rem; }
    .pager-button:hover:not(:disabled) { background: var(--color-slate-50); }
    .pager-button:disabled { opacity: 0.4; cursor: not-allowed; }
  `,
})
export class AuditLogPage {
  private readonly audit = inject(AuditService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);

  protected readonly actions = AUDIT_ACTIONS;
  protected readonly query = toSignal(this.route.queryParamMap.pipe(map(parseQuery)), { requireSync: true });
  protected readonly page = signal<Page<AuditEntry> | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal('');
  protected readonly invalidRange = signal(false);
  protected readonly actors = signal<string[]>([]);
  protected readonly expandedId = signal<number | null>(null);
  private readonly reloadCount = signal(0);

  protected readonly hasFilters = computed(() => {
    const query = this.query();
    return !!(query.actor || query.action || query.from || query.to);
  });

  constructor() {
    toObservable(computed(() => ({ query: this.query(), reload: this.reloadCount() })))
      .pipe(
        tap(() => this.loading.set(true)),
        switchMap(({ query }) =>
          query.from && query.to && query.from > query.to
            ? of<LoadResult>({ invalidRange: true })
            : this.audit.list(query, PAGE_SIZE).pipe(
                map((page): LoadResult => ({ page })),
                catchError((error: unknown) => of<LoadResult>({ error: errorMessage(error) })),
              ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((result) => {
        this.loading.set(false);
        this.invalidRange.set('invalidRange' in result);
        if ('page' in result) {
          this.page.set(result.page);
          this.loadError.set('');
          this.expandedId.set(null);
        } else if ('error' in result) {
          this.loadError.set(result.error);
          if (this.page()) this.toast.error(result.error);
        }
      });

    // The user list is a convenience; the page works without it.
    this.audit.actors().pipe(catchError(() => of([]))).subscribe((actors) => this.actors.set(actors));
  }

  protected update(changes: Partial<AuditQuery>): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: toParams({ ...this.query(), ...changes }) });
  }

  protected clearFilters(): void {
    this.update({ actor: '', action: '', from: '', to: '', page: 0 });
  }

  protected reload(): void {
    this.reloadCount.update((count) => count + 1);
  }

  protected toggle(entry: AuditEntry): void {
    this.expandedId.update((id) => (id === entry.id ? null : entry.id));
  }

  protected badge(action: AuditAction): string {
    return ACTION_BADGE[action];
  }

  protected isAuthEvent(action: AuditAction): boolean {
    return isAuthenticationEvent(action);
  }

  protected blockedMinutes(retryAfterSeconds: number | undefined): string {
    return blockedMinutes(retryAfterSeconds);
  }

  protected label(action: AuditAction): string {
    return actionLabel(action);
  }

  protected field(name: string): string {
    return fieldLabel(name);
  }

  protected value(field: string, value: string | null): string {
    return displayValue(field, value);
  }
}
