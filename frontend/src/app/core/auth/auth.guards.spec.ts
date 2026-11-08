import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './auth.guards';
import { AuthService } from './auth.service';
import { Role } from './auth.models';

describe('auth guards', () => {
  let signedIn: boolean;
  let role: Role;

  const route = {} as ActivatedRouteSnapshot;
  const state = (url: string) => ({ url }) as RouterStateSnapshot;
  const run = <T>(guard: () => T) => TestBed.runInInjectionContext(guard);
  const serialize = (result: unknown) => TestBed.inject(Router).serializeUrl(result as UrlTree);

  beforeEach(() => {
    signedIn = false;
    role = 'VIEWER';
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => signedIn,
            hasRole: (...roles: Role[]) => signedIn && roles.includes(role),
          },
        },
      ],
    });
  });

  it('authGuard sends anonymous users to /login with a return URL', () => {
    const result = run(() => authGuard(route, state('/patients?page=2')));
    expect(serialize(result)).toBe('/login?returnUrl=%2Fpatients%3Fpage%3D2');
  });

  it('authGuard lets signed-in users through', () => {
    signedIn = true;
    expect(run(() => authGuard(route, state('/patients')))).toBe(true);
  });

  it('guestGuard redirects signed-in users away from the login page', () => {
    signedIn = true;
    expect(serialize(run(() => guestGuard(route, state('/login'))))).toBe('/');
  });

  it('roleGuard only admits the listed roles', () => {
    signedIn = true;
    const adminOnly = roleGuard('ADMIN');
    expect(serialize(run(() => adminOnly(route, state('/audit'))))).toBe('/');
    role = 'ADMIN';
    expect(run(() => adminOnly(route, state('/audit')))).toBe(true);
  });
});
