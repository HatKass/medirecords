-- V1: database baseline.
-- pg_trgm enables fast case-insensitive "contains" searches (ILIKE '%term%') through GIN
-- trigram indexes; the patient name filter in a later migration relies on it.
-- It is available on managed Postgres providers (Neon, Supabase, Render, Railway).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
