import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from './auth.models';
import { AuthService } from './auth.service';

/** Lets signed-in users through; everyone else goes to /login and comes back afterwards. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.isAuthenticated()) return true;
  const queryParams = state.url && state.url !== '/' ? { returnUrl: state.url } : {};
  return inject(Router).createUrlTree(['/login'], { queryParams });
};

/** Keeps signed-in users away from the login page. */
export const guestGuard: CanActivateFn = () =>
  inject(AuthService).isAuthenticated() ? inject(Router).createUrlTree(['/']) : true;

/**
 * Restricts a route to the given roles (e.g. the ADMIN-only audit log). Use after {@link authGuard}.
 * Users without the role land on the dashboard instead of an error page.
 */
export function roleGuard(...roles: Role[]): CanActivateFn {
  return () => (inject(AuthService).hasRole(...roles) ? true : inject(Router).createUrlTree(['/']));
}
