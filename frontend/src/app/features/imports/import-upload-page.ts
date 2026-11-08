import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/http/problem';
import { ToastService } from '../../core/toast/toast.service';
import { OPTIONAL_COLUMNS, REQUIRED_COLUMNS, TEMPLATE_CSV } from './import.models';
import { ImportsService } from './imports.service';

/** Matches the backend's multipart limit (UPLOAD_MAX_FILE_SIZE, 20 MB by default). */
const MAX_FILE_BYTES = 20 * 1024 * 1024;

/** ADMIN: pick or drop a CSV and start an import; then the detail page shows its live progress. */
@Component({
  selector: 'mr-import-upload-page',
  imports: [RouterLink],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight">Import patients</h1>
        <p class="mt-1 text-sm text-slate-500">Upload a CSV file. Valid rows are added; invalid rows are skipped and listed in an error report.</p>
      </div>
      <a routerLink="/imports" class="text-sm font-medium text-teal-700 hover:underline">View import history →</a>
    </div>

    <div class="mt-6 grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <section class="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        <label
          class="flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed px-4 py-8 text-center sm:px-6 sm:py-12 transition-colors"
          [class]="dragging() ? 'border-teal-500 bg-teal-50' : 'border-slate-300 hover:border-slate-400 hover:bg-slate-50'"
          (dragover)="$event.preventDefault(); dragging.set(true)"
          (dragleave)="dragging.set(false)"
          (drop)="onDrop($event)"
        >
          <svg class="h-10 w-10 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
          </svg>
          <span class="mt-3 text-sm font-medium text-slate-900">Drop a CSV file here, or <span class="text-teal-700 underline">browse</span></span>
          <span class="mt-1 text-xs text-slate-500">UTF-8 CSV, up to 20 MB</span>
          <input type="file" accept=".csv,text/csv" class="sr-only" (change)="onPick($event)" [disabled]="uploading()" />
        </label>

        @if (file(); as selected) {
          <div class="mt-4 flex items-center justify-between gap-4 rounded-xl border border-slate-200 px-4 py-3">
            <div class="min-w-0">
              <p class="truncate text-sm font-medium text-slate-900">{{ selected.name }}</p>
              <p class="text-xs text-slate-500">{{ formatSize(selected.size) }}</p>
            </div>
            <button type="button" (click)="file.set(null)" [disabled]="uploading()" class="text-sm text-slate-500 hover:text-slate-800 disabled:opacity-40">Remove</button>
          </div>
        }

        @if (fileError()) {
          <p class="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{{ fileError() }}</p>
        }

        <div class="mt-6 flex justify-end">
          <button type="button" (click)="start()" [disabled]="!file() || uploading()"
                  class="inline-flex w-full items-center justify-center gap-2 rounded-lg bg-teal-600 px-5 py-2.5 sm:w-auto text-sm font-semibold text-white shadow-sm hover:bg-teal-700 disabled:cursor-not-allowed disabled:opacity-50">
            @if (uploading()) {
              <span class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" aria-hidden="true"></span>
              Uploading…
            } @else {
              Start import
            }
          </button>
        </div>
      </section>

      <aside class="rounded-2xl border border-slate-200 bg-white p-4 text-sm sm:p-6 text-slate-600 shadow-sm">
        <h2 class="font-semibold text-slate-900">File format</h2>
        <p class="mt-2">The first line is a header. Columns can be in any order; common spellings such as “First Name”, “DOB” or “Sex” are recognized.</p>
        <dl class="mt-4 space-y-3">
          <div>
            <dt class="text-xs font-semibold uppercase tracking-wide text-slate-500">Required</dt>
            <dd class="mt-1 flex flex-wrap gap-1.5">
              @for (column of required; track column) { <code class="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-800">{{ column }}</code> }
            </dd>
          </div>
          <div>
            <dt class="text-xs font-semibold uppercase tracking-wide text-slate-500">Optional</dt>
            <dd class="mt-1 flex flex-wrap gap-1.5">
              @for (column of optional; track column) { <code class="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-800">{{ column }}</code> }
            </dd>
          </div>
        </dl>
        <ul class="mt-4 list-disc space-y-1 pl-5">
          <li>Dates: <code class="text-xs">1980-12-31</code>, <code class="text-xs">31/12/1980</code> (day first), <code class="text-xs">31 Dec 1980</code>…</li>
          <li>Gender: F, M or O (or Female, Male, Other).</li>
          <li>Blood type: A+, B−, AB+, O− … or empty.</li>
          <li>Whitespace is trimmed, emails lower-cased, phone numbers normalized.</li>
          <li>Rows whose email already exists, in the file or in the database, are rejected.</li>
        </ul>
        <button type="button" (click)="downloadTemplate()" class="mt-5 text-sm font-medium text-teal-700 hover:underline">Download a template CSV</button>
      </aside>
    </div>
  `,
})
export class ImportUploadPage {
  private readonly imports = inject(ImportsService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly required = REQUIRED_COLUMNS;
  protected readonly optional = OPTIONAL_COLUMNS;
  protected readonly file = signal<File | null>(null);
  protected readonly fileError = signal('');
  protected readonly dragging = signal(false);
  protected readonly uploading = signal(false);

  protected onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.select(input.files?.[0] ?? null);
    input.value = ''; // picking the same file again should still fire (change)
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.select(event.dataTransfer?.files[0] ?? null);
  }

  protected start(): void {
    const selected = this.file();
    if (!selected) return;
    this.uploading.set(true);
    this.fileError.set('');
    this.imports.upload(selected).subscribe({
      next: (started) => {
        this.toast.success(`Import of ${started.fileName} started.`);
        void this.router.navigate(['/imports', started.id]);
      },
      error: (error: unknown) => {
        this.uploading.set(false);
        this.fileError.set(errorMessage(error, 'The upload failed. Please try again.'));
      },
    });
  }

  protected downloadTemplate(): void {
    const url = URL.createObjectURL(new Blob([TEMPLATE_CSV], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'patients-template.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  protected formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  private select(candidate: File | null): void {
    this.fileError.set('');
    if (!candidate) return;
    if (!candidate.name.toLowerCase().endsWith('.csv')) {
      this.file.set(null);
      this.fileError.set('Only .csv files can be imported.');
    } else if (candidate.size > MAX_FILE_BYTES) {
      this.file.set(null);
      this.fileError.set(`The file is ${this.formatSize(candidate.size)}; the limit is 20 MB.`);
    } else {
      this.file.set(candidate);
    }
  }
}
