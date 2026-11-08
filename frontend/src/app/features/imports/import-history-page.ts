import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { EMPTY, Observable, catchError, switchMap, tap, timer } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/problem';
import { ToastService } from '../../core/toast/toast.service';
import { Page } from '../patients/patient.models';
import { ImportStatusBadge } from './import-status-badge';
import { PatientImport, importDuration, isFinished } from './import.models';
import { ImportsService } from './imports.service';

const PAGE_SIZE = 20;
/** While any visible import is still running, the table refreshes itself at this interval. */
const LIVE_REFRESH_MS = 3000;

@Component({
  selector: 'mr-import-history-page',
  imports: [RouterLink, DatePipe, DecimalPipe, ImportStatusBadge],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight">Import history</h1>
        <p class="mt-1 text-sm text-slate-500">Every CSV import with its outcome. Download a report to see why rows were rejected.</p>
      </div>
      @if (isAdmin()) {
        <a routerLink="/import" class="inline-flex items-center gap-2 rounded-lg bg-teal-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-teal-700">
          <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          New import
        </a>
      }
    </div>

    <div class="mt-6 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
      @if (loadError() && !page()) {
        <div class="px-6 py-16 text-center">
          <p class="font-medium text-slate-900">Could not load import history</p>
          <p class="mt-1 text-sm text-slate-500">{{ loadError() }}</p>
          <button type="button" (click)="reload()" class="mt-4 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700">Try again</button>
        </div>
      } @else {
        <!-- Phones: one card per import -->
        <ul class="divide-y divide-slate-100 md:hidden">
          @if (!page()) {
            @for (row of [0, 1, 2, 3]; track row) {
              <li class="space-y-2 px-4 py-4" aria-hidden="true">
                <div class="h-4 w-2/3 animate-pulse rounded bg-slate-200"></div>
                <div class="h-3 w-1/2 animate-pulse rounded bg-slate-100"></div>
              </li>
            }
          } @else {
            @for (item of page()!.content; track item.id) {
              <li class="px-4 py-3">
                <button type="button" class="block w-full text-left" (click)="open(item)">
                  <span class="flex items-start justify-between gap-3">
                    <span class="min-w-0">
                      <span class="block truncate font-medium text-slate-900">{{ item.fileName }}</span>
                      <span class="block text-xs text-slate-500">#{{ item.id }} · {{ item.importedBy }} · {{ (item.startedAt ?? item.createdAt) | date: 'MMM d, HH:mm' }}</span>
                    </span>
                    <span class="shrink-0 text-right">
                      <mr-import-status-badge [status]="item.status" />
                      @if (!finished(item)) { <span class="mt-1 block text-xs tabular-nums text-slate-500">{{ item.progressPercent }}%</span> }
                    </span>
                  </span>
                  <span class="mt-2 flex gap-4 text-xs tabular-nums">
                    <span class="text-slate-600">{{ item.totalRows | number }} rows</span>
                    <span class="text-emerald-700">{{ item.validRows | number }} valid</span>
                    <span [class]="item.invalidRows > 0 ? 'text-amber-700' : 'text-slate-400'">{{ item.invalidRows | number }} invalid</span>
                  </span>
                </button>
                @if (finished(item) && item.invalidRows > 0) {
                  <button type="button" (click)="download(item)" [disabled]="downloadingId() === item.id"
                          class="mt-2 -ml-2 rounded-md px-2 py-1 text-[0.8125rem] font-medium text-teal-700 hover:bg-teal-50 disabled:opacity-50"
                          [attr.aria-label]="'Download error report for ' + item.fileName">
                    {{ downloadingId() === item.id ? 'Preparing…' : 'Download error report' }}
                  </button>
                }
              </li>
            } @empty {
              <li class="px-6 py-14 text-center">
                <p class="font-medium text-slate-900">No imports yet</p>
                <p class="mt-1 text-sm text-slate-500">
                  {{ isAdmin() ? 'Upload a CSV file to add patients in bulk.' : 'Imports run by administrators will appear here.' }}
                </p>
                @if (isAdmin()) {
                  <a routerLink="/import" class="mt-4 inline-block text-sm font-medium text-teal-700 hover:underline">Import a CSV file</a>
                }
              </li>
            }
          }
        </ul>

        <!-- Tablets and up: table. "relative" keeps the sr-only header label inside the scroll box. -->
        <div class="relative hidden overflow-x-auto md:block">
          <table class="min-w-full divide-y divide-slate-200 text-sm">
            <thead class="bg-slate-50 text-left text-xs font-semibold uppercase tracking-wide text-slate-500">
              <tr>
                <th scope="col" class="px-4 py-3">Import</th>
                <th scope="col" class="px-4 py-3">Status</th>
                <th scope="col" class="px-4 py-3 text-right">Total</th>
                <th scope="col" class="px-4 py-3 text-right">Valid</th>
                <th scope="col" class="px-4 py-3 text-right">Invalid</th>
                <th scope="col" class="hidden px-4 py-3 md:table-cell">Started</th>
                <th scope="col" class="hidden px-4 py-3 lg:table-cell">Finished</th>
                <th scope="col" class="px-4 py-3"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @if (!page()) {
                @for (row of [0, 1, 2, 3]; track row) {
                  <tr>
                    @for (cell of [0, 1, 2, 3, 4, 5, 6, 7]; track cell) {
                      <td class="px-4 py-4"><div class="h-3.5 animate-pulse rounded bg-slate-200"></div></td>
                    }
                  </tr>
                }
              } @else {
                @for (item of page()!.content; track item.id) {
                  <tr class="cursor-pointer hover:bg-slate-50" (click)="open(item)">
                    <td class="px-4 py-3">
                      <p class="max-w-[16rem] truncate font-medium text-slate-900" [title]="item.fileName">{{ item.fileName }}</p>
                      <p class="text-xs text-slate-500">
                        #{{ item.id }}@if (item.jobExecutionId) { · job {{ item.jobExecutionId }} } · {{ item.importedBy }}
                      </p>
                    </td>
                    <td class="whitespace-nowrap px-4 py-3">
                      <mr-import-status-badge [status]="item.status" />
                      @if (!finished(item)) { <span class="ml-2 text-xs tabular-nums text-slate-500">{{ item.progressPercent }}%</span> }
                    </td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ item.totalRows | number }}</td>
                    <td class="px-4 py-3 text-right tabular-nums text-emerald-700">{{ item.validRows | number }}</td>
                    <td class="px-4 py-3 text-right tabular-nums" [class]="item.invalidRows > 0 ? 'text-amber-700' : 'text-slate-400'">{{ item.invalidRows | number }}</td>
                    <td class="hidden whitespace-nowrap px-4 py-3 text-slate-600 md:table-cell">{{ (item.startedAt ?? item.createdAt) | date: 'MMM d, y, HH:mm:ss' }}</td>
                    <td class="hidden whitespace-nowrap px-4 py-3 text-slate-600 lg:table-cell">
                      @if (item.finishedAt) {
                        {{ item.finishedAt | date: 'HH:mm:ss' }} <span class="text-xs text-slate-400">({{ duration(item) }})</span>
                      } @else { — }
                    </td>
                    <td class="whitespace-nowrap px-4 py-3 text-right">
                      @if (finished(item) && item.invalidRows > 0) {
                        <button type="button" (click)="$event.stopPropagation(); download(item)" [disabled]="downloadingId() === item.id"
                                class="rounded-md px-2 py-1 text-[0.8125rem] font-medium text-teal-700 hover:bg-teal-50 disabled:opacity-50"
                                [attr.aria-label]="'Download error report for ' + item.fileName">
                          {{ downloadingId() === item.id ? 'Preparing…' : 'Error report' }}
                        </button>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="8" class="px-6 py-16 text-center">
                      <p class="font-medium text-slate-900">No imports yet</p>
                      <p class="mt-1 text-sm text-slate-500">
                        {{ isAdmin() ? 'Upload a CSV file to add patients in bulk.' : 'Imports run by administrators will appear here.' }}
                      </p>
                      @if (isAdmin()) {
                        <a routerLink="/import" class="mt-4 inline-block text-sm font-medium text-teal-700 hover:underline">Import a CSV file</a>
                      }
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
              <p>{{ current.totalElements | number }} imports</p>
              <div class="flex items-center gap-3">
                <span class="text-slate-500">Page {{ current.page + 1 }} of {{ current.totalPages }}</span>
                <button type="button" class="pager-button" [disabled]="current.page === 0" (click)="pageIndex.set(current.page - 1)" aria-label="Previous page">‹</button>
                <button type="button" class="pager-button" [disabled]="current.page + 1 >= current.totalPages" (click)="pageIndex.set(current.page + 1)" aria-label="Next page">›</button>
              </div>
            </div>
          }
        }
      }
    </div>
  `,
  styles: `
    .pager-button { border-radius: 0.375rem; border: 1px solid var(--color-slate-300); padding: 0.25rem 0.75rem; font-size: 1rem; }
    .pager-button:hover:not(:disabled) { background: var(--color-slate-50); }
    .pager-button:disabled { opacity: 0.4; cursor: not-allowed; }
  `,
})
export class ImportHistoryPage {
  private readonly imports = inject(ImportsService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly isAdmin = inject(AuthService).isAdmin;
  protected readonly pageIndex = signal(0);
  protected readonly page = signal<Page<PatientImport> | null>(null);
  protected readonly loadError = signal('');
  protected readonly downloadingId = signal<number | null>(null);
  private readonly reloadCount = signal(0);

  constructor() {
    // Load on page change or retry; while something is still running, keep refreshing.
    toObservable(computed(() => ({ page: this.pageIndex(), reload: this.reloadCount() })))
      .pipe(
        switchMap(({ page }) => this.load(page)),
        takeUntilDestroyed(),
      )
      .subscribe();
  }

  protected reload(): void {
    this.reloadCount.update((count) => count + 1);
  }

  protected open(item: PatientImport): void {
    void this.router.navigate(['/imports', item.id]);
  }

  protected finished(item: PatientImport): boolean {
    return isFinished(item.status);
  }

  protected duration(item: PatientImport): string | null {
    return importDuration(item);
  }

  protected download(item: PatientImport): void {
    this.downloadingId.set(item.id);
    this.imports.downloadErrorReport(item).subscribe({
      next: () => this.downloadingId.set(null),
      error: (error: unknown) => {
        this.downloadingId.set(null);
        this.toast.error(errorMessage(error, 'Could not download the error report.'));
      },
    });
  }

  /** Fetches the page; if any import on it is unfinished, fetches it again after a short delay. */
  private load(page: number): Observable<unknown> {
    return this.imports.list(page, PAGE_SIZE).pipe(
      tap((result) => {
        this.page.set(result);
        this.loadError.set('');
      }),
      switchMap((result) =>
        result.content.some((item) => !isFinished(item.status))
          ? timer(LIVE_REFRESH_MS).pipe(switchMap(() => this.load(page)))
          : EMPTY,
      ),
      catchError((error: unknown) => {
        this.loadError.set(errorMessage(error));
        if (this.page()) this.toast.error(errorMessage(error));
        return EMPTY;
      }),
    );
  }
}
