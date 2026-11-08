import { DatePipe } from '@angular/common';
import { Component, ElementRef, OnInit, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { problemOf, errorMessage } from '../../core/http/problem';
import { ToastService } from '../../core/toast/toast.service';
import { BLOOD_TYPES, BloodType, GENDERS, Gender, Patient, PatientRequest } from './patient.models';
import { PatientsService } from './patients.service';

// Same rules as the API (PatientDtos.PatientRequest), so most mistakes are caught before a round trip.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_PATTERN = /^\+?[0-9 ().-]{7,20}$/;

function inThePast(control: AbstractControl<string>): ValidationErrors | null {
  if (!control.value) return null;
  const today = new Date().toISOString().slice(0, 10);
  return control.value < today ? null : { past: true };
}

const PATTERN_MESSAGES: Record<string, string> = {
  email: 'Enter a valid email address, e.g. name@example.com.',
  phone: 'Use digits, spaces and + ( ) - only, 7 to 20 characters.',
};

/**
 * Slide-over panel to create, edit or (for read-only users) view a patient.
 * It saves through the API itself and reports the saved patient to the page.
 */
@Component({
  selector: 'mr-patient-form-panel',
  imports: [ReactiveFormsModule, DatePipe],
  host: { '(document:keydown.escape)': 'close()' },
  template: `
    <div class="fixed inset-0 z-40 bg-slate-900/40" (click)="close()" aria-hidden="true"></div>
    <aside class="fixed inset-y-0 right-0 z-50 flex w-full max-w-lg flex-col bg-white shadow-2xl"
           role="dialog" aria-modal="true" aria-labelledby="panel-title">
      <header class="flex items-center justify-between border-b border-slate-200 px-6 py-4">
        <div>
          <h2 id="panel-title" class="text-lg font-semibold">{{ heading() }}</h2>
          @if (patient(); as current) {
            <p class="text-xs text-slate-500">Patient #{{ current.id }}</p>
          }
        </div>
        <button type="button" (click)="close()" class="rounded-lg p-2 text-slate-500 hover:bg-slate-100" aria-label="Close panel">
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </header>

      <form [formGroup]="form" (ngSubmit)="save()" class="flex min-h-0 flex-1 flex-col" novalidate>
        <div class="flex-1 space-y-5 overflow-y-auto px-6 py-5">
          @if (bannerError()) {
            <p class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{{ bannerError() }}</p>
          }

          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <label for="firstName" class="field-label">First name</label>
              <input #firstInput id="firstName" formControlName="firstName" autocomplete="off" class="field-input" [class.field-invalid]="errorFor('firstName')" />
              @if (errorFor('firstName'); as error) { <p class="field-error">{{ error }}</p> }
            </div>
            <div>
              <label for="lastName" class="field-label">Last name</label>
              <input id="lastName" formControlName="lastName" autocomplete="off" class="field-input" [class.field-invalid]="errorFor('lastName')" />
              @if (errorFor('lastName'); as error) { <p class="field-error">{{ error }}</p> }
            </div>
            <div>
              <label for="dateOfBirth" class="field-label">Date of birth</label>
              <input id="dateOfBirth" type="date" formControlName="dateOfBirth" [max]="today" class="field-input" [class.field-invalid]="errorFor('dateOfBirth')" />
              @if (errorFor('dateOfBirth'); as error) { <p class="field-error">{{ error }}</p> }
            </div>
            <div>
              <label for="gender" class="field-label">Gender</label>
              <select id="gender" formControlName="gender" class="field-input" [class.field-invalid]="errorFor('gender')">
                <option value="" disabled>Select…</option>
                @for (option of genders; track option.value) { <option [value]="option.value">{{ option.label }}</option> }
              </select>
              @if (errorFor('gender'); as error) { <p class="field-error">{{ error }}</p> }
            </div>
          </div>

          <div>
            <label for="email" class="field-label">Email</label>
            <input id="email" type="email" formControlName="email" autocomplete="off" class="field-input" [class.field-invalid]="errorFor('email')" />
            @if (errorFor('email'); as error) { <p class="field-error">{{ error }}</p> }
          </div>

          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <label for="phone" class="field-label">Phone <span class="font-normal text-slate-400">(optional)</span></label>
              <input id="phone" type="tel" formControlName="phone" autocomplete="off" placeholder="+44 7700 900123" class="field-input" [class.field-invalid]="errorFor('phone')" />
              @if (errorFor('phone'); as error) { <p class="field-error">{{ error }}</p> }
            </div>
            <div>
              <label for="city" class="field-label">City</label>
              <input id="city" formControlName="city" list="city-options" autocomplete="off" class="field-input" [class.field-invalid]="errorFor('city')" />
              <datalist id="city-options">
                @for (city of cities(); track city) { <option [value]="city"></option> }
              </datalist>
              @if (errorFor('city'); as error) { <p class="field-error">{{ error }}</p> }
            </div>
          </div>

          <div class="sm:w-1/2 sm:pr-2">
            <label for="bloodType" class="field-label">Blood type <span class="font-normal text-slate-400">(optional)</span></label>
            <select id="bloodType" formControlName="bloodType" class="field-input">
              <option value="">Unknown</option>
              @for (option of bloodTypes; track option.value) { <option [value]="option.value">{{ option.label }}</option> }
            </select>
          </div>

          @if (patient(); as current) {
            <dl class="grid grid-cols-2 gap-4 border-t border-slate-100 pt-4 text-xs text-slate-500">
              <div><dt>Created</dt><dd class="mt-0.5 text-slate-700">{{ current.createdAt | date: 'medium' }}</dd></div>
              <div><dt>Last updated</dt><dd class="mt-0.5 text-slate-700">{{ current.updatedAt | date: 'medium' }}</dd></div>
            </dl>
          }
        </div>

        <footer class="flex justify-end gap-3 border-t border-slate-200 px-6 py-4">
          <button type="button" (click)="close()" class="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50">
            {{ readOnly() ? 'Close' : 'Cancel' }}
          </button>
          @if (!readOnly()) {
            <button type="submit" [disabled]="saving()"
                    class="rounded-lg bg-teal-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-teal-700 disabled:cursor-not-allowed disabled:opacity-60">
              {{ saving() ? 'Saving…' : patient() ? 'Save changes' : 'Create patient' }}
            </button>
          }
        </footer>
      </form>
    </aside>
  `,
  styles: `
    .field-label { display: block; font-size: 0.875rem; font-weight: 500; color: var(--color-slate-700); }
    .field-input {
      margin-top: 0.25rem; display: block; width: 100%; border-radius: 0.5rem; border: 1px solid var(--color-slate-300);
      background: white; padding: 0.5rem 0.75rem; font-size: 0.875rem; outline: none;
    }
    .field-input:focus { border-color: var(--color-teal-600); box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-teal-600) 20%, transparent); }
    .field-input:disabled { background: var(--color-slate-50); color: var(--color-slate-700); }
    .field-invalid { border-color: var(--color-red-500); }
    .field-error { margin-top: 0.25rem; font-size: 0.75rem; color: var(--color-red-600); }
  `,
})
export class PatientFormPanel implements OnInit {
  private readonly patients = inject(PatientsService);
  private readonly toast = inject(ToastService);

  /** The patient to edit or view; null creates a new one. */
  readonly patient = input<Patient | null>(null);
  readonly readOnly = input(false);
  readonly cities = input<string[]>([]);

  readonly saved = output<Patient>();
  readonly closed = output<void>();

  protected readonly genders = GENDERS;
  protected readonly bloodTypes = BLOOD_TYPES;
  protected readonly today = new Date().toISOString().slice(0, 10);
  protected readonly saving = signal(false);
  protected readonly bannerError = signal('');
  protected readonly heading = computed(() =>
    this.readOnly() ? 'Patient details' : this.patient() ? 'Edit patient' : 'New patient',
  );

  protected readonly form = inject(NonNullableFormBuilder).group({
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    dateOfBirth: ['', [Validators.required, inThePast]],
    gender: ['' as Gender | '', Validators.required],
    email: ['', [Validators.required, Validators.maxLength(254), Validators.pattern(EMAIL_PATTERN)]],
    phone: ['', Validators.pattern(PHONE_PATTERN)],
    city: ['', [Validators.required, Validators.maxLength(100)]],
    bloodType: ['' as BloodType | ''],
  });

  private readonly firstInput = viewChild.required<ElementRef<HTMLInputElement>>('firstInput');

  constructor() {
    afterNextRender(() => {
      if (!this.readOnly()) this.firstInput().nativeElement.focus();
    });
  }

  ngOnInit(): void {
    const patient = this.patient();
    if (patient) {
      this.form.setValue({
        firstName: patient.firstName,
        lastName: patient.lastName,
        dateOfBirth: patient.dateOfBirth,
        gender: patient.gender,
        email: patient.email,
        phone: patient.phone ?? '',
        city: patient.city,
        bloodType: patient.bloodType ?? '',
      });
    }
    if (this.readOnly()) this.form.disable();
  }

  protected close(): void {
    if (!this.saving()) this.closed.emit();
  }

  protected errorFor(name: keyof typeof this.form.controls): string | null {
    const control = this.form.controls[name];
    if (!control.errors || !(control.touched || control.dirty)) return null;
    const errors = control.errors;
    if (errors['server']) return errors['server'] as string;
    if (errors['required']) return 'This field is required.';
    if (errors['maxlength']) return `At most ${errors['maxlength'].requiredLength} characters.`;
    if (errors['past']) return 'Must be a date in the past.';
    if (errors['pattern']) return PATTERN_MESSAGES[name] ?? 'Invalid format.';
    return 'Invalid value.';
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const existing = this.patient();
    const request: PatientRequest = {
      ...value,
      gender: value.gender as Gender,
      phone: value.phone.trim() || null,
      bloodType: value.bloodType || null,
      version: existing?.version,
    };
    this.saving.set(true);
    this.bannerError.set('');
    const call = existing ? this.patients.update(existing.id, request) : this.patients.create(request);
    call.subscribe({
      next: (patient) => {
        this.saving.set(false);
        this.toast.success(existing ? `Saved changes to ${patient.firstName} ${patient.lastName}.` : `Created ${patient.firstName} ${patient.lastName}.`);
        this.saved.emit(patient);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.showServerErrors(error);
      },
    });
  }

  /** Field errors go next to their inputs; anything else (409, 403...) becomes the banner. */
  private showServerErrors(error: unknown): void {
    const fieldErrors = problemOf(error)?.errors ?? [];
    let unplaced = false;
    for (const { field, message } of fieldErrors) {
      const control = this.form.get(field);
      if (control) {
        control.setErrors({ server: message });
        control.markAsTouched();
      } else {
        unplaced = true;
      }
    }
    if (fieldErrors.length === 0 || unplaced) {
      this.bannerError.set(errorMessage(error, 'Could not save the patient. Please try again.'));
    }
  }
}
