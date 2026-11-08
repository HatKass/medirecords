export type Role = 'ADMIN' | 'VIEWER';

/** Body of /api/auth/me, /login and /refresh. Tokens never reach JavaScript: they live in HttpOnly cookies. */
export interface CurrentUser {
  username: string;
  fullName: string;
  roles: Role[];
}
