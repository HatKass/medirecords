import { HttpErrorResponse } from '@angular/common/http';

/** RFC 9457 error body returned by every backend error. */
export interface ProblemDetail {
  status?: number;
  title?: string;
  detail?: string;
  /** Present on validation errors (400): one entry per invalid field. */
  errors?: { field: string; message: string }[];
}

export function problemOf(error: unknown): ProblemDetail | null {
  if (!(error instanceof HttpErrorResponse) || typeof error.error !== 'object' || error.error === null) return null;
  return error.error as ProblemDetail;
}

/** A sentence suitable for a toast or an inline error banner. */
export function errorMessage(error: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (error instanceof HttpErrorResponse && error.status === 0) {
    return 'Cannot reach the server. Check your connection and try again.';
  }
  return problemOf(error)?.detail ?? fallback;
}
