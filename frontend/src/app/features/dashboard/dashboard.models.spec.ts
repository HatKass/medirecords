import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BloodTypeCount, barWidth, rejectionRate } from './dashboard.models';
import { DashboardService } from './dashboard.service';

describe('DashboardService', () => {
  it('loads everything with one GET', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const backend = TestBed.inject(HttpTestingController);
    TestBed.inject(DashboardService).get().subscribe();
    expect(backend.expectOne('/api/dashboard').request.method).toBe('GET');
    backend.verify();
  });
});

describe('rejectionRate', () => {
  const base = { count: 1, failedImports: 0, inProgress: 0 };

  it('is the rounded share of rejected rows, or null when nothing was processed', () => {
    expect(rejectionRate({ ...base, validRows: 95, invalidRows: 5 })).toBe(5);
    expect(rejectionRate({ ...base, validRows: 2, invalidRows: 1 })).toBe(33);
    expect(rejectionRate({ ...base, validRows: 0, invalidRows: 0 })).toBeNull();
  });
});

describe('barWidth', () => {
  const groups: BloodTypeCount[] = [
    { bloodType: 'O_POSITIVE', count: 40 },
    { bloodType: 'A_NEGATIVE', count: 10 },
    { bloodType: null, count: 0 },
  ];

  it('scales against the largest group', () => {
    expect(barWidth(40, groups)).toBe(100);
    expect(barWidth(10, groups)).toBe(25);
    expect(barWidth(0, groups)).toBe(0);
  });

  it('is zero when every group is empty', () => {
    expect(barWidth(0, [{ bloodType: 'O_POSITIVE', count: 0 }])).toBe(0);
  });
});
