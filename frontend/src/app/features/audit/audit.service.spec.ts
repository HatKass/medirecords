import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AUDIT_ACTIONS, AuditQuery, actionLabel, displayValue, isAuthenticationEvent, toInstantRange } from './audit.models';
import { AuditService } from './audit.service';

const NO_FILTERS: AuditQuery = { page: 0, actor: '', action: '', from: '', to: '' };

describe('AuditService', () => {
  let service: AuditService;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuditService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('sends only the filters that are set', () => {
    service.list(NO_FILTERS, 25).subscribe();
    const params = backend.expectOne((req) => req.url === '/api/audit-log').request.params;
    expect(params.keys().sort()).toEqual(['page', 'size']);
  });

  it('turns the picked days into a local-time range that includes the whole last day', () => {
    service.list({ ...NO_FILTERS, actor: 'admin', action: 'IMPORT', from: '2026-11-01', to: '2026-11-08' }, 25).subscribe();
    const params = backend.expectOne((req) => req.url === '/api/audit-log').request.params;
    expect(params.get('actor')).toBe('admin');
    expect(params.get('action')).toBe('IMPORT');
    expect(params.get('from')).toBe(new Date(2026, 10, 1).toISOString());
    expect(params.get('to')).toBe(new Date(2026, 10, 9).toISOString());
  });

  it('sends a sign-in action filter as is', () => {
    service.list({ ...NO_FILTERS, action: 'LOGIN_FAILED' }, 25).subscribe();
    expect(backend.expectOne((req) => req.url === '/api/audit-log').request.params.get('action')).toBe('LOGIN_FAILED');
  });
});

describe('audit models', () => {
  it('offers the sign-in events in the action filter', () => {
    expect(AUDIT_ACTIONS.map((action) => action.value)).toEqual(
      expect.arrayContaining(['LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGOUT']),
    );
    expect(actionLabel('LOGIN_FAILED')).toBe('Failed sign-in');
    expect(isAuthenticationEvent('LOGOUT')).toBe(true);
    expect(isAuthenticationEvent('IMPORT')).toBe(false);
  });

  it('leaves an open-ended range open', () => {
    expect(toInstantRange('', '2026-12-31')).toEqual({ from: undefined, to: new Date(2027, 0, 1).toISOString() });
  });

  it('shows stored enum values with their usual labels', () => {
    expect(displayValue('bloodType', 'AB_NEGATIVE')).toBe('AB−');
    expect(displayValue('gender', 'FEMALE')).toBe('Female');
    expect(displayValue('phone', null)).toBe('—');
    expect(displayValue('city', 'York')).toBe('York');
  });
});
