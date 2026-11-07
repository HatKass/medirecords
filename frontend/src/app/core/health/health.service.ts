import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface HealthComponent {
  status: string;
  details?: Record<string, unknown>;
}

export interface HealthResponse {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN';
  components?: Record<string, HealthComponent>;
}

@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly http = inject(HttpClient);

  check(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>(`${environment.apiBaseUrl}/actuator/health`);
  }
}
