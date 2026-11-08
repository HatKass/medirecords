# Deployment

Target setup: **frontend on Vercel**, **backend on Render**, **PostgreSQL on Neon or Supabase**.

## Why everything must stay same-origin

The session lives in HttpOnly cookies (`mr_access`, `mr_refresh`) with `SameSite=Lax`, and CSRF
protection relies on the JS-readable `XSRF-TOKEN` cookie. Both only work when the browser sees the
API on **the same origin as the app**. So the browser never calls Render directly: Vercel proxies
`/api/*` to the backend, exactly like nginx does in Docker Compose and `proxy.conf.json` does in
`ng serve`. There is no CORS configuration in the backend, on purpose.

```
browser ──► https://medirecords.vercel.app/            (static Angular build)
        └─► https://medirecords.vercel.app/api/...  ──► Vercel rewrite ──► https://<render-app>.onrender.com/api/...
```

Cookies are set without a `Domain` attribute, so they belong to the Vercel host the browser sees.

## 1. Database (Neon or Supabase)

Create a Postgres 16 database and note the JDBC URL, user and password. Use the provider's
connection string with `sslmode=require`, for example:
`jdbc:postgresql://ep-xxx.eu-central-1.aws.neon.tech/medirecords?sslmode=require`.
Flyway creates the schema and seed data on first start (`pg_trgm` is available on both providers).

## 2. Backend (Render)

Create a **Web Service** from the repo, root directory `backend`, runtime **Docker** (uses
`backend/Dockerfile`). Health check path: `/actuator/health/readiness`.

| Variable | Value |
| --- | --- |
| `DB_URL` | JDBC URL from step 1 |
| `DB_USERNAME` / `DB_PASSWORD` | database credentials |
| `JWT_SECRET` | random, at least 32 bytes: `openssl rand -base64 48` |
| `COOKIE_SECURE` | `true` (the default; Vercel serves HTTPS) |
| `JWT_TTL` / `REFRESH_TOKEN_TTL` | optional, defaults `PT15M` / `P7D` |

Render injects `PORT`, which the app already honours.

## 3. Frontend (Vercel)

Import the repo with **root directory `frontend`**. Build settings come from
[`frontend/vercel.json`](../frontend/vercel.json):

- builds with `ng build` and serves `dist/frontend/browser`;
- **rewrites** `/api/*`, `/swagger-ui/*`, `/v3/api-docs*` and `/actuator/*` to the Render backend;
- falls back to `index.html` for Angular routes (static files are served first).

**Edit the backend host** in `vercel.json` (`medirecords-api.onrender.com` is a placeholder) to
your Render service URL, then deploy. The Angular build needs no API URL: `environment.apiBaseUrl`
stays empty (same origin).

Netlify works the same way with a `_redirects` file:
`/api/*  https://<render-app>.onrender.com/api/:splat  200`, followed by `/*  /index.html  200`.

## Checklist after deploying

- `https://<vercel-app>/actuator/health` returns `UP` (proves the rewrite reaches Render).
- Sign in, then in DevTools → Application → Cookies, `mr_access` and `mr_refresh` are listed
  under the **Vercel** host with `HttpOnly`, `Secure`, `SameSite=Lax`.
- Swagger UI: `https://<vercel-app>/swagger-ui/index.html`.

Note: Render's free tier sleeps when idle; the first request after a pause can take ~30 s.
