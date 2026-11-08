import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ImportStatus, PatientImport, importDuration } from './import.models';
import { ImportsService } from './imports.service';

function importWith(status: ImportStatus, totalRows = 0): PatientImport {
  return {
    id: 5,
    jobExecutionId: 1,
    fileName: 'patients.csv',
    status,
    importedBy: 'admin',
    expectedRows: 100,
    totalRows,
    validRows: totalRows,
    invalidRows: 0,
    progressPercent: totalRows,
    failureMessage: null,
    createdAt: '2026-11-08T10:00:00Z',
    startedAt: '2026-11-08T10:00:00Z',
    finishedAt: null,
  };
}

describe('ImportsService.poll', () => {
  let service: ImportsService;
  let backend: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ImportsService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  it('polls every second and stops after emitting the finished import', () => {
    const seen: ImportStatus[] = [];
    let completed = false;
    service.poll(5).subscribe({ next: (item) => seen.push(item.status), complete: () => (completed = true) });

    vi.advanceTimersByTime(0);
    backend.expectOne('/api/imports/5').flush(importWith('QUEUED'));
    vi.advanceTimersByTime(1000);
    backend.expectOne('/api/imports/5').flush(importWith('RUNNING', 40));
    vi.advanceTimersByTime(1000);
    backend.expectOne('/api/imports/5').flush(importWith('COMPLETED', 100));

    vi.advanceTimersByTime(5000);
    backend.expectNone('/api/imports/5');
    expect(seen).toEqual(['QUEUED', 'RUNNING', 'COMPLETED']);
    expect(completed).toBe(true);
  });

  it('retries a dropped request instead of failing the progress view', () => {
    const seen: ImportStatus[] = [];
    let failed = false;
    service.poll(5).subscribe({ next: (item) => seen.push(item.status), error: () => (failed = true) });

    vi.advanceTimersByTime(0);
    backend.expectOne('/api/imports/5').flush(null, { status: 0, statusText: 'Unknown Error' });
    vi.advanceTimersByTime(1000);
    backend.expectOne('/api/imports/5').flush(importWith('FAILED'));

    expect(failed).toBe(false);
    expect(seen).toEqual(['FAILED']);
  });
});

describe('importDuration', () => {
  it('formats seconds and minutes, and is null until the import finished', () => {
    const started = importWith('RUNNING');
    expect(importDuration(started)).toBeNull();
    expect(importDuration({ ...started, finishedAt: '2026-11-08T10:00:01.234Z' })).toBe('1.2 s');
    expect(importDuration({ ...started, finishedAt: '2026-11-08T10:03:05Z' })).toBe('3 min 05 s');
  });
});
