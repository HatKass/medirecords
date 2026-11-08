import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/problem';
import { ToastService } from '../../core/toast/toast.service';
import { ImportStatusBadge } from './import-status-badge';
import { ImportRowError, PatientImport, importDuration, isFinished } from './import.models';
import { ImportsService } from './imports.service';

const ERROR_PREVIEW_SIZE = 10;

/** One import: live progress while it runs, then the outcome and the first rejected rows. */
@Component({
  selector: 'mr-import-detail-page',
  imports: [RouterLink, DatePipe, DecimalPipe, ImportStatusBadge],
  template: `
    <a routerLink="/imports" class="text-sm font-medium text-slate-500 hover:text-slate-800">← Import history</a>

    @if (loadError() && !item()) {
      <div class="mt-6 rounded-2xl border border-slate-200 bg-white px-6 py-16 text-center shadow-sm">
        <p class="font-medium text-slate-900">Could not load this import</p>
        <p class="mt-1 text-sm text-slate-500">{{ loadError() }}</p>
      </div>
    } @else if (item(); as current) {
      <div class="mt-3 flex flex-wrap items-start justify-between gap-4">
        <div class="min-w-0">
          <h1 class="flex flex-wrap items-center gap-3 text-2xl font-semibold tracking-tight">
            <span class="max-w-full truncate">{{ current.fileName }}</span>
            <mr-import-status-badge [status]="current.status" />
          </h1>
          <p class="mt-1 text-sm text-slate-500">
            Import #{{ current.id }}@if (current.jobExecutionId) { · job execution {{ current.jobExecutionId }} }
            · by {{ current.importedBy }} · {{ current.createdAt | date: 'medium' }}
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          @if (finished() && current.invalidRows > 0) {
            <button type="button" (click)="download(current)" [disabled]="downloading()"
                    class="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50">
              <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 4v12M7 11l5 5 5-5M4 20h16" /></svg>
              {{ downloading() ? 'Preparing…' : 'Download error report' }}
            </button>
          }
          @if (finished() && isAdmin()) {
            <a routerLink="/import" class="rounded-lg bg-teal-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-teal-700">Import another file</a>
          }
        </div>
      </div>

      <!-- Progress -->
      <section class="mt-6 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 text-sm">
          <p class="font-medium text-slate-900">
            @switch (current.status) {
              @case ('QUEUED') { Waiting for a free worker… }
              @case ('RUNNING') { Processing rows… }
              @case ('COMPLETED') { Finished{{ duration() ? ' in ' + duration() : '' }} }
              @case ('FAILED') { Stopped }
            }
          </p>
          <p class="tabular-nums text-slate-500">
            {{ current.totalRows | number }} / {{ current.expectedRows | number }} rows · {{ current.progressPercent }}%
          </p>
        </div>
        <div class="mt-3 h-2.5 overflow-hidden rounded-full bg-slate-100" role="progressbar" aria-label="Import progress"
             aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="current.progressPercent">
          <div class="h-full rounded-full transition-[width] duration-500"
               [class]="current.status === 'FAILED' ? 'bg-red-500' : current.status === 'COMPLETED' ? 'bg-emerald-500' : 'bg-teal-500'"
               [style.width.%]="current.progressPercent"></div>
        </div>

        <dl class="mt-6 grid grid-cols-3 gap-2 sm:gap-4">
          <div class="rounded-xl bg-slate-50 p-3 sm:p-4">
            <dt class="text-[0.6875rem] font-semibold uppercase tracking-wide text-slate-500 sm:text-xs"><span class="hidden sm:inline">Rows </span>processed</dt>
            <dd class="mt-1 text-xl font-semibold tabular-nums sm:text-2xl">{{ current.totalRows | number }}</dd>
          </div>
          <div class="rounded-xl bg-emerald-50 p-3 sm:p-4">
            <dt class="text-[0.6875rem] sm:text-xs font-semibold uppercase tracking-wide text-emerald-700">Imported</dt>
            <dd class="mt-1 text-xl font-semibold tabular-nums sm:text-2xl text-emerald-900">{{ current.validRows | number }}</dd>
          </div>
          <div class="rounded-xl p-3 sm:p-4" [class]="current.invalidRows > 0 ? 'bg-amber-50' : 'bg-slate-50'">
            <dt class="text-[0.6875rem] sm:text-xs font-semibold uppercase tracking-wide" [class]="current.invalidRows > 0 ? 'text-amber-700' : 'text-slate-500'">Rejected</dt>
            <dd class="mt-1 text-xl font-semibold tabular-nums sm:text-2xl" [class.text-amber-900]="current.invalidRows > 0">{{ current.invalidRows | number }}</dd>
          </div>
        </dl>

        @if (current.failureMessage) {
          <p class="mt-6 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{{ current.failureMessage }}</p>
        }
        @if (current.status === 'COMPLETED') {
          <p class="mt-6 text-sm text-slate-600">
            {{ current.validRows | number }} {{ current.validRows === 1 ? 'patient was' : 'patients were' }} added.
            <a routerLink="/patients" [queryParams]="{ sort: 'updatedAt', dir: 'desc' }" class="font-medium text-teal-700 hover:underline">View newest patients →</a>
          </p>
        }
      </section>

      <!-- First rejected rows -->
      @if (finished() && current.invalidRows > 0) {
        <section class="mt-6 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
          <div class="flex flex-wrap items-baseline justify-between gap-2 border-b border-slate-200 px-4 py-4 sm:px-6">
            <h2 class="font-semibold text-slate-900">Rejected rows</h2>
            <p class="text-sm text-slate-500">
              Showing the first {{ errors().length }} of {{ current.invalidRows | number }}. The report contains all of them and can be fixed and re-imported.
            </p>
          </div>
          <div class="relative overflow-x-auto">
            <table class="min-w-full divide-y divide-slate-200 text-sm">
              <thead class="bg-slate-50 text-left text-xs font-semibold uppercase tracking-wide text-slate-500">
                <tr>
                  <th scope="col" class="px-4 py-3 sm:px-6">Row</th>
                  <th scope="col" class="px-4 py-3 sm:px-6">Problem</th>
                  <th scope="col" class="hidden px-6 py-3 md:table-cell">Original line</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (error of errors(); track error.rowNumber + error.reason) {
                  <tr>
                    <td class="whitespace-nowrap px-4 py-3 align-top tabular-nums text-slate-500 sm:px-6">{{ error.rowNumber }}</td>
                    <td class="px-4 py-3 text-slate-900 sm:px-6">
                      {{ error.reason }}
                      <span class="mt-1 block break-all font-mono text-xs text-slate-500 md:hidden">{{ error.rawLine }}</span>
                    </td>
                    <td class="hidden max-w-md truncate px-6 py-3 font-mono text-xs text-slate-500 md:table-cell" [title]="error.rawLine">{{ error.rawLine }}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="3" class="px-6 py-8 text-center text-slate-500">Loading rejected rows…</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
    } @else {
      <div class="mt-6 h-64 animate-pulse rounded-2xl bg-slate-200/60" aria-label="Loading"></div>
    }
  `,
})
export class ImportDetailPage implements OnInit {
  private readonly imports = inject(ImportsService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  /** Route parameter (bound via withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly isAdmin = inject(AuthService).isAdmin;
  protected readonly item = signal<PatientImport | null>(null);
  protected readonly errors = signal<ImportRowError[]>([]);
  protected readonly loadError = signal('');
  protected readonly downloading = signal(false);

  protected readonly finished = computed(() => {
    const current = this.item();
    return !!current && isFinished(current.status);
  });
  protected readonly duration = computed(() => {
    const current = this.item();
    return current ? importDuration(current) : null;
  });

  ngOnInit(): void {
    const id = Number(this.id());
    this.imports
      .poll(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (current) => {
          const wasRunning = this.item() && !isFinished(this.item()!.status);
          this.item.set(current);
          if (isFinished(current.status)) {
            if (current.invalidRows > 0) this.loadErrors(id);
            if (wasRunning) this.announce(current);
          }
        },
        error: (error: unknown) => {
          this.loadError.set(errorMessage(error, 'Could not load this import.'));
          if (this.item()) this.toast.error('Lost contact with the server; progress is no longer updating.');
        },
      });
  }

  protected download(current: PatientImport): void {
    this.downloading.set(true);
    this.imports.downloadErrorReport(current).subscribe({
      next: () => this.downloading.set(false),
      error: (error: unknown) => {
        this.downloading.set(false);
        this.toast.error(errorMessage(error, 'Could not download the error report.'));
      },
    });
  }

  private loadErrors(id: number): void {
    this.imports
      .errors(id, 0, ERROR_PREVIEW_SIZE)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.errors.set(page.content),
        error: (error: unknown) => this.toast.error(errorMessage(error, 'Could not load the rejected rows.')),
      });
  }

  /** Toast only when the user watched the import finish, not when opening an old one. */
  private announce(current: PatientImport): void {
    if (current.status === 'FAILED') {
      this.toast.error(`Import of ${current.fileName} failed.`);
    } else if (current.invalidRows > 0) {
      this.toast.info(`Imported ${current.validRows} rows; ${current.invalidRows} were rejected.`);
    } else {
      this.toast.success(`Imported all ${current.validRows} rows.`);
    }
  }
}
