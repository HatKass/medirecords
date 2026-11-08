import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page, Patient, PatientQuery, PatientRequest } from './patient.models';

const API = `${environment.apiBaseUrl}/api/patients`;

@Injectable({ providedIn: 'root' })
export class PatientsService {
  private readonly http = inject(HttpClient);

  list(query: PatientQuery): Observable<Page<Patient>> {
    let params = new HttpParams()
      .set('page', query.page)
      .set('size', query.size)
      .append('sort', `${query.sort},${query.direction}`);
    // Secondary order for equal last names; the API adds the id as a final tie-breaker.
    if (query.sort === 'lastName') params = params.append('sort', `firstName,${query.direction}`);
    if (query.name.trim()) params = params.set('name', query.name.trim());
    if (query.city) params = params.set('city', query.city);
    if (query.bloodType) params = params.set('bloodType', query.bloodType);
    return this.http.get<Page<Patient>>(API, { params });
  }

  cities(): Observable<string[]> {
    return this.http.get<string[]>(`${API}/cities`);
  }

  create(request: PatientRequest): Observable<Patient> {
    return this.http.post<Patient>(API, request);
  }

  update(id: number, request: PatientRequest): Observable<Patient> {
    return this.http.put<Patient>(`${API}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${API}/${id}`);
  }
}
