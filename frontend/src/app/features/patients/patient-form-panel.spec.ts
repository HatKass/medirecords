import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PatientFormPanel } from './patient-form-panel';
import { Patient } from './patient.models';

const EXISTING: Patient = {
  id: 7,
  firstName: 'Ann',
  lastName: 'Lee',
  dateOfBirth: '1980-05-06',
  gender: 'FEMALE',
  email: 'ann.lee@example.com',
  phone: null,
  city: 'Leeds',
  bloodType: 'O_NEGATIVE',
  createdAt: '2026-01-01T10:00:00Z',
  updatedAt: '2026-01-02T10:00:00Z',
  version: 3,
};

describe('PatientFormPanel', () => {
  let fixture: ComponentFixture<PatientFormPanel>;
  let backend: HttpTestingController;
  let element: HTMLElement;

  function open(inputs: { patient?: Patient; readOnly?: boolean } = {}): void {
    fixture = TestBed.createComponent(PatientFormPanel);
    if (inputs.patient) fixture.componentRef.setInput('patient', inputs.patient);
    if (inputs.readOnly) fixture.componentRef.setInput('readOnly', true);
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  }

  function type(id: string, value: string): void {
    const field = element.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)!;
    field.value = value;
    field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? 'change' : 'input'));
  }

  function fillValidPatient(): void {
    type('firstName', 'Jane');
    type('lastName', 'Doe');
    type('dateOfBirth', '1985-04-12');
    type('gender', 'FEMALE');
    type('email', 'jane@example.com');
    type('city', 'London');
  }

  function submit(): void {
    element.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  }

  /** The inline error under the given input, if any. */
  function errorUnder(id: string): string | null {
    return element.querySelector(`#${id}`)!.parentElement!.querySelector('.field-error')?.textContent?.trim() ?? null;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('does not call the API while the form is invalid, and explains each problem', () => {
    open();
    type('email', 'not-an-email');
    type('phone', 'call me');

    submit();

    backend.expectNone(() => true);
    expect(errorUnder('firstName')).toBe('This field is required.');
    expect(errorUnder('email')).toBe('Enter a valid email address, e.g. name@example.com.');
    expect(errorUnder('phone')).toContain('7 to 20 characters');
  });

  it('rejects a birth date that is not in the past', () => {
    open();
    fillValidPatient();
    type('dateOfBirth', '2999-01-01');

    submit();

    backend.expectNone(() => true);
    expect(errorUnder('dateOfBirth')).toBe('Must be a date in the past.');
  });

  it('creates a patient, sending null for blank optional fields', () => {
    open();
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    fillValidPatient(); // phone and blood type left empty

    submit();

    const request = backend.expectOne({ method: 'POST', url: '/api/patients' });
    expect(request.request.body).toEqual({
      firstName: 'Jane',
      lastName: 'Doe',
      dateOfBirth: '1985-04-12',
      gender: 'FEMALE',
      email: 'jane@example.com',
      phone: null,
      city: 'London',
      bloodType: null,
      version: undefined,
    });
    request.flush({ ...EXISTING, id: 8, firstName: 'Jane', lastName: 'Doe' });
    expect(saved).toHaveBeenCalledWith(expect.objectContaining({ id: 8 }));
  });

  it('shows server validation errors next to the matching inputs', () => {
    open();
    fillValidPatient();
    submit();

    backend.expectOne('/api/patients').flush(
      { status: 400, title: 'Validation failed', errors: [{ field: 'email', message: 'must be a valid email address' }] },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    expect(errorUnder('email')).toBe('must be a valid email address');
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });

  it('sends the edited version and shows a conflict as a banner', () => {
    open({ patient: EXISTING });
    type('city', 'York');

    submit();

    const request = backend.expectOne({ method: 'PUT', url: '/api/patients/7' });
    expect(request.request.body).toEqual(expect.objectContaining({ city: 'York', version: 3, bloodType: 'O_NEGATIVE' }));
    request.flush(
      { status: 409, detail: 'This patient was changed by someone else since you opened it.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();

    expect(element.querySelector('[role="alert"]')?.textContent).toContain('changed by someone else');
  });

  it('is view-only for read-only users', () => {
    open({ patient: EXISTING, readOnly: true });

    expect(element.querySelector('h2')?.textContent).toContain('Patient details');
    expect(element.querySelector<HTMLInputElement>('#firstName')!.disabled).toBe(true);
    expect(element.querySelector('button[type="submit"]')).toBeNull();
  });
});
