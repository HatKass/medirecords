# Testing

Three layers, each runnable on its own:

| Layer | Where | Tools | Needs |
|---|---|---|---|
| Backend unit + integration | `backend/src/test` | JUnit 5, Mockito, Spring Boot Test, Testcontainers (PostgreSQL 16) | JDK 21, Docker |
| Frontend unit | `frontend/src/**/*.spec.ts` | Angular TestBed, Vitest, jsdom | Node 22.22+ / 24.15+ |
| End to end | `e2e/tests` | Playwright (bundled Chromium, or installed Chrome) | A running stack (Docker Compose) |

## Backend

```bash
cd backend
./mvnw verify          # all tests + JaCoCo report (Windows: mvnw.cmd verify)
```

- Integration tests start one PostgreSQL 16 container (Testcontainers) and run the real Flyway
  migrations, so Docker must be running. All test classes share one Spring context and one container.
- Coverage report: `backend/target/site/jacoco/index.html` (and `jacoco.xml` / `jacoco.csv`).
- One class: `./mvnw test -Dtest=CsrfIntegrationTest`.

What the backend tests cover:

| Test class | Focus |
|---|---|
| `AccessRulesIntegrationTest` | Role matrix over every API endpoint (anonymous / VIEWER / ADMIN); expired, foreign-issuer, wrongly signed and unsigned JWTs; unlisted paths denied |
| `CsrfIntegrationTest` | Every state-changing endpoint rejects a missing, wrong or cookie-less CSRF token, with no side effect |
| `AuthIntegrationTest` | Cookie flags, login/logout, refresh rotation and replay detection, expired/unknown refresh tokens, disabled accounts |
| `PatientIntegrationTest` | CRUD, normalization, validation errors (ProblemDetail, no internals leaked), optimistic locking, duplicates, filters, paging |
| `ImportIntegrationTest` | The Spring Batch pipeline end to end: messy rows, per-row reasons, error report round trip, encoding/binary/empty files, duplicates across chunks, restart recovery |
| `ImportServiceTest` | Mockito unit test: a full worker queue answers 503 and records the import as failed |
| `CsvParsingTest` | Lenient date / gender / blood type parsing and header aliases |
| `AuditIntegrationTest` | Field-level change log, one entry per import, sign-in events, filters, ADMIN only, append-only table, failed changes leave no entry |
| `DashboardIntegrationTest` | Figures match the database; week window; VIEWER gets no audit activity |

## Frontend

```bash
cd frontend
npm ci
npx ng test --watch=false   # or: npm test (watch mode)
npm run build
```

## End to end (Playwright)

The e2e tests drive a real browser against a running stack. They create (and delete) a patient and
import `sample-data/patients_messy.csv`, so run them against a throwaway stack rather than a database
you care about. A separate Compose project gets its own volume and ports:

```bash
# from the repository root: an isolated stack on ports 4300 / 8081 / 5434
POSTGRES_HOST_PORT=5434 BACKEND_HOST_PORT=8081 FRONTEND_HOST_PORT=4300 docker compose -p medirecords-e2e up -d --build --wait

cd e2e
npm ci
```

Then run the tests with one of two browsers:

**Option A: Playwright's bundled Chromium** (what CI uses)

```bash
npx playwright install chromium        # once; CI uses: npx playwright install --with-deps chromium
E2E_BASE_URL=http://localhost:4300 npx playwright test
```

**Option B: your installed Google Chrome** (when the bundled Chromium cannot be downloaded or
extracted on your machine; nothing to install)

```bash
E2E_BROWSER_CHANNEL=chrome E2E_BASE_URL=http://localhost:4300 npx playwright test
```

`E2E_BROWSER_CHANNEL` is passed to Playwright as the browser `channel` (`chrome`, `msedge`, …).
Leave it unset to use the bundled Chromium.

Afterwards, remove the stack and its data:

```bash
docker compose -p medirecords-e2e down -v
```

PowerShell: set the variables first, e.g.

```powershell
$env:E2E_BASE_URL = "http://localhost:4300"
$env:E2E_BROWSER_CHANNEL = "chrome"   # Option B only
npx playwright test
```

Do the same for the three port variables before `docker compose -p medirecords-e2e up`.

Without `E2E_BASE_URL` the tests use `http://localhost:4200`, i.e. the default `docker compose up` stack
or `ng serve` with a local backend. `npx playwright show-report` opens the HTML report; failed tests keep a
trace and a screenshot in `e2e/test-results/`.

The suite (`auth`, `patients`, `import` specs) covers: signing in as ADMIN and VIEWER, signing out,
deep links surviving sign-in, the VIEWER being kept out of admin pages (UI and API), creating, editing
and deleting a patient, client-side validation, and uploading the sample CSV through to a finished
import with a downloadable error report. The import test is written to pass on repeated runs against
the same database (a second import of the sample rejects every row as a duplicate).

## CI

`.github/workflows/ci.yml` runs on every pull request and on pushes to `main` (so a branch with an open
PR is checked once, not once for the push and again for the PR):

1. **backend**: `./mvnw verify` (Testcontainers uses the runner's Docker); coverage table in the job
   summary; Surefire and JaCoCo reports uploaded as an artifact.
2. **frontend**: `npm ci`, unit tests, production build.
3. **e2e** (after both pass): `docker compose up --build --wait`, then
   `npx playwright install --with-deps chromium` and `npx playwright test` with the bundled Chromium
   (`E2E_BROWSER_CHANNEL` is not set in CI). On failure, the stack logs are printed and the HTML
   report and traces (`playwright-report/`, `test-results/`) are uploaded as the `playwright-report`
   artifact; open a trace with `npx playwright show-trace <trace.zip>`.

   This job runs on `ubuntu-24.04`, not `ubuntu-latest`: Playwright installs its browsers only on
   Ubuntu releases it supports, and Playwright 1.56 rejects 26.04 (the current `ubuntu-latest`).
   Move the runner forward only together with a Playwright upgrade.
