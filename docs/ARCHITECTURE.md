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
├── github/      GitHubClient, RepoUrlParser, ArchiveReader, FileFilter, RepoSnapshot, SourceFile,
│                GitHubProperties (limits and timeouts), GitHubException
├── provider/    CodeReviewProvider (interface), ProviderLimits, ReviewChunk, RawFinding,
│   │            FakeReviewProvider, FindingValidator (gives VerifiedFinding and ValidationResult),
│   │            NumberedSource, ProviderException, ProviderErrorCode, FindingKind,
│   │            FindingCategory, Severity, ProviderType
│   └── gemini/  GeminiReviewProvider, GeminiPrompt (instructions, schema, version),
│                GeminiProperties (key, model, timeouts, limits, pacing)
├── review/      ReviewController, ReviewService, Review (with its state transitions), Analysis,
│   │            Finding, repositories, dto/, ScoreCalculator, Scores, FindingLevel, ReviewStatus,
│   │            ReviewStage
│   └── job/     ReviewWorkerSchedule (the timer), ReviewWorker (runs one review at a time and
│                decides what a failure means), ReviewPipeline (steps of one review),
│                ReviewJobStore (every write of a job, each in its own transaction), Chunker,
│                ChunkAnalyzer (sends chunks, repeats and splits them; gives ChunkAnalysis),
│                RequestPacer, RetryPolicy, JobFailure, ReviewErrorCode, Sleeper
├── config/      SecurityConfig, OpenApiConfig, ClockConfig, ReviewProperties (settings of the
│                review process: chunk budget, attempts, worker; the GitHub client's limits are
│                in github/GitHubProperties)
└── common/      GlobalExceptionHandler, error types, Origins (check of configured addresses)
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
- **Dependencies between features point one way:** `review` → `provider` → `github`. The
  enums that describe a finding live in `provider`, because both the provider's output and
  the stored finding use them.

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
| repo_owner, repo_name | VARCHAR(39), VARCHAR(100), NOT NULL | lower-case (GitHub names are case-insensitive) |
| branch | VARCHAR(255), NULL | default branch, filled by the worker |
| commit_sha | CHAR(40), NULL | filled by the worker |
| status | VARCHAR(20), NOT NULL | `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED` |
| stage | VARCHAR(20), NULL | `FETCHING`, `ANALYZING`, `SCORING` |
| chunks_done, chunks_total | INT, NOT NULL | analysis progress; 0 of 0 until the chunks are known |
| attempts | INT, NOT NULL | |
| run_after | TIMESTAMPTZ, NOT NULL | earliest start time (retry delay) |
| error_code, error_message | VARCHAR(40), VARCHAR(500), NULL | e.g. `REPO_NOT_FOUND`, `RATE_LIMITED`, `REPO_TOO_LARGE` |
| analysis_id | BIGINT, FK → analyses, NULL | set when completed |
| cache_hit | BOOLEAN, NOT NULL | result was served from the cache |
| created_at, started_at, finished_at | TIMESTAMPTZ | |

Indexes: `(user_id, created_at DESC)` for history, `(status, run_after)` for the worker.

Check constraints: a review has an analysis exactly when its status is `COMPLETED`; a
`FAILED` review has an `error_code`; `0 ≤ chunks_done ≤ chunks_total`; `commit_sha`, when
set, is 40 lower-case hex characters. Neither foreign key has an `ON DELETE` action, so a
user or an analysis that a review points to cannot be deleted.

### analyses

| Column | Type | Notes |
|---|---|---|
| id | BIGINT identity, PK | |
| repo_owner, repo_name, commit_sha | VARCHAR(39), VARCHAR(100), CHAR(40), NOT NULL | same rules as in `reviews` |
| provider, model | VARCHAR | e.g. `GEMINI` and the model name |
| prompt_version | INT | incremented when the prompt changes |
| overall_score, security_score, maintainability_score, performance_score | SMALLINT, 0 to 100 | computed by the backend |
| files_analyzed, files_skipped, findings_discarded | INT | shown in the report |
| files_failed | INT, default 0 | files the model could not review; above 0 the analysis is partial |
| lines_analyzed | INT | lines of the reviewed files; the second input of the score formula |
| created_at | TIMESTAMPTZ | |

`UNIQUE (repo_owner, repo_name, commit_sha, provider, model, prompt_version)` is the cache
key. Provider, model and prompt version are part of it so that changing the prompt or the
model does not serve a stale analysis.

`files_failed` and `lines_analyzed` are on the analysis and not on the review because they
describe the result, which every review of the commit shares: a review served from the cache
never reads the files and could not compute them. `files_skipped` counts files the file
filter left out before the review; `files_failed` counts files that were sent and could not
be reviewed.

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
when present it passes the same line verification. For every finding, lines come as a pair
inside a file with `1 ≤ line_start ≤ line_end`, and `code_snippet` and `snippet_start_line`
are set together.

Index: `(analysis_id)`, because PostgreSQL does not index a foreign key by itself and the
report reads findings by analysis.

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
access. Its input is the findings that passed validation and the number of lines of the
files that were actually reviewed. That number is stored with the scores as
`lines_analyzed`, so a score can be checked from the findings and the analysis alone.

```
penalty(category) = sum of the points of every ISSUE in that category
lines             = max(lines analysed, 1000)
score(category)   = max(0, round(100 - penalty * 1000 / lines))
overall           = round(0.40 * security + 0.35 * maintainability + 0.25 * performance)
```

| Severity | `CRITICAL` | `HIGH` | `MEDIUM` | `LOW` |
|---|---|---|---|---|
| Points per 1000 lines | 30 | 15 | 6 | 3 |

- The penalty is linear in the density of issues: the same issues weigh half as much in
  twice as much code.
- A line is a line of `SourceFile.lines()`, blank lines and comments included.
- Less than 1000 lines is scored as 1000 lines, so in a small repository one issue costs
  exactly the points in the table.
- Rounding is half up. The overall score is computed from the rounded category scores, so
  it can be checked from the numbers on the screen.
- The numbers are constants in the code, not configuration.

| Repository | Issues | Security | Maintainability | Performance | Overall |
|---|---|---|---|---|---|
| 2000 lines | security: 1 critical, 1 high; maintainability: 3 medium, 4 low; performance: 1 medium | 78 | 85 | 97 | 85 |
| 300 lines | maintainability: 1 low | 100 | 97 | 100 | 99 |
| 300 lines | security: 1 critical | 70 | 100 | 100 | 88 |
| 6000 lines | security: 15 critical | 25 | 100 | 100 | 70 |
| any | none, or only good practices | 100 | 100 | 100 | 100 |

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
| `POST /api/auth/login` | `{email, password}` | 200 `{accessToken, expiresIn, user}` | 400 empty field, 401 |
| `GET /api/auth/me` | | 200 `{id, email}` | 401 |

`expiresIn` is in seconds. A wrong password and an unknown email give the same 401, so
login does not reveal which emails are registered. The access token is valid for 24 hours
and carries only the user id; the application does not start without a `JWT_SECRET` of at
least 32 bytes.

### Reviews

| Method and path | Purpose | Success | Errors |
|---|---|---|---|
| `POST /api/reviews` `{repoUrl}` | submit | 202 with `Location: /api/reviews/{id}` and the status of the new review | 400 invalid URL or empty field, 401 |
| `GET /api/reviews/{id}` | status (polled) | 200 status | 400 if the id is not a UUID, 401, 404 |
| `GET /api/reviews/{id}/result` | report | 200 `{scores, counts, stats, findings[], goodPractices[]}` | 400, 401, 404, 409 if not `COMPLETED` |
| `GET /api/reviews?page=&size=` | the user's history, newest first; `page` from 0, `size` 20 by default and at most 50 | 200 `{items[], page, size, totalItems}` | 400 page below 0 or size below 1, 401 |

The status:

```json
{ "id": "…", "repo": "owner/name", "branch": "main", "commitSha": "…",
  "status": "RUNNING", "stage": "ANALYZING", "chunksDone": 2, "chunksTotal": 5,
  "attempt": 1, "maxAttempts": 3, "retryAt": null, "cacheHit": false,
  "createdAt": "…", "startedAt": "…", "finishedAt": null,
  "error": null }
```

- `repo` is the name as it was submitted; `branch` and `commitSha` are null until the job
  has asked GitHub.
- `stage` is set only while the review is `RUNNING`. `chunksTotal` grows by one when a chunk
  has to be split.
- `attempt` counts the starts of the review. `retryAt` is set only while the review is
  `QUEUED` and waits for a time that is still ahead, after a failure that can pass. A new
  review, and one put back into the queue after a restart, have none.
- `error` is `{code, message}` when the review is `FAILED`, and when it is `QUEUED` after a
  failed attempt (the reason of that attempt, kept until the next one starts); otherwise
  null. The code is one of `GitHubErrorCode`, `ProviderErrorCode` or `ReviewErrorCode`.

A history item is `{id, repo, branch, commitSha, status, overallScore, createdAt,
finishedAt}`; `overallScore` is set only for a completed review.

In the report, `counts` is `{critical, warnings, codeSmells, goodPractices}` and `stats` is
`{filesAnalyzed, filesSkipped, filesFailed, linesAnalyzed, findingsDiscarded}`; a report with
`filesFailed` above 0 is partial. Each element of `findings[]` and `goodPractices[]` carries
`id`, `level`, `category`, `severity`, location, texts and the data for the diff view, in the
order the findings were stored. Sorting and filtering are the frontend's.

A review of another user is answered exactly like one that does not exist. A token whose
user no longer exists gets 401 when it submits a review.

**No focus selector in V1.** `POST /api/reviews` takes only `repoUrl`. Every analysis covers
all three categories, and the frontend filters the display over findings it has already
fetched. Rejected: a `focus` parameter, because it would have to be part of the cache key,
so one commit would have several analyses and spend quota several times.

### Other

`GET /actuator/health` (Spring Boot Actuator), `/swagger-ui.html`, `/v3/api-docs`.

The API documentation is generated from the controllers by springdoc and served by the
backend at `http://localhost:8080/swagger-ui.html`. The description and the viewer are
public; every endpoint they describe still needs a token, except registration and login.
To call a protected endpoint from the viewer:

1. Open `POST /api/auth/login`, choose "Try it out", send an email and password, and copy
   `accessToken` from the answer.
2. Choose "Authorize" at the top of the page and paste the token alone, without `Bearer `.
3. Endpoints marked with a lock now send `Authorization: Bearer <token>`.

| Decision | Rejected | Why |
|---|---|---|
| 202 Accepted, then polling about every 2 s | Synchronous response; SSE or WebSocket | A review takes seconds to minutes, so a single request would time out. SSE and WebSocket need a long-lived connection and more code; polling is enough locally. |
| Separate status and result endpoints | One endpoint returning everything | Status is called many times and must stay small; the report is large and fetched once. |
| 404 for another user's review | 403 | 403 reveals that a review with that id exists. |
| JWT access token (HS256, secret from `JWT_SECRET`), no refresh token | Refresh tokens with rotation; server sessions | Refresh tokens add a table and a flow that a local app does not need. Sessions depart from the agreed stack. |
| `jjwt` with a custom filter | `spring-security-oauth2-resource-server` | The token handling stays explicit and small; the resource server adds more concepts for the same result. Confirmed in M1. |
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

1. **Submit.** `RepoUrlParser` accepts only `github.com/{owner}/{repo}`: with `https://`,
   `http://` or no scheme, an optional `www.`, an optional `.git` suffix and an optional
   trailing slash. Anything else is rejected, including links to a branch or a file. The
   backend never requests a user-supplied URL; it extracts `owner` and `repo` and builds
   GitHub API addresses itself. A `QUEUED` row is inserted and 202 is returned.
2. **Claim.** `ReviewWorker` is called about once a second (`app.review.worker.poll-interval`)
   by a scheduler with a single thread. It takes the oldest `QUEUED` row whose `run_after`
   has passed (`SELECT ... FOR UPDATE SKIP LOCKED`), marks it `RUNNING`, counts the attempt
   and runs the whole review on that thread; the next round starts only when it has returned,
   so one review runs at a time. A review submitted meanwhile waits in the queue.
3. **Commit and cache.** `GitHubClient.resolveHead` makes two API requests: one for the
   default branch and the reported repository size, one for the SHA of the latest commit.
   The reported size is only a coarse guard (see the limits below). If an analysis exists
   for that cache key, the review is linked to it and completed with `cache_hit = true`. The
   key uses the name GitHub reports for the repository, so a renamed repository leads to the
   same analysis under both names.
4. **Fetch.** `GitHubClient.fetchSnapshot` makes a third API request, which redirects to
   the archive host. The repository at that SHA is downloaded as one zip archive and read
   as a stream by `ArchiveReader`; nothing is written to disk. `FileFilter` drops dependency
   and build directories, binaries, lock files, documentation, minified and generated code,
   and oversized files. The result lists the files kept and counts the files skipped by
   reason.

   | Limit (`app.github.*`) | Default | When crossed |
   |---|---|---|
   | `max-repo-size`, as reported by GitHub | 1 GB | review fails, `REPO_TOO_LARGE` |
   | `max-download-size`, compressed bytes | 50 MB | review fails, `REPO_TOO_LARGE` |
   | `max-uncompressed-size`, skipped entries included | 200 MB | review fails, `REPO_TOO_LARGE` |
   | `max-entries` in the archive | 20 000 | review fails, `REPO_TOO_LARGE` |
   | `max-file-size` of one file | 200 KB | the file is skipped |
   | `max-files` kept for review | 500 | review fails, `REPO_TOO_LARGE` |
   | `max-source-size` of the files kept | 2 MB | review fails, `REPO_TOO_LARGE` |

   If no file is left to review, the review fails with `NO_REVIEWABLE_FILES`; the model is
   not asked and no analysis is stored.

5. **Analyse.** `Chunker` groups files into chunks by a character budget
   (`app.review.chunk-budget`, 200 000). Files keep their order and are never split; a file
   that alone costs more than the budget is a chunk of its own. A chunk holds whole, unchanged
   files. For each chunk, `CodeReviewProvider.review(chunk)` returns the findings as
   reported. The text a model sees is made by `NumberedSource`: every line is prefixed with
   its number so the model can cite lines precisely, and the cost of a file in a chunk is the
   length of that text.

   `ChunkAnalyzer` sends the chunks one after another. Before every request `RequestPacer`
   waits until the request fits the limits the provider states (`limits()`): a sliding window
   of 60 seconds over the number of requests and the number of characters. A chunk whose
   answer was cut off or whose content the model refused is sent once more as two halves,
   split by the number of files; files that still cannot be reviewed are counted as failed
   and the analysis goes on without them. After every chunk the progress is stored.
6. **Validate.** `FindingValidator` checks every reported finding: required fields, allowed
   values, lengths that fit the columns, the shape of its kind, and its location. The file
   must be a file of the chunk, under exactly the path that was sent, and
   `1 ≤ line_start ≤ line_end ≤ number of lines in the file`, over at most 100 lines.
   Anything that fails is discarded and counted, never repaired. This is the only place a
   finding is dropped, so the sum of its counts over the chunks is `findings_discarded`. For
   an accepted finding with lines it cuts the snippet out of the file: the lines covered
   plus one line of context on each side.

   A line is defined once, in `SourceFile.lines()`: it ends at `\n`, `\r\n` or `\r`, and a
   break at the very end of a file does not start another line. The numbers shown to a
   model, the check of a reported line and the snippet all use it.
7. **Score and store.** Of several good practices with the same title only the first is
   kept: a model sees one chunk at a time and praises each for the same thing.
   `ScoreCalculator` computes the scores from the findings and the lines of the files that
   were reviewed; the analysis and its findings are saved in one transaction; the review
   becomes `COMPLETED`. If some files could not be reviewed, the analysis says how many
   (`files_failed`) and is stored and cached like any other. If no chunk at all could be
   reviewed, the review fails.
8. **Failure.** A transient error (a rate limit, 5xx, a timeout or a dropped connection)
   returns the row to `QUEUED` with a later `run_after`: one minute after the first attempt,
   five after the second, or the time the service gave, at most an hour ahead. After three
   attempts (`app.review.max-attempts`) the review is `FAILED` with the last error. A
   permanent error (repository missing, private, blocked, empty or too large; an archive that
   cannot be trusted; a rejected access token; the model's daily quota; nothing to review)
   sets `FAILED` with an `error_code` at once. For GitHub the codes are those of
   `GitHubErrorCode`, and each says whether it is transient: `RATE_LIMITED` and
   `GITHUB_UNAVAILABLE` are, while `REPO_NOT_FOUND`, `REPO_UNAVAILABLE`, `REPO_EMPTY`,
   `REPO_TOO_LARGE`, `ARCHIVE_INVALID` and `GITHUB_AUTH_FAILED` are not. For a provider the
   codes are those of `ProviderErrorCode` (see "Gemini provider" below). The review itself
   adds `NO_REVIEWABLE_FILES`, `REVIEW_INTERRUPTED` and `INTERNAL_ERROR` (`ReviewErrorCode`),
   none of them transient. A mistake in the application is `INTERNAL_ERROR` with a generic
   message; its details go to the log only.

   **Stops and restarts.** When the application shuts down while a review runs, nothing is
   written: the row stays `RUNNING`. The first round of the worker after a start returns such
   rows to `QUEUED`, due at once; a row that has used all its attempts becomes `FAILED` with
   `REVIEW_INTERRUPTED`. The same recovery runs in the round after one that ended with an
   unhandled exception, for example when the database could not be reached while the end of
   a review was being written. Everything runs on one thread, so when a round begins no
   review is really running and a row marked `RUNNING` is a leftover. This assumes a single
   instance of the application.
9. **Frontend.** Polls the status every 2 s; on `COMPLETED` it fetches the report, on
   `FAILED` it shows the error.

### Retries restart the whole analysis

A deliberate V1 trade-off. There are two levels of retry:

- **Within a job.** Only a request to the model is repeated: after a rate limit, a server
  error or an answer that is not the agreed JSON, the same chunk is sent again, at most three
  sendings, after 5 and then 20 seconds or the time the provider gave. Chunks already
  finished are not repeated. If the provider asks for more than 60 seconds, the job does not
  sleep; the failure goes to the next level.
- **The whole job.** If the job still fails, the next attempt starts from scratch: it
  downloads the archive again and sends every chunk to the model again. Chunk results from
  the failed attempt are not kept, so their quota is spent again. Worst case: three
  attempts cost three times the quota of one review.

A failure of GitHub is handled at the second level only. `GitHubClient` never retries and
never waits: it reports a failure with a code, a flag that says whether trying again can
help, and the time GitHub gave for the limit to reset. GitHub is asked before the first
chunk, so starting over costs no quota of the model, and its hourly limit can take up to an
hour to reset; postponing the review through `run_after` leaves the worker free for other
reviews, where a wait inside the job would block the queue.

This is acceptable because repository size is capped (few chunks per review), most
transient errors of the model are absorbed by the in-job delay, and attempts are limited to
three. Revisit when large repositories or a public deployment with a daily budget arrive.

### Provider interface

```java
public interface CodeReviewProvider {
    String name();
    String model();
    int promptVersion();
    ProviderLimits limits();
    List<RawFinding> review(ReviewChunk chunk);
}
```

`name()`, `model()` and `promptVersion()` are the provider's part of the cache key of an
analysis. The prompt version is a constant in the code next to the instructions, so it
changes in the same commit as they do.

`limits()` says how many requests and how many characters the provider accepts in a minute.
A provider never waits by itself; the job paces its requests to these limits. A limit
belongs to a provider and its model, so each provider states its own: the fake has none.

A `ReviewChunk` is a list of whole files. A `RawFinding` is a finding as reported: kind,
category, severity, file path, lines, title and texts, none of it checked yet. The snippet
shown in the report is not part of it; the backend takes it from the file.

Selected with `REVIEW_PROVIDER=fake|gemini`. The default is `fake`, so the app works
without an API key. A value that names no provider stops the startup, and so does `gemini`
without `GEMINI_API_KEY`. Ollama is outside V1; the interface keeps it possible.

The fake provider is deterministic: the file at position `i` of a chunk gets one of four
fixed issues (`i` modulo 4, one per severity) on its first lines, and each chunk gets one
good practice. Its findings point at real files and lines, so they pass line verification.

### Gemini provider

One chunk is one `POST /v1beta/interactions` (the Interactions API). The request carries the
model, fixed instructions, the numbered source of the chunk, the JSON schema of the answer
(`response_format`), `store: false` so that the API keeps nothing, and the output limit. The
key travels in the `x-goog-api-key` header, only to the configured origin, and never appears
in an address, a log line or an error message. Redirects are not followed.

The answer must be `{"findings": [...]}` with at most 15 findings, each with the fields of a
`RawFinding`; every field is required and the ones that may be absent are nullable. The
schema has no place for a score or a snippet. Lengths are not part of it (the API has no
keyword for them): the instructions ask for short texts and the validator enforces the
limits. The longest answer the validator accepts stays below the output limit of 32 768
tokens.

Defaults: model `gemini-3.5-flash-lite`, request timeout 120 s. A full chunk of Java source
measured about 71 000 input tokens (2.8 characters per token), so three full chunks a minute
fit the free limit of 250 000 tokens a minute. The job paces its requests to 12 requests and
600 000 characters a minute (`app.gemini.requests-per-minute`, `characters-per-minute`),
below the free limits of 15 requests and 250 000 tokens: 600 000 characters are three full
chunks, about 214 000 tokens. A review of up to three chunks is not slowed down at all; a
larger one proceeds at about three chunks a minute. The daily limit of 500 requests is not
counted locally; when the API reports it, the review fails at once.

The provider never retries and never waits. A failure is a `ProviderException` with one of
these codes:

| Code | When | Transient | HTTP |
|---|---|---|---|
| `PROVIDER_RATE_LIMITED` | 429 | yes | 503 |
| `PROVIDER_QUOTA_EXHAUSTED` | 429 with `quota_exceeded` (the daily quota) | no | 503 |
| `PROVIDER_UNAVAILABLE` | 408, 5xx, a redirect, a timeout, a dropped connection, an unreadable answer, an interaction that did not complete | yes | 502 |
| `PROVIDER_AUTH_FAILED` | 401, 403, or a body that names an invalid key (the API answers 400 for one) | no | 502 |
| `PROVIDER_MISCONFIGURED` | any other 4xx, 501: unknown model, invalid request | no | 502 |
| `PROVIDER_BLOCKED` | the API refused the content (`safety`, `recitation` and similar) | no | 422 |
| `PROVIDER_OUTPUT_TRUNCATED` | interaction status `incomplete`: the answer hit the output limit; nothing of it is used | no | 502 |
| `PROVIDER_INVALID_RESPONSE` | completed, but the text is not the agreed JSON, or the answer is too large | yes | 502 |

The daily quota is not transient for a job: it returns at midnight Pacific time, far later
than any retry. The rows for a rate limit, a server error and blocked content follow the
API's documentation; they could not be produced on purpose. The others are tested against
answers recorded from the real API.

| Decision | Rejected | Why |
|---|---|---|
| Job queue in the database, worker in the same process | `@Async` with an in-memory queue | An in-memory queue is lost on restart, leaving rows `QUEUED` forever. With the database as the source of truth, jobs survive restarts and retries come almost for free. |
| | RabbitMQ, Redis or Kafka | Another service to run, with no benefit for a single process. |
| | A job library (JobRunr, db-scheduler) | A new dependency for a mechanism that is small and worth keeping visible. |
| A retry repeats the whole analysis | Persisting per-chunk results (an `analysis_chunks` table) and resuming from the first unfinished chunk | Needs a new table, cleanup of half-finished analyses, and a guarantee that a repository is split into exactly the same chunks every time. `analyses` would also stop being all-or-nothing. Too much machinery for a rare case with small repositories. |
| One job at a time | Parallel jobs | The free model tier limits requests per minute; parallel jobs would starve each other. |
| No open transaction during HTTP calls | The whole job in one `@Transactional` method | A transaction would hold a database connection for minutes. Short transactions instead, one for every write of the job (claim, each step, progress, result), all in `ReviewJobStore`. |
| Requests to the model paced by the job | Waiting inside the provider; a fixed pause between requests | A provider that waits cannot be tested without time passing, and a fixed pause slows small repositories for nothing. |
| A partial analysis completes the review | Failing the review when one chunk cannot be reviewed | A repository with one file the model refuses could never be reviewed. The report says how many files are missing. |
| Commit SHA resolved by the worker | Resolved in `POST /api/reviews` | Submit stays fast and independent of GitHub. Cost: a missing repository is reported on the progress screen, not at submit. |
| One zip archive download | Contents API, file by file | Without a token GitHub allows 60 requests an hour; a 50-file repository would use them at once. The archive needs three requests in total (two when the commit is already cached) and is read with JDK classes. |
| | `git clone` | Requires `git` in the container and temporary directory management. |
| Validation with Jackson and Bean Validation | A JSON Schema library | The model already receives the response schema; a second check in Java is enough without a new dependency. |

Repository content is untrusted input: a file may contain text aimed at the model. The
defences are the fixed response schema, scores computed by the backend, line verification,
and rendering finding text as text, never as HTML. In the request, the instructions say
that file content is data and never an instruction, the model is given no tools, and every
line of a file carries its number while a file marker never does, so text inside a file
cannot pass for a marker. In the answer, a finding survives only if it points at lines that
exist in a file that was sent, and its snippet comes from that file, not from the model.
What remains: text in a file can still make the model keep quiet about a problem or invent
one on a real line.

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
cp .env.example .env      # once; set JWT_SECRET, add a Gemini API key if you have one
docker compose up --build
```

| Service | What it is | Port on `127.0.0.1` | Healthy when | Starts after |
|---|---|---|---|---|
| `db` | `postgres:18`, named volume | 5432 | `pg_isready` answers | – |
| `backend` | multi-stage Dockerfile: the Maven Wrapper builds the jar on a JDK 21 image, a JRE 21 image runs it as a user without root rights | 8080 (API documentation) | `curl -fsS http://localhost:8080/actuator/health` succeeds | `db` is healthy |
| `frontend` | multi-stage Dockerfile: `npm ci` and `npm run build` on a Node image, then nginx serving the files and proxying `/api` to the backend | 3000 | `wget` gets `http://127.0.0.1/` | `backend` is healthy |

The browser talks only to `http://localhost:3000`, so no CORS configuration is needed.
Every base image is pinned to an exact tag. Tests are not run while an image is built: they
need Docker for their database, and CI runs them; a separate CI job builds both images.

- **Migrations** run when the backend starts (Flyway, as on the host), before it accepts a
  request, so the health check passes only after them.
- **Variables:** Compose reads `.env` next to `docker-compose.yml` and gives each service an
  explicit list. The backend gets the database settings (with `DB_HOST=db`), `JWT_SECRET`,
  `GITHUB_TOKEN`, `REVIEW_PROVIDER`, `GEMINI_API_KEY` and `GEMINI_MODEL`; the frontend gets
  none. `JWT_SECRET` is required: Compose refuses an empty value, and the backend refuses the
  example value. A variable set in the shell or in the operating system's environment takes
  precedence over `.env`, both for Compose and for a backend started on the host.
- **nginx** serves the built files, sends any other address to `index.html` so the router
  can handle it, caches the hashed files under `/assets/` for a year and answers 404 for a
  missing one. It looks up the address of `backend` per request through Docker's DNS, so it
  starts without the backend and follows a recreated container.

`.env.example` (placeholders only; `.env` is git-ignored). The file lists only variables
that are in use, so each one is added by the milestone that introduces it; the complete
list for V1 is:

```
POSTGRES_DB=codereviewer
POSTGRES_USER=codereviewer
POSTGRES_PASSWORD=change-me
JWT_SECRET=change-me-to-a-long-random-string
REVIEW_PROVIDER=fake
GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.5-flash-lite
GITHUB_TOKEN=
```

Development mode, for faster feedback: `docker compose up db`, then `./mvnw spring-boot:run`
and `npm run dev` (Vite proxies `/api` to port 8080). The database port is published on
`127.0.0.1` for this mode, and a backend started from `backend/` reads the `.env` file in
the repository root, so the same file configures both the database and the backend.

The `db` service and `.env.example` arrived in M1 with the first table; the `backend` and
`frontend` containers arrived in M8.

| Decision | Rejected | Why |
|---|---|---|
| Docker Compose with three services | Everything installed on the host | Would require installing PostgreSQL and Maven and starting three things by hand. |
| nginx serves the frontend and proxies `/api` | Spring Boot serving the frontend's static files | One container fewer, but it couples two builds into one. |
| An explicit list of variables per service | `env_file: .env` on every service | Every container would receive every value, the Gemini key included. |
| `JWT_SECRET` set by hand | A default secret; one generated at start | A default is public; a secret generated at every start makes every token invalid after a restart. |
| Ollama not in the compose file | An Ollama service by default | The image and a model take several gigabytes, and V1 has no Ollama milestone. |

## 7. Frontend notes

The frontend follows the second approved design (M7.5): a dark gradient background, a
floating pill navigation, framed cards and pill-shaped controls, with the report kept as
dense rows grouped by file. Colours, gradients, shadows, fonts, spacing, radii, layers and
motion live in one file of CSS variables with semantic names
(`frontend/src/styles/tokens.css`); components use CSS modules. Patterns that repeat on
every screen are components: `Panel` (a translucent frame around a dark panel), `Eyebrow`
(the label above a title), `StatusMark` (the status of a review) and `Button` with an
optional arrow. The three font families are served from `frontend/public/fonts` (SIL Open
Font License).

Routes (`react-router`): `/` landing, `/login`, `/register`, `/reviews` history,
`/reviews/new` the form for a new review, `/reviews/:id` one review, anything else a
not-found page. The three review routes need a signed-in user; the static `new` segment
ranks before `:id`, so it is never read as a review id. `/reviews/:id` shows whatever the status says: progress while the review
is queued or running, the failure, or the report.

- `src/api/client.ts` adds the stored token to every request and turns problem details
  into an `ApiError`. A 401 on a request that carried a token signs the user out.
- The landing form requires a signed-in user. A visitor who is not signed in is sent to
  login and the URL they entered is kept.
- The review page polls the status every 2 s; the next request is planned only after the
  last one answered, and polling stops at `COMPLETED` or `FAILED`. A network failure or a
  5xx is retried, a 4xx is not.
- The short verdict next to the overall score is chosen from fixed templates by score and
  counters (`src/review/verdict.ts`); it is not written by the model.
- Findings are grouped by file, the file with the most severe finding first; inside a file
  they follow their lines. The report's filters (all, critical, warnings, smells) work on
  data already in the browser and carry the counters.
- The diff view numbers old and new lines in separate columns (`src/review/diff.ts`).
- The new review screen shows the same form as the landing page, the three latest reviews
  (the history endpoint with `size=3`; the list is left out if it cannot be loaded) and an
  interactive example of a report (`src/review/ReviewPreview.tsx`). The example is four
  cards in CSS 3D space: the scene tilts with the pointer, and a click brings one card
  forward. The pointer position is written to two CSS variables, not React state, so a
  move does not re-render. Below 720 px, without a mouse and with reduced motion, the
  scene does not tilt; on narrow screens the cards become a flat list.
- Motion is limited to hover colours, a press on buttons, the arrow in primary buttons,
  the progress bar and the example report. Under `prefers-reduced-motion` all
  transitions and animations stop.
- Text from the model is always rendered as text, never as HTML.

Out of scope for V1: exporting a report (planned for V2), dismissing individual findings
(needs per-user state on a shared analysis), and forcing a fresh analysis of a commit that
is already cached. Re-running a review submits the same URL again: a new commit produces a
new analysis, the same commit is served from the cache.
