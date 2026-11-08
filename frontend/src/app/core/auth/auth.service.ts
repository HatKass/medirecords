import { HttpClient, HttpContext, HttpContextToken } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, firstValueFrom, map, of, shareReplay, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CurrentUser, Role } from './auth.models';

const AUTH_API = `${environment.apiBaseUrl}/api/auth`;

/** Endpoints whose 401 means "bad credentials / no session", never "refresh and retry". */
export const AUTH_ENDPOINTS = ['login', 'refresh', 'logout'].map((name) => `${AUTH_API}/${name}`);

/** Set to false on a request whose 401 should not send the user to the login page. */
export const REDIRECT_ON_UNAUTHORIZED = new HttpContextToken<boolean>(() => true);

/**
 * Holds who is signed in. The session itself is a pair of HttpOnly cookies the browser manages;
 * this service only mirrors the user returned by the API, so nothing secret is kept in JavaScript.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly currentUser = signal<CurrentUser | null>(null);
  private refreshInFlight: Observable<CurrentUser> | null = null;

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);
  readonly isAdmin = computed(() => this.hasRole('ADMIN'));

  /**
   * Runs once at app start (before the first navigation). Also gives the browser its XSRF-TOKEN
   * cookie, which every later POST (login included) needs. A 401 here just means "signed out".
   */
  restoreSession(): Promise<void> {
    const context = new HttpContext().set(REDIRECT_ON_UNAUTHORIZED, false);
    return firstValueFrom(
      this.http.get<CurrentUser>(`${AUTH_API}/me`, { context }).pipe(
        tap((user) => this.currentUser.set(user)),
        map(() => undefined),
        catchError(() => {
          this.currentUser.set(null);
          return of(undefined);
        }),
      ),
    );
  }

  login(username: string, password: string): Observable<CurrentUser> {
    return this.http
      .post<CurrentUser>(`${AUTH_API}/login`, { username, password })
      .pipe(tap((user) => this.currentUser.set(user)));
  }

  /**
   * Exchanges the refresh cookie for new cookies. Concurrent callers share one request, because the
   * server rotates the refresh token and would treat a second use of the old one as a replay.
   */
  refresh(): Observable<CurrentUser> {
    this.refreshInFlight ??= this.http.post<CurrentUser>(`${AUTH_API}/refresh`, null).pipe(
      tap((user) => this.currentUser.set(user)),
      finalize(() => (this.refreshInFlight = null)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.refreshInFlight;
  }

  /** Asks the server to revoke the session and clear the cookies, then shows the login page. */
  logout(): void {
    this.http
      .post<void>(`${AUTH_API}/logout`, null)
      .pipe(catchError(() => of(undefined)))
      .subscribe(() => this.endSession());
  }

  /** Forgets the user locally (the session is already gone server-side) and goes to the login page. */
  endSession(options: { sessionExpired?: boolean; returnUrl?: string } = {}): void {
    this.currentUser.set(null);
    const queryParams: Record<string, string> = {};
    if (options.sessionExpired) queryParams['reason'] = 'expired';
    if (options.returnUrl && options.returnUrl !== '/' && !options.returnUrl.startsWith('/login')) {
      queryParams['returnUrl'] = options.returnUrl;
    }
    void this.router.navigate(['/login'], { queryParams });
  }

  hasRole(...roles: Role[]): boolean {
    return this.currentUser()?.roles.some((role) => roles.includes(role)) ?? false;
  }
}
