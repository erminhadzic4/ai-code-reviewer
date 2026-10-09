# Decisions

Format: date, decision, why, alternatives rejected. Newest at the bottom.

## 2026-10-08: Single monorepo
Frontend and backend live in one repository (`backend/`, `frontend/`, `docs/`).
Why: one link shows the whole project, shared API contract changes in one commit,
solo project with no separate release cycles.
Rejected: separate frontend and backend repositories.

## 2026-10-08: JWT login and registration from V1
Reviews belong to users. Why: needed for history, per-user usage limits, and a possible
paid feature later. Rejected: anonymous usage in V1.

## 2026-10-08: Free-only stack
Gemini free tier and local Ollama, free GitHub Actions. Anything that cannot be run
for free is not done.

## 2026-10-08: Local only for now, public deployment optional later
The app runs locally with the developer's own Gemini key. A public version is a possible
later option (see "Later" in ROADMAP.md).
Why: the free Gemini quota is shared by every user of one key, and free hosting has limits,
so a public version needs usage limits and abuse protection first.
Rejected: building public deployment and its protections now.

## 2026-10-08: Frontend direction
The approved frontend design mockup (dark theme: landing page and review report) is the
reference for the frontend (M7).

## 2026-10-09: V1 architecture
Full reasoning and rejected alternatives are in `ARCHITECTURE.md`. The main decisions:

- **PostgreSQL confirmed.** Rejected: H2, SQLite, MySQL, MongoDB.
- **Backend packages by feature**, with controller, service and repository layers inside
  each. Rejected: packages by layer; full hexagonal architecture.
- **`reviews` and `analyses` are separate tables.** A review is a user's request and carries
  the job state; an analysis is the shared result for one commit and is the cache.
  Rejected: one table with results per user; a separate `jobs` table.
- **Job queue in the database, worker in the same process.** Rejected: in-memory `@Async`
  queue (lost on restart), a message broker, a job library.
- **A retry restarts the whole analysis** and spends quota again, at most three attempts.
  Rejected: persisting per-chunk results and resuming.
- **Repository fetched as one zip archive.** Rejected: Contents API file by file (uses up
  the unauthenticated rate limit), `git clone`.
- **Flyway migrations** with Hibernate in validate mode. Rejected: `ddl-auto=update`, Liquibase.
- **Testcontainers** for integration tests. Rejected: H2 in tests.
- **Findings have a `kind`** (`ISSUE` or `GOOD_PRACTICE`). The four report counters are
  derived from kind and severity; good practices never affect a score.
  Rejected: a separate table for good practices; deriving the level from category.
- **No focus selector in V1.** Every analysis covers security, maintainability and
  performance; the frontend only filters. Rejected: a `focus` parameter (it would multiply
  cached analyses per commit).
- **Ollama is outside V1.** Only the `CodeReviewProvider` interface keeps it possible.
