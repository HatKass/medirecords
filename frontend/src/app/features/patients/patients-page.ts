import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, ParamMap, Params, Router } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap, tap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/problem';
import { ToastService } from '../../core/toast/toast.service';
import { ConfirmDialog } from '../../shared/confirm-dialog';
import { PatientFormPanel } from './patient-form-panel';
import {
  BLOOD_TYPES,
  BloodType,
  Page,
  Patient,
  PatientQuery,
  SortField,
  ageFrom,
  bloodTypeLabel,
  genderLabel,
} from './patient.models';
import { PatientsService } from './patients.service';

const PAGE_SIZES = [10, 20, 50, 100];
const SORT_FIELDS: SortField[] = ['lastName', 'dateOfBirth', 'city', 'bloodType', 'updatedAt'];
const DEFAULT_QUERY: PatientQuery = {
  page: 0, size: 20, sort: 'lastName', direction: 'asc', name: '', city: '', bloodType: '',
};

/** The list state lives in the URL (?page=2&city=Leeds...), so reload, back and shared links all work. */
function parseQuery(params: ParamMap): PatientQuery {
  const page = Number(params.get('page'));
  const size = Number(params.get('size'));
  const sort = params.get('sort') as SortField;
  const bloodType = params.get('bloodType') as BloodType;
  return {
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: PAGE_SIZES.includes(size) ? size : DEFAULT_QUERY.size,
    sort: SORT_FIELDS.includes(sort) ? sort : DEFAULT_QUERY.sort,
    direction: params.get('dir') === 'desc' ? 'desc' : 'asc',
    name: params.get('name') ?? '',
    city: params.get('city') ?? '',
    bloodType: BLOOD_TYPES.some((type) => type.value === bloodType) ? bloodType : '',
  };
}

/** Only non-default values go into the URL, to keep it short. */
function toParams(query: PatientQuery): Params {
  return {
    page: query.page || null,
    size: query.size === DEFAULT_QUERY.size ? null : query.size,
    sort: query.sort === DEFAULT_QUERY.sort ? null : query.sort,
    dir: query.direction === 'asc' ? null : query.direction,
    name: query.name.trim() || null,
    city: query.city || null,
    bloodType: query.bloodType || null,
  };
}

type LoadResult = { page: Page<Patient> } | { error: string };

interface PanelState {
  patient: Patient | null;
}

@Component({
  selector: 'mr-patients-page',
  imports: [ReactiveFormsModule, DatePipe, PatientFormPanel, ConfirmDialog],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="text-2xl font-semibold tracking-tight">Patients</h1>
        <p class="mt-1 text-sm text-slate-500">
          @if (page(); as current) {
            {{ current.totalElements }} {{ current.totalElements === 1 ? 'patient' : 'patients' }}{{ hasFilters() ? ' match your filters' : ' on record' }}
          } @else {
            Loading records…
          }
        </p>
      </div>
      @if (isAdmin()) {
        <button type="button" (click)="openCreate()"
                class="inline-flex items-center gap-2 rounded-lg bg-teal-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-teal-700">
          <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          Add patient
        </button>
      }
    </div>

    <!-- Filters -->
    <div class="mt-6 grid gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:grid-cols-2 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)_auto]">
      <label class="relative block">
        <span class="sr-only">Search by name</span>
        <svg class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
          <circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" />
        </svg>
        <input type="search" [formControl]="searchControl" placeholder="Search by name…" class="filter-input pl-9" />
      </label>
      <label class="block">
        <span class="sr-only">City</span>
        <select class="filter-input" [value]="query().city" (change)="update({ city: $any($event.target).value, page: 0 })">
          <option value="">All cities</option>
          @for (city of cities(); track city) { <option [value]="city" [selected]="city === query().city">{{ city }}</option> }
        </select>
      </label>
      <label class="block">
        <span class="sr-only">Blood type</span>
        <select class="filter-input" [value]="query().bloodType" (change)="update({ bloodType: $any($event.target).value, page: 0 })">
          <option value="">All blood types</option>
          @for (type of bloodTypes; track type.value) { <option [value]="type.value" [selected]="type.value === query().bloodType">{{ type.label }}</option> }
        </select>
      </label>
      <button type="button" (click)="clearFilters()" [disabled]="!hasFilters()"
              class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40">
        Clear filters
      </button>
    </div>

    <!-- Results -->
    <div class="mt-4 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
      @if (loadError() && !page()) {
        <div class="px-6 py-16 text-center">
          <p class="font-medium text-slate-900">Could not load patients</p>
          <p class="mt-1 text-sm text-slate-500">{{ loadError() }}</p>
          <button type="button" (click)="reload()" class="mt-4 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700">Try again</button>
        </div>
      } @else {
        <div class="overflow-x-auto" [class.opacity-60]="loading() && page()" [attr.aria-busy]="loading()">
          <table class="min-w-full divide-y divide-slate-200 text-sm">
            <thead class="bg-slate-50 text-left text-xs font-semibold uppercase tracking-wide text-slate-500">
              <tr>
                <th scope="col" class="px-4 py-3" [attr.aria-sort]="ariaSort('lastName')">
                  <button type="button" class="sort-button" (click)="sortBy('lastName')">Name <span aria-hidden="true">{{ sortIcon('lastName') }}</span></button>
                </th>
                <th scope="col" class="px-4 py-3" [attr.aria-sort]="ariaSort('dateOfBirth')">
                  <button type="button" class="sort-button" (click)="sortBy('dateOfBirth')">Born <span aria-hidden="true">{{ sortIcon('dateOfBirth') }}</span></button>
                </th>
                <th scope="col" class="hidden px-4 py-3 md:table-cell">Gender</th>
                <th scope="col" class="px-4 py-3" [attr.aria-sort]="ariaSort('city')">
                  <button type="button" class="sort-button" (click)="sortBy('city')">City <span aria-hidden="true">{{ sortIcon('city') }}</span></button>
                </th>
                <th scope="col" class="px-4 py-3" [attr.aria-sort]="ariaSort('bloodType')">
                  <button type="button" class="sort-button" (click)="sortBy('bloodType')">Blood <span aria-hidden="true">{{ sortIcon('bloodType') }}</span></button>
                </th>
                <th scope="col" class="hidden px-4 py-3 lg:table-cell">Phone</th>
                <th scope="col" class="hidden px-4 py-3 lg:table-cell" [attr.aria-sort]="ariaSort('updatedAt')">
                  <button type="button" class="sort-button" (click)="sortBy('updatedAt')">Updated <span aria-hidden="true">{{ sortIcon('updatedAt') }}</span></button>
                </th>
                @if (isAdmin()) { <th scope="col" class="px-4 py-3"><span class="sr-only">Actions</span></th> }
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @if (!page()) {
                @for (row of skeletonRows; track row) {
                  <tr>
                    @for (cell of skeletonCells(); track cell) {
                      <td class="px-4 py-4"><div class="h-3.5 animate-pulse rounded bg-slate-200" [style.width.%]="60 + ((row * 7 + cell * 13) % 35)"></div></td>
                    }
                  </tr>
                }
              } @else {
                @for (patient of page()!.content; track patient.id) {
                  <tr class="cursor-pointer hover:bg-slate-50" (click)="openPatient(patient)">
                    <td class="px-4 py-3">
                      <p class="font-medium text-slate-900">{{ patient.lastName }}, {{ patient.firstName }}</p>
                      <p class="text-xs text-slate-500">{{ patient.email }}</p>
                    </td>
                    <td class="whitespace-nowrap px-4 py-3">
                      {{ patient.dateOfBirth | date: 'mediumDate' }}
                      <span class="block text-xs text-slate-500">{{ age(patient.dateOfBirth) }} yrs</span>
                    </td>
                    <td class="hidden px-4 py-3 md:table-cell">{{ gender(patient.gender) }}</td>
                    <td class="px-4 py-3">{{ patient.city }}</td>
                    <td class="px-4 py-3">
                      @if (patient.bloodType) {
                        <span class="rounded-full bg-rose-50 px-2 py-0.5 text-xs font-semibold text-rose-700">{{ bloodType(patient.bloodType) }}</span>
                      } @else {
                        <span class="text-xs text-slate-400">Unknown</span>
                      }
                    </td>
                    <td class="hidden whitespace-nowrap px-4 py-3 text-slate-600 lg:table-cell">{{ patient.phone ?? '—' }}</td>
                    <td class="hidden whitespace-nowrap px-4 py-3 text-slate-500 lg:table-cell">{{ patient.updatedAt | date: 'mediumDate' }}</td>
                    @if (isAdmin()) {
                      <td class="whitespace-nowrap px-4 py-3 text-right">
                        <button type="button" class="row-action" (click)="$event.stopPropagation(); openPatient(patient)" [attr.aria-label]="'Edit ' + patient.firstName + ' ' + patient.lastName">Edit</button>
                        <button type="button" class="row-action text-red-600 hover:bg-red-50" (click)="$event.stopPropagation(); deleting.set(patient)" [attr.aria-label]="'Delete ' + patient.firstName + ' ' + patient.lastName">Delete</button>
                      </td>
                    }
                  </tr>
                } @empty {
                  <tr>
                    <td [attr.colspan]="isAdmin() ? 8 : 7" class="px-6 py-16 text-center">
                      <p class="font-medium text-slate-900">{{ hasFilters() ? 'No patients match these filters' : 'No patients yet' }}</p>
                      <p class="mt-1 text-sm text-slate-500">{{ hasFilters() ? 'Try a different name, city or blood type.' : 'Add a patient or import a CSV file to get started.' }}</p>
                      @if (hasFilters()) {
                        <button type="button" (click)="clearFilters()" class="mt-4 text-sm font-medium text-teal-700 hover:underline">Clear filters</button>
                      }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>

        @if (page(); as current) {
          @if (current.totalElements > 0) {
            <div class="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 px-4 py-3 text-sm text-slate-600">
              <p>Showing <strong>{{ rangeStart() }}–{{ rangeEnd() }}</strong> of <strong>{{ current.totalElements }}</strong></p>
              <div class="flex items-center gap-3">
                <label class="flex items-center gap-2">
                  <span class="hidden sm:inline">Rows</span>
                  <select class="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm" (change)="update({ size: +$any($event.target).value, page: 0 })">
                    @for (size of pageSizes; track size) { <option [value]="size" [selected]="size === query().size">{{ size }}</option> }
                  </select>
                </label>
                <span class="text-slate-500">Page {{ current.page + 1 }} of {{ current.totalPages }}</span>
                <div class="flex gap-1">
                  <button type="button" class="pager-button" [disabled]="current.page === 0 || loading()" (click)="update({ page: current.page - 1 })" aria-label="Previous page">‹</button>
                  <button type="button" class="pager-button" [disabled]="current.page + 1 >= current.totalPages || loading()" (click)="update({ page: current.page + 1 })" aria-label="Next page">›</button>
                </div>
              </div>
            </div>
          }
        }
      }
    </div>

    @if (panel(); as state) {
      <mr-patient-form-panel [patient]="state.patient" [readOnly]="!isAdmin()" [cities]="cities()"
                             (saved)="onSaved()" (closed)="panel.set(null)" />
    }

    @if (deleting(); as patient) {
      <mr-confirm-dialog title="Delete patient?" confirmLabel="Delete patient" [busy]="deleteBusy()"
                         [message]="'This permanently removes ' + patient.firstName + ' ' + patient.lastName + ' and cannot be undone.'"
                         (confirmed)="confirmDelete(patient)" (cancelled)="deleting.set(null)" />
    }
  `,
  styles: `
    .filter-input {
      display: block; width: 100%; border-radius: 0.5rem; border: 1px solid var(--color-slate-300); background: white;
      padding: 0.5rem 0.75rem; font-size: 0.875rem; outline: none;
    }
    .filter-input:focus { border-color: var(--color-teal-600); }
    .filter-input.pl-9 { padding-left: 2.25rem; }
    .sort-button { display: inline-flex; gap: 0.25rem; text-transform: inherit; letter-spacing: inherit; font-weight: inherit; }
    .sort-button:hover { color: var(--color-slate-900); }
    .row-action { border-radius: 0.375rem; padding: 0.25rem 0.5rem; font-size: 0.8125rem; font-weight: 500; color: var(--color-teal-700); }
    .row-action:hover { background: var(--color-teal-50); }
    .pager-button { border-radius: 0.375rem; border: 1px solid var(--color-slate-300); padding: 0.125rem 0.625rem; font-size: 1rem; }
    .pager-button:hover:not(:disabled) { background: var(--color-slate-50); }
    .pager-button:disabled { opacity: 0.4; cursor: not-allowed; }
  `,
})
export class PatientsPage {
  private readonly patients = inject(PatientsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);

  protected readonly isAdmin = inject(AuthService).isAdmin;
  protected readonly bloodTypes = BLOOD_TYPES;
  protected readonly pageSizes = PAGE_SIZES;
  protected readonly skeletonRows = [0, 1, 2, 3, 4, 5];

  protected readonly query = toSignal(this.route.queryParamMap.pipe(map(parseQuery)), { requireSync: true });
  protected readonly page = signal<Page<Patient> | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal('');
  protected readonly cities = signal<string[]>([]);
  protected readonly panel = signal<PanelState | null>(null);
  protected readonly deleting = signal<Patient | null>(null);
  protected readonly deleteBusy = signal(false);
  protected readonly searchControl = new FormControl(this.query().name, { nonNullable: true });

  private readonly reloadCount = signal(0);

  protected readonly hasFilters = computed(() => {
    const query = this.query();
    return !!(query.name.trim() || query.city || query.bloodType);
  });
  protected readonly rangeStart = computed(() => {
    const page = this.page();
    return page ? page.page * page.size + 1 : 0;
  });
  protected readonly rangeEnd = computed(() => {
    const page = this.page();
    return page ? page.page * page.size + page.content.length : 0;
  });
  protected readonly skeletonCells = computed(() => (this.isAdmin() ? [0, 1, 2, 3, 4, 5, 6, 7] : [0, 1, 2, 3, 4, 5, 6]));

  constructor() {
    // Load whenever the URL query changes or a reload is requested; switchMap drops stale responses.
    toObservable(computed(() => ({ query: this.query(), reload: this.reloadCount() })))
      .pipe(
        tap(() => this.loading.set(true)),
        switchMap(({ query }) =>
          this.patients.list(query).pipe(
            map((page): LoadResult => ({ page })),
            catchError((error: unknown) => of<LoadResult>({ error: errorMessage(error) })),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((result) => {
        this.loading.set(false);
        if ('page' in result) {
          this.page.set(result.page);
          this.loadError.set('');
          // A filter or a delete can leave us past the last page: step back to the last one.
          if (result.page.content.length === 0 && result.page.page > 0 && result.page.totalPages > 0) {
            this.update({ page: result.page.totalPages - 1 }, true);
          }
        } else {
          this.loadError.set(result.error);
          if (this.page()) this.toast.error(result.error);
        }
      });

    this.searchControl.valueChanges
      .pipe(debounceTime(300), map((name) => name.trim()), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((name) => this.update({ name, page: 0 }, true));

    // Keep the search box in sync when the URL changes from outside (back button, cleared filters).
    effect(() => {
      const name = this.query().name;
      if (name.trim() !== this.searchControl.value.trim()) this.searchControl.setValue(name, { emitEvent: false });
    });

    this.loadCities();
  }

  protected update(changes: Partial<PatientQuery>, replaceUrl = false): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: toParams({ ...this.query(), ...changes }),
      replaceUrl,
    });
  }

  protected clearFilters(): void {
    this.searchControl.setValue('', { emitEvent: false });
    this.update({ name: '', city: '', bloodType: '', page: 0 });
  }

  protected reload(): void {
    this.reloadCount.update((count) => count + 1);
  }

  protected sortBy(field: SortField): void {
    const { sort, direction } = this.query();
    // Dates feel natural newest-first; text columns start A→Z.
    const firstDirection = field === 'updatedAt' ? 'desc' : 'asc';
    this.update({ sort: field, direction: sort === field ? (direction === 'asc' ? 'desc' : 'asc') : firstDirection, page: 0 });
  }

  protected sortIcon(field: SortField): string {
    const { sort, direction } = this.query();
    return sort !== field ? '' : direction === 'asc' ? '▲' : '▼';
  }

  protected ariaSort(field: SortField): string | null {
    const { sort, direction } = this.query();
    return sort === field ? (direction === 'asc' ? 'ascending' : 'descending') : null;
  }

  protected openCreate(): void {
    this.panel.set({ patient: null });
  }

  protected openPatient(patient: Patient): void {
    this.panel.set({ patient });
  }

  protected onSaved(): void {
    this.panel.set(null);
    this.reload();
    this.loadCities(); // the patient may have introduced a new city
  }

  protected confirmDelete(patient: Patient): void {
    this.deleteBusy.set(true);
    this.patients.delete(patient.id).subscribe({
      next: () => {
        this.deleteBusy.set(false);
        this.deleting.set(null);
        this.toast.success(`Deleted ${patient.firstName} ${patient.lastName}.`);
        this.reload();
      },
      error: (error: unknown) => {
        this.deleteBusy.set(false);
        this.deleting.set(null);
        this.toast.error(errorMessage(error, 'Could not delete the patient.'));
        this.reload();
      },
    });
  }

  protected age(dateOfBirth: string): number {
    return ageFrom(dateOfBirth);
  }

  protected bloodType(value: BloodType | null): string {
    return bloodTypeLabel(value);
  }

  protected gender(value: Patient['gender']): string {
    return genderLabel(value);
  }

  private loadCities(): void {
    this.patients.cities().subscribe({ next: (cities) => this.cities.set(cities), error: () => undefined });
  }
}
