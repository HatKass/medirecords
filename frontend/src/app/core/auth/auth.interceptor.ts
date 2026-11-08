import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AUTH_ENDPOINTS, AuthService, REDIRECT_ON_UNAUTHORIZED } from './auth.service';

const API_PREFIX = `${environment.apiBaseUrl}/api/`;

/**
 * Credentials travel as cookies and the CSRF header is added by Angular's built-in XSRF support,
 * so this interceptor only handles expiry: on a 401 from our API it refreshes the session once and
 * retries the request; if the refresh fails too, the user is sent to the login page.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const canRefresh = request.url.startsWith(API_PREFIX) && !AUTH_ENDPOINTS.includes(request.url);

  return next(request).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401 || !canRefresh) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        catchError(() => {
          if (request.context.get(REDIRECT_ON_UNAUTHORIZED)) {
            auth.endSession({ sessionExpired: true, returnUrl: router.url });
          }
          return throwError(() => error);
        }),
        // Retry exactly once; a second 401 is passed to the caller as is.
        switchMap(() => next(request)),
      );
    }),
  );
};
