# Architecture (V1)

Scope: V1 of the roadmap (M1 to M8), a working review of a public GitHub repository,
running locally. Each decision lists the alternative that was rejected and why.

Versions: Java 21, Spring Boot 4.0.x, Maven Wrapper, PostgreSQL (pinned major version),
React 19, Vite, TypeScript.

## 1. Backend structure

Base package `com.codereviewer`, organised by feature. Inside each feature the usual
layers apply.

```
com.codereviewer
├── auth/        AuthController, AuthService, JwtService, JwtAuthFilter, dto/
├── user/        User (entity), UserRepository
├── github/      GitHubClient, RepoUrlParser, FileFilter, RepoSnapshot, SourceFile
├── provider/    CodeReviewProvider (interface), FakeReviewProvider, FindingValidator
│   └── gemini/  GeminiReviewProvider, prompt, response DTOs
├── review/      ReviewController, ReviewService, Review, Analysis, Finding,
│   │            repositories, dto/, ScoreCalculator, FindingLevel
│   └── job/     ReviewWorker (claims jobs), ReviewPipeline (steps of one review), Chunker
├── config/      SecurityConfig, OpenApiConfig, ReviewProperties (limits from application.yml)
└── common/      GlobalExceptionHandler, error types
```

```
HTTP → Controller → Service → Repository → PostgreSQL
                       │
                       ├→ GitHubClient        (external system 1)
                       └→ CodeReviewProvider  (external system 2)
```

- **Controller**: HTTP only. Accepts and returns DTOs (Java records), validates input,
  holds no business logic. Entities never leave the service layer as JSON.
- **Service**: business rules and transaction boundaries.
- **Repository**: Spring Data JPA interfaces.
- **External systems** sit behind their own classes, so tests can replace them with fakes.

| Decision | Rejected | Why |
|---|---|---|
| Package by feature | Package by layer (`controller/`, `service/`, `repository/`) | One feature would be spread over three packages; by feature, module boundaries are visible in the folder tree. |
| Layers, plus an interface only for the review provider | Full hexagonal architecture (ports and adapters everywhere) | Too much abstraction for one process. An interface is used where there really are several implementations (fake, Gemini, later Ollama). |
| DTOs separate from entities | Returning JPA entities | An entity in JSON exposes columns such as `password_hash` and ties the API to the schema. |
| Errors as RFC 9457 `ProblemDetail` | Custom error format | Standard format, built into Spring, no extra code. |
| Spring `RestClient` for GitHub and Gemini | Vendor SDK; `WebClient` | An SDK is a new dependency that hides a single REST call; `WebClient` brings in the reactive stack for no benefit. |

## 2. Data model

Four tables. The key idea is to separate a user's request from the result of an analysis:

- `reviews`: one user's request ("review this repository"). Carries the job state. Owned by a user.
- `analyses`: the result for one repository at one commit. Shared, immutable, acts as the cache.

Two requests for the same commit, from the same or different users, point to the same
analysis, so the model quota is spent once.

```
users 1 ──── N reviews N ──── 1 analyses 1 ──── N findings
```

### users

| Column | Type | Notes |
|---|---|---|
| id | BIGINT identity, PK | |
| email | VARCHAR(255), UNIQUE, NOT NULL | stored lower-case |
| password_hash | VARCHAR(100), NOT NULL | BCrypt |
| created_at | TIMESTAMPTZ, NOT NULL | |

### reviews

| Column | Type | Notes |
|---|---|---|
| id | UUID, PK | appears in URLs; not guessable |
| user_id | BIGINT, FK → users, NOT NULL | owner |
| repo_owner, repo_name | VARCHAR, NOT NULL | lower-case (GitHub names are case-insensitive) |
| branch | VARCHAR(255), NULL | default branch, filled by the worker |
| commit_sha | CHAR(40), NULL | filled by the worker |
| status | VARCHAR(20), NOT NULL | `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED` |
| stage | VARCHAR(20), NULL | `FETCHING`, `ANALYZING`, `SCORING` |
| chunks_done, chunks_total | INT | analysis progress |
| attempts | INT, NOT NULL | |
| run_after | TIMESTAMPTZ, NOT NULL | earliest start time (retry delay) |
| error_code, error_message | VARCHAR, NULL | e.g. `REPO_NOT_FOUND`, `RATE_LIMITED`, `REPO_TOO_LARGE` |
| analysis_id | BIGINT, FK → analyses, NULL | set when completed |
| cache_hit | BOOLEAN, NOT NULL | result was served from the cache |
| created_at, started_at, finished_at | TIMESTAMPTZ | |

Indexes: `(user_id, created_at DESC)` for history, `(status, run_after)` for the worker.

### analyses

| Column | Type | Notes |
|---|---|---|
| id | BIGINT identity, PK | |
| repo_owner, repo_name, commit_sha | | |
| provider, model | VARCHAR | e.g. `GEMINI` and the model name |
| prompt_version | INT | incremented when the prompt changes |
| overall_score, security_score, maintainability_score, performance_score | SMALLINT, 0 to 100 | computed by the backend |
| files_analyzed, files_skipped, findings_discarded | INT | shown in the report |
| created_at | TIMESTAMPTZ | |

`UNIQUE (repo_owner, repo_name, commit_sha, provider, model, prompt_version)` is the cache
key. Provider, model and prompt version are part of it so that changing the prompt or the
model does not serve a stale analysis.

### findings

| Column | Type | Notes |
|---|---|---|
| id | BIGINT identity, PK | |
| analysis_id | BIGINT, FK → analyses, ON DELETE CASCADE | |
| kind | VARCHAR(15), NOT NULL | `ISSUE` or `GOOD_PRACTICE` |
| category | VARCHAR(20), NOT NULL | `SECURITY`, `MAINTAINABILITY`, `PERFORMANCE` |
| severity | VARCHAR(10), NULL | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`; required for `ISSUE`, null for `GOOD_PRACTICE` |
| file_path | VARCHAR(1024), NULL | required for `ISSUE` |
| line_start, line_end | INT, NULL | required for `ISSUE`; verified against the file |
| title | VARCHAR(200), NOT NULL | |
| explanation | TEXT, NULL | why it matters |
| suggestion | TEXT, NULL | suggested fix, in prose |
| suggested_code | TEXT, NULL | replacement for lines `line_start..line_end` |
| code_snippet | TEXT, NULL | original lines plus one line of context on each side, taken from the file by the backend |
| snippet_start_line | INT, NULL | line number of the first line in `code_snippet` |

Check constraint: a row with `kind = 'ISSUE'` must have `severity`, `file_path`,
`line_start` and `line_end`; a row with `kind = 'GOOD_PRACTICE'` must have a null
`severity`. A good practice may apply to the whole repository, so its location is optional;
when present it passes the same line verification.

### Report counters

The report shows four counters. The level is not stored; one pure function
(`FindingLevel`) derives it from `kind` and `severity`, and the API returns it with every
finding and as totals.

| kind | severity | level | Counter |
|---|---|---|---|
| `ISSUE` | `CRITICAL` | `CRITICAL` | Critical |
| `ISSUE` | `HIGH` | `WARNING` | Warnings |
| `ISSUE` | `MEDIUM` | `WARNING` | Warnings |
| `ISSUE` | `LOW` | `SMELL` | Code smells |
| `GOOD_PRACTICE` | (none) | `GOOD_PRACTICE` | Good practices |

- Category does not affect the level; it is shown as its own label.
- Every issue falls into exactly one counter, so the total number of issues equals
  Critical + Warnings + Code smells. Good practices are listed separately.
- Good practices do not affect any score. Otherwise a score could be raised simply by
  listing praise.
- The same good-practice title reported for several parts of a repository is counted once.

### Scoring

Scores are computed by `ScoreCalculator`, a pure function with no database or model
access. For each category: 100 minus the sum of penalties, where a penalty depends on the
severity of an issue and is normalised by the amount of code analysed, clamped to 0 to 100.
The overall score is a weighted average of the three categories. Exact weights are fixed
and tested in M6.

| Decision | Rejected | Why |
|---|---|---|
| `reviews` and `analyses` as separate tables | One `reviews` table holding results per user | The cache would work only within one user, or findings would be copied. "The same commit is not analysed twice" would not hold. |
| Job state lives in `reviews` | Separate `jobs` table | In V1 one request is one job, so a `jobs` table would add a join and nothing else. Split it if other job types appear. |
| UUID for `reviews.id`, BIGINT elsewhere | BIGINT everywhere; UUID everywhere | Only `reviews.id` appears in URLs, where sequential numbers can be guessed. Internal keys stay small. |
| Enums as VARCHAR with CHECK constraints | PostgreSQL `ENUM` types; ordinals | Native enums are awkward to change in migrations; ordinals are unreadable and break when the Java order changes. |
| Flyway SQL migrations, Hibernate `ddl-auto=validate` | `ddl-auto=update`; Liquibase | `update` changes the schema with no history and no review. Liquibase does the same job as Flyway with more ceremony. |
| Findings that fail line verification are discarded and counted | Keeping them flagged as unverified | An unreliable finding would distort the score; the number discarded stays visible. |
| `kind` column on `findings` | Separate `good_practices` table | A good practice has the same shape as an issue (category, title, text, optional location). A second table would mean a second entity and repository for the same data. |
| Level derived from severity only | "Smell" for every maintainability issue, "Warning" for security and performance | A serious maintainability problem would be hidden as a smell and a trivial security note promoted to a warning. The level expresses urgency; the category has its own label. Cost: a minor security issue is labelled as a smell. |
| Level computed on read | Stored `level` column; computing it in the frontend | A stored copy can drift from `severity`; in the frontend the rule would exist in two places. |
| Four severity levels | Five, with `INFO` | `INFO` would have neither a counter nor a weight in the score, and fewer levels give more consistent model output. |
| No `role` column | Roles (user, admin) from the start | V1 has no admin features. |

## 3. REST API

Prefix `/api`. Everything except registration, login, the health endpoint and the API
documentation requires `Authorization: Bearer <JWT>`.

### Auth

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `POST /api/auth/register` | `{email, password}` | 201 `{accessToken, expiresIn, user}` | 400 validation, 409 email taken |
| `POST /api/auth/login` | `{email, password}` | 200 `{accessToken, expiresIn, user}` | 401 |
| `GET /api/auth/me` | | 200 `{id, email}` | 401 |

### Reviews

| Method and path | Purpose | Success | Errors |
|---|---|---|---|
| `POST /api/reviews` `{repoUrl}` | submit | 202 with `Location`, `{id, status}` | 400 invalid URL, 401 |
| `GET /api/reviews/{id}` | status (polled) | 200 `{id, repo, branch, commitSha, status, stage, chunksDone, chunksTotal, error}` | 404 |
| `GET /api/reviews/{id}/result` | report | 200 `{scores, counts, stats, findings[], goodPractices[]}` | 404, 409 if not `COMPLETED` |
| `GET /api/reviews?page=&size=` | the user's history | 200 page | 401 |

`counts` is `{critical, warnings, codeSmells, goodPractices}`. Each element of `findings[]`
carries `level`, `category`, `severity`, location, texts and the data for the diff view.

**No focus selector in V1.** `POST /api/reviews` takes only `repoUrl`. Every analysis covers
all three categories, and the frontend filters the display over findings it has already
fetched. Rejected: a `focus` parameter, because it would have to be part of the cache key,
so one commit would have several analyses and spend quota several times.

### Other

`GET /actuator/health` (Spring Boot Actuator), `/swagger-ui.html`, `/v3/api-docs`.

| Decision | Rejected | Why |
|---|---|---|
| 202 Accepted, then polling about every 2 s | Synchronous response; SSE or WebSocket | A review takes seconds to minutes, so a single request would time out. SSE and WebSocket need a long-lived connection and more code; polling is enough locally. |
| Separate status and result endpoints | One endpoint returning everything | Status is called many times and must stay small; the report is large and fetched once. |
| 404 for another user's review | 403 | 403 reveals that a review with that id exists. |
| JWT access token (HS256, secret from `JWT_SECRET`), no refresh token | Refresh tokens with rotation; server sessions | Refresh tokens add a table and a flow that a local app does not need. Sessions depart from the agreed stack. |
| `jjwt` with a custom filter | `spring-security-oauth2-resource-server` | The token handling stays explicit and small; the resource server adds more concepts for the same result. Confirmed again when M1 is planned. |
| Token kept in `localStorage` | httpOnly cookie | A cookie requires CSRF protection. Acceptable for local use; revisit before any public deployment. |
| No version segment in paths | `/api/v1/...` | The only client is the frontend in the same repository, changed in the same commit. |

## 4. Flow of one review

```
Browser            Backend (HTTP)        Database      Worker             GitHub     Model
   │ POST /api/reviews  │                   │              │                 │          │
   ├───────────────────►│ validate URL      │              │                 │          │
   │                    ├─ INSERT QUEUED ──►│              │                 │          │
   │◄── 202 {id} ───────┤                   │              │                 │          │
   │                    │                   │◄─ claim ─────┤                 │          │
   │                    │                   │   RUNNING    ├─ repo, HEAD ───►│          │
   │ GET /reviews/{id}  │                   │◄─ cache? ────┤                 │          │
   ├───────────────────►│ read status ─────►│              ├─ zipball ──────►│          │
   │◄── RUNNING ────────┤                   │              ├─ filter, chunk  │          │
   │      (repeats)     │                   │              ├─ review(chunk) ───────────►│
   │                    │                   │              │◄─ JSON findings ───────────┤
   │                    │                   │              ├─ validate, verify lines    │
   │                    │                   │◄─ analysis ──┤  score                     │
   │                    │                   │   COMPLETED  │                 │          │
   │ GET /{id}/result   │                   │              │                 │          │
   ├───────────────────►│ ─────────────────►│              │                 │          │
   │◄── report ─────────┤                   │              │                 │          │
```

1. **Submit.** `RepoUrlParser` accepts only `https://github.com/{owner}/{repo}` (with an
   optional `.git` suffix or trailing slash). The backend never requests a user-supplied
   URL; it extracts `owner` and `repo` and builds GitHub API addresses itself. A `QUEUED`
   row is inserted and 202 is returned.
2. **Claim.** `ReviewWorker` polls about once a second for the oldest `QUEUED` row whose
   `run_after` has passed (`SELECT ... FOR UPDATE SKIP LOCKED`) and marks it `RUNNING`.
3. **Commit and cache.** GitHub returns the default branch, the repository size (oversized
   repositories are rejected here) and the SHA of the latest commit. If an analysis exists
   for that cache key, the review is linked to it and completed with `cache_hit = true`.
4. **Fetch.** The repository at that SHA is downloaded as one zip archive and read as a
   stream, with limits on download size, file count and total uncompressed size.
   `FileFilter` drops `node_modules`, `target`, `dist`, binaries, lock files, minified and
   generated code, and oversized files.
5. **Analyse.** `Chunker` groups files into chunks by a character budget. Every line is
   prefixed with its number so the model can cite lines precisely. For each chunk,
   `CodeReviewProvider.review(chunk)` returns findings as JSON matching a fixed schema.
6. **Validate.** The JSON is read into Java records and validated (required fields, allowed
   values). Then lines are verified: the file must belong to the chunk and
   `1 ≤ line_start ≤ line_end ≤ number of lines in the file`. Anything that fails is
   discarded and counted.
7. **Score and store.** `ScoreCalculator` computes the scores; the analysis and its findings
   are saved in one transaction; the review becomes `COMPLETED`.
8. **Failure.** A transient error (429, 5xx, timeout) increments `attempts`, sets a later
   `run_after` and returns the row to `QUEUED`, at most three attempts. A permanent error
   (repository missing, private or too large) sets `FAILED` with an `error_code` at once.
   On startup, rows left in `RUNNING` are returned to `QUEUED`.
9. **Frontend.** Polls the status every 2 s; on `COMPLETED` it fetches the report, on
   `FAILED` it shows the error.

### Retries restart the whole analysis

A deliberate V1 trade-off. There are two levels of retry:

- **Within a job.** A single HTTP call to GitHub or the model that returns 429 or 5xx is
  repeated after a delay, honouring `Retry-After`. Chunks already finished are not repeated.
- **The whole job.** If the job still fails, the next attempt starts from scratch: it
  downloads the archive again and sends every chunk to the model again. Chunk results from
  the failed attempt are not kept, so their quota is spent again. Worst case: three
  attempts cost three times the quota of one review.

This is acceptable because repository size is capped (few chunks per review), most
transient errors are absorbed by the in-job delay, and attempts are limited to three.
Revisit when large repositories or a public deployment with a daily budget arrive.

### Provider interface

```java
public interface CodeReviewProvider {
    String name();
    String model();
    List<RawFinding> review(ReviewChunk chunk);
}
```

Selected with `REVIEW_PROVIDER=fake|gemini`. The default is `fake`, so the app works
without an API key. Ollama is outside V1; the interface keeps it possible.

| Decision | Rejected | Why |
|---|---|---|
| Job queue in the database, worker in the same process | `@Async` with an in-memory queue | An in-memory queue is lost on restart, leaving rows `QUEUED` forever. With the database as the source of truth, jobs survive restarts and retries come almost for free. |
| | RabbitMQ, Redis or Kafka | Another service to run, with no benefit for a single process. |
| | A job library (JobRunr, db-scheduler) | A new dependency for a mechanism that is small and worth keeping visible. |
| A retry repeats the whole analysis | Persisting per-chunk results (an `analysis_chunks` table) and resuming from the first unfinished chunk | Needs a new table, cleanup of half-finished analyses, and a guarantee that a repository is split into exactly the same chunks every time. `analyses` would also stop being all-or-nothing. Too much machinery for a rare case with small repositories. |
| One job at a time (configurable) | Parallel jobs | The free model tier limits requests per minute; parallel jobs would starve each other. |
| No open transaction during HTTP calls | The whole job in one `@Transactional` method | A transaction would hold a database connection for minutes. Three short transactions instead: claim, update progress, save result. |
| Commit SHA resolved by the worker | Resolved in `POST /api/reviews` | Submit stays fast and independent of GitHub. Cost: a missing repository is reported on the progress screen, not at submit. |
| One zip archive download | Contents API, file by file | Without a token GitHub allows 60 requests an hour; a 50-file repository would use them at once. The archive needs two requests in total and is read with JDK classes. |
| | `git clone` | Requires `git` in the container and temporary directory management. |
| Validation with Jackson and Bean Validation | A JSON Schema library | The model already receives the response schema; a second check in Java is enough without a new dependency. |

Repository content is untrusted input: a file may contain text aimed at the model. The
defences are the fixed response schema, scores computed by the backend, line verification,
and rendering finding text as text, never as HTML.

An optional `GITHUB_TOKEN` raises the GitHub limit from 60 to 5000 requests an hour.

## 5. Database

PostgreSQL, running in Docker so nothing is installed on the host.

| Alternative | Why rejected |
|---|---|
| H2 (embedded) | No setup, but its SQL differs from PostgreSQL, so migrations and `SKIP LOCKED` would not be exercised on the real engine. |
| SQLite | One writer at a time (the worker and HTTP requests write concurrently), and Hibernate support relies on a community dialect. |
| MySQL | Would work. PostgreSQL has better free hosting options if the project is ever published. |
| MongoDB | The data is relational (user, review, analysis, findings) with filtering and aggregation, and the stack is built on JPA. |

Integration tests use Testcontainers with a real PostgreSQL. Rejected: H2 in tests, which
would test a different database from the one that runs. Cost: Docker must be running
during tests and they take a few seconds longer. Pure unit tests (scoring, file filter,
URL parser, validator) need no database.

## 6. Running locally

```
cp .env.example .env      # once; add a Gemini API key if you have one
docker compose up --build
```

| Service | What it is | Port |
|---|---|---|
| `db` | `postgres` (pinned version), named volume, health check | internal |
| `backend` | multi-stage Dockerfile: Maven build, then a JRE 21 image; waits for `db` to be healthy | 8080 |
| `frontend` | multi-stage Dockerfile: `npm run build`, then nginx serving static files and proxying `/api` to the backend | 3000 |

The browser talks only to `http://localhost:3000`, so no CORS configuration is needed.

`.env.example` (placeholders only; `.env` is git-ignored):

```
POSTGRES_DB=codereviewer
POSTGRES_USER=codereviewer
POSTGRES_PASSWORD=change-me
JWT_SECRET=change-me-to-a-long-random-string
REVIEW_PROVIDER=fake
GEMINI_API_KEY=
GEMINI_MODEL=
GITHUB_TOKEN=
```

Development mode, for faster feedback: `docker compose up db`, then `./mvnw spring-boot:run`
and `npm run dev` (Vite proxies `/api` to port 8080).

The `db` service and `.env.example` arrive in M1 with the first table; the `backend` and
`frontend` containers arrive in M8.

| Decision | Rejected | Why |
|---|---|---|
| Docker Compose with three services | Everything installed on the host | Would require installing PostgreSQL and Maven and starting three things by hand. |
| nginx serves the frontend and proxies `/api` | Spring Boot serving the frontend's static files | One container fewer, but it couples two builds into one. |
| Ollama not in the compose file | An Ollama service by default | The image and a model take several gigabytes, and V1 has no Ollama milestone. |

## 7. Frontend notes

The frontend follows the approved frontend design mockup (dark theme: landing page and
review report). Colours, fonts and spacing live in one file of CSS variables
(`frontend/src/styles/tokens.css`); components use CSS modules.

- The landing form requires a signed-in user. A visitor who is not signed in is sent to
  login and the URL they entered is kept.
- The short verdict next to the overall score is chosen from fixed templates by score and
  counters; it is not written by the model.
- The report's filters (all, critical, warnings, smells) work on data already in the browser.

Out of scope for V1: exporting a report (planned for V2), dismissing individual findings
(needs per-user state on a shared analysis), and forcing a fresh analysis of a commit that
is already cached. Re-running a review submits the same URL again: a new commit produces a
new analysis, the same commit is served from the cache.
