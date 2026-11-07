You are a senior Java/Angular engineer helping me build a portfolio project for my Upwork profile. The goal is a clean, professional, production-style app that a client can understand in 10 seconds and a technical reviewer can respect.



\## Project: "MediRecords" — Patient Records Management Dashboard

A fictional clinic app to manage patient records, bulk-import messy CSV data safely, and track every change with an audit log.



\## Tech stack (use these exact choices)

\- Backend: Java 21, Spring Boot 3 (latest stable), Spring Web, Spring Data JPA, Spring Security (JWT, stateless), Spring Batch 5, Bean Validation, Flyway, springdoc-openapi (Swagger UI)

\- Database: PostgreSQL 16

\- Frontend: Angular (latest stable) with standalone components, signals where sensible, Angular Router, HttpClient with interceptors, Tailwind CSS

\- Infra: Docker + Docker Compose (postgres + backend + frontend), one command to run everything

\- Tests: JUnit 5, Mockito, Testcontainers (PostgreSQL) for integration tests



\## Features

1\. Authentication \& roles

&#x20;  - JWT login, two roles: ADMIN (full access) and VIEWER (read-only)

&#x20;  - Seed two demo users: admin / demo123 and viewer / demo123

&#x20;  - Angular route guards + HTTP interceptor that attaches the token and handles 401



2\. Patient CRUD

&#x20;  - Patient fields: id, firstName, lastName, dateOfBirth, gender, email, phone, city, bloodType, createdAt, updatedAt

&#x20;  - REST API with validation, pagination, sorting, and filtering (by name, city, blood type)

&#x20;  - Consistent error responses via @ControllerAdvice (ProblemDetail format)

&#x20;  - DTOs + mapper layer (no entities exposed in the API)



3\. Bulk CSV import with Spring Batch (the showcase feature)

&#x20;  - Upload a CSV from the UI; start a Spring Batch job asynchronously

&#x20;  - Reader → Processor (validation + cleansing: trim, normalize phone/email, parse dates in multiple formats, reject duplicates by email) → Writer

&#x20;  - Invalid rows are skipped and recorded with row number + reason

&#x20;  - Import history table: job id, file name, status, total/valid/invalid counts, started/finished time

&#x20;  - Endpoint + UI button to download an error report CSV for each import

&#x20;  - UI polls job status and shows progress



4\. Audit log

&#x20;  - Record every create/update/delete/import: who, what entity, action, timestamp, and changed fields (old → new values)

&#x20;  - Implement cleanly (e.g. JPA entity listeners or an AOP aspect — explain your choice)

&#x20;  - Audit log page with filters (user, action, date range), ADMIN only



5\. Dashboard home page

&#x20;  - Summary cards: total patients, imports this week, failed rows, recent activity

&#x20;  - One simple chart (patients per city or per blood type)



\## Frontend design

\- Clean, modern admin layout: sidebar navigation, top bar with current user + logout

\- Pages: Login, Dashboard, Patients (table + create/edit form in modal or side panel), Import, Import History, Audit Log

\- Fully responsive, Tailwind only, neutral professional palette, loading and empty states, toast notifications for success/errors

\- Show the demo credentials on the login page



\## Seed \& demo data

\- Flyway migration that seeds \~200 realistic fake patients

\- Include in the repo /sample-data/patients\_messy.csv with \~5,000 rows, where \~5% are deliberately broken (bad emails, invalid dates, missing names, duplicates) so the import demo is meaningful



\## Code quality

\- Package by feature (auth, patient, importjob, audit), not by layer

\- Constructor injection, no Lombok abuse, meaningful names, no dead code

\- Config via environment variables with sensible defaults in application.yml

\- Integration tests for: auth, patient CRUD + filtering, and the batch import (valid + invalid rows)



\## Deliverables

\- Monorepo: /backend, /frontend, docker-compose.yml, /sample-data

\- A strong README.md: one-paragraph summary, feature list, screenshots/GIF placeholders, architecture diagram (Mermaid), tech stack, "Run locally in one command", demo credentials, API docs link (Swagger), and a "Design decisions" section explaining the batch pipeline and audit approach

\- Deployment notes for: frontend on Vercel/Netlify, backend on Render/Railway, database on Neon/Supabase



\## How to work

Build in phases and STOP after each phase so I can review, run, and understand the code before continuing:

1\. Project setup: backend + frontend skeletons, Docker Compose, Flyway, health check

2\. Auth \& roles (backend + Angular login, guard, interceptor)

3\. Patient CRUD API + Angular patients page

4\. Spring Batch CSV import + import history + error report + UI

5\. Audit log (backend + UI)

6\. Dashboard page

7\. Tests

8\. README, sample data, deployment notes



At each phase: briefly explain the key design decisions, list the files created, and tell me exactly how to run and test what was just built. If something is ambiguous, pick the most professional option and tell me what you chose.

