/**
 * Base URL of the backend. Empty means "same origin": in development the Angular
 * dev server proxies /api and /actuator (see proxy.conf.json), and in Docker the
 * nginx container does the same (see nginx.conf).
 */
export const environment = {
  apiBaseUrl: '',
};
