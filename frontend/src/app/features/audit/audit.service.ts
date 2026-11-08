import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page } from '../patients/patient.models';
import { AuditEntry, AuditQuery, toInstantRange } from './audit.models';

const API = `${environment.apiBaseUrl}/api/audit-log`;

@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);

  /** Newest first; only filters that are set are sent. */
  list(query: AuditQuery, size: number): Observable<Page<AuditEntry>> {
    let params = new HttpParams().set('page', query.page).set('size', size);
    if (query.actor) params = params.set('actor', query.actor);
    if (query.action) params = params.set('action', query.action);
    const range = toInstantRange(query.from, query.to);
    if (range.from) params = params.set('from', range.from);
    if (range.to) params = params.set('to', range.to);
    return this.http.get<Page<AuditEntry>>(API, { params });
  }

  /** Usernames that appear in the log, for the user filter. */
  actors(): Observable<string[]> {
    return this.http.get<string[]>(`${API}/actors`);
  }
}
