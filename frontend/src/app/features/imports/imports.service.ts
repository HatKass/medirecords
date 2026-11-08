import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, retry, switchMap, takeWhile, timer } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page } from '../patients/patient.models';
import { ImportRowError, PatientImport, isFinished } from './import.models';

const API = `${environment.apiBaseUrl}/api/imports`;
const POLL_INTERVAL_MS = 1000;

@Injectable({ providedIn: 'root' })
export class ImportsService {
  private readonly http = inject(HttpClient);

  /** Uploads the file; the server answers 202 as soon as the job is queued. */
  upload(file: File): Observable<PatientImport> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<PatientImport>(API, body);
  }

  list(page: number, size: number): Observable<Page<PatientImport>> {
    return this.http.get<Page<PatientImport>>(API, { params: new HttpParams().set('page', page).set('size', size) });
  }

  get(id: number): Observable<PatientImport> {
    return this.http.get<PatientImport>(`${API}/${id}`);
  }

  /**
   * Emits the import now and then every second until it has finished (the last emission is the
   * finished state). A dropped request is retried a few times before the stream errors.
   */
  poll(id: number): Observable<PatientImport> {
    return timer(0, POLL_INTERVAL_MS).pipe(
      switchMap(() => this.get(id).pipe(retry({ count: 3, delay: POLL_INTERVAL_MS }))),
      takeWhile((item) => !isFinished(item.status), true),
    );
  }

  errors(id: number, page: number, size: number): Observable<Page<ImportRowError>> {
    return this.http.get<Page<ImportRowError>>(`${API}/${id}/errors`, {
      params: new HttpParams().set('page', page).set('size', size),
    });
  }

  /**
   * Fetches the error report through HttpClient (not a plain link), so an expired session is refreshed
   * by the interceptor instead of the browser showing a raw 401, then hands the file to the browser.
   */
  downloadErrorReport(item: PatientImport): Observable<void> {
    return this.http
      .get(`${API}/${item.id}/errors.csv`, { responseType: 'blob', observe: 'response' })
      .pipe(map((response) => saveFile(response, `import-${item.id}-errors.csv`)));
  }
}

function saveFile(response: HttpResponse<Blob>, fallbackName: string): void {
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const plain = /filename="([^"]+)"/i.exec(disposition)?.[1];
  const name = encoded ? decodeURIComponent(encoded) : (plain ?? fallbackName);

  const url = URL.createObjectURL(response.body!);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
