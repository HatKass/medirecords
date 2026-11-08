import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { CurrentUser } from './auth.models';

const ADMIN: CurrentUser = { username: 'admin', fullName: 'Dr. Alex Morgan', roles: ['ADMIN'] };
const UNAUTHORIZED = { status: 401, statusText: 'Unauthorized' };

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => backend.verify());

  it('never adds an Authorization header (the session is an HttpOnly cookie)', () => {
    http.get('/api/patients').subscribe();
    expect(backend.expectOne('/api/patients').request.headers.has('Authorization')).toBe(false);
  });

  it('on 401 refreshes once and retries the original request', () => {
    let result: unknown;
    http.get('/api/patients').subscribe((body) => (result = body));

    backend.expectOne('/api/patients').flush(null, UNAUTHORIZED);
    backend.expectOne('/api/auth/refresh').flush(ADMIN);
    backend.expectOne('/api/patients').flush({ ok: true });

    expect(result).toEqual({ ok: true });
    expect(auth.user()).toEqual(ADMIN);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shares one refresh between concurrent 401s', () => {
    http.get('/api/a').subscribe();
    http.get('/api/b').subscribe();
    backend.expectOne('/api/a').flush(null, UNAUTHORIZED);
    backend.expectOne('/api/b').flush(null, UNAUTHORIZED);

    backend.expectOne('/api/auth/refresh').flush(ADMIN);

    backend.expectOne('/api/a').flush({});
    backend.expectOne('/api/b').flush({});
  });

  it('redirects to /login when the refresh fails', () => {
    let failure: HttpErrorResponse | undefined;
    http.get('/api/patients').subscribe({ error: (error) => (failure = error) });

    backend.expectOne('/api/patients').flush(null, UNAUTHORIZED);
    backend.expectOne('/api/auth/refresh').flush(null, UNAUTHORIZED);

    expect(failure?.status).toBe(401);
    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reason: 'expired' } });
  });

  it('does not try to refresh after a failed login', () => {
    auth.login('admin', 'wrong').subscribe({ error: () => undefined });
    backend.expectOne('/api/auth/login').flush(null, UNAUTHORIZED);
    backend.expectNone('/api/auth/refresh');
  });

  it('restoreSession tries a refresh but stays on the page when signed out', async () => {
    const restored = auth.restoreSession();

    backend.expectOne('/api/auth/me').flush(null, UNAUTHORIZED);
    backend.expectOne('/api/auth/refresh').flush(null, UNAUTHORIZED);
    await restored;

    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('restoreSession signs the user back in from the cookie', async () => {
    const restored = auth.restoreSession();
    backend.expectOne('/api/auth/me').flush(ADMIN);
    await restored;

    expect(auth.user()).toEqual(ADMIN);
    expect(auth.isAdmin()).toBe(true);
  });
});
