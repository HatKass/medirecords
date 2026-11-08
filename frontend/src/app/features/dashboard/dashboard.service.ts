import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Dashboard } from './dashboard.models';

const API = `${environment.apiBaseUrl}/api/dashboard`;

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  /** All figures in one request; the server decides what the current role may see. */
  get(): Observable<Dashboard> {
    return this.http.get<Dashboard>(API);
  }
}
