import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PatientQuery, ageFrom } from './patient.models';
import { PatientsService } from './patients.service';

const BASE: PatientQuery = { page: 0, size: 20, sort: 'lastName', direction: 'asc', name: '', city: '', bloodType: '' };

describe('PatientsService', () => {
  let service: PatientsService;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(PatientsService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('sends paging and sort, with first name as secondary order for last name', () => {
    service.list(BASE).subscribe();
    const request = backend.expectOne((req) => req.url === '/api/patients');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.getAll('sort')).toEqual(['lastName,asc', 'firstName,asc']);
    expect(request.request.params.has('name')).toBe(false);
    request.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('only sends filters that are set, trimming the name', () => {
    service.list({ ...BASE, sort: 'dateOfBirth', direction: 'desc', name: '  ann ', city: 'Leeds', bloodType: 'O_NEGATIVE' }).subscribe();
    const params = backend.expectOne((req) => req.url === '/api/patients').request.params;
    expect(params.getAll('sort')).toEqual(['dateOfBirth,desc']);
    expect(params.get('name')).toBe('ann');
    expect(params.get('city')).toBe('Leeds');
    expect(params.get('bloodType')).toBe('O_NEGATIVE');
  });
});

describe('ageFrom', () => {
  const today = new Date(2026, 10, 8); // 8 Nov 2026

  it('counts whole years and respects whether the birthday has passed', () => {
    expect(ageFrom('2000-11-08', today)).toBe(26);
    expect(ageFrom('2000-11-09', today)).toBe(25);
    expect(ageFrom('2000-01-31', today)).toBe(26);
  });
});
