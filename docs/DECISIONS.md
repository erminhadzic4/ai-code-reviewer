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

## 2026-10-10: M1 authentication
- **`jjwt` with a custom filter confirmed.** Rejected: the OAuth2 resource server, which is
  designed for tokens issued by a separate authorisation server and adds concepts this
  application does not use.
- **Token:** HS256, claims `sub` (user id), `iat`, `exp`; valid for 24 hours. Rejected:
  15 minutes (no refresh token exists), 30 days (no revocation exists).
- **The filter does not query the database** and never rejects a request itself; the access
  rules do. Only `/api/auth/me` loads the user. Cost: a token outlives its user until it
  expires. Rejected: loading the user on every request.
- **The application refuses to start** when `JWT_SECRET` is missing, shorter than 32 bytes
  or equal to the example value. Rejected: a default secret; a logged warning.
- **Passwords:** BCrypt, at least 8 characters and at most 72 bytes, no composition rules.
  The upper limit is in bytes because BCrypt reads only the first 72.
- **Login does not reveal whether an email exists:** same response, and an unknown email is
  still checked against a hash so the response time is comparable. Registration does reveal
  it, with 409; closing that needs email verification (see "Later" in ROADMAP.md).
- **Emails are stored trimmed and lower-case**, enforced by a CHECK constraint. Uniqueness
  is enforced by the database, so two simultaneous registrations cannot both succeed.
  Rejected: `CITEXT`; a unique index on `lower(email)`.
- **Closed by default:** every path requires a token unless it is listed as public.
  Rejected: listing the protected paths.
- **Errors carry their own status.** Feature exceptions extend one `ApiException`, handled
  in one place. Rejected: a handler method per exception type, which would make the shared
  package depend on every feature.
- **PostgreSQL 18**, published on `127.0.0.1:5432` so the backend can run on the host
  during development. Rejected: no published port; publishing on all interfaces.
- **`.env` is loaded by Spring Boot** through `spring.config.import` when the backend runs
  on the host. Rejected: a dotenv library; exporting variables by hand.
- **`.env.example` lists only variables that are in use.** Each milestone adds its own.
- **Integration tests are named `*IntegrationTest`** and run in the normal test phase.
  Rejected: `*IT` with the Failsafe plugin, extra build configuration with no benefit yet.

## 2026-10-11: M2 GitHub client
- **The repository URL is matched against one anchored pattern** and only the owner and the
  name leave the parser. Accepted: `github.com/owner/repo` with `https://`, `http://` or no
  scheme, optional `www.`, optional `.git`, optional trailing slash. Rejected: parsing with
  `java.net.URI` and checking the host, because URL parsers disagree on edge cases such as
  user info and backslashes, and one fixed shape is easier to test than a general URL.
- **No request ever goes to an address a user typed.** Addresses are built from the configured
  API origin and the validated names. Redirects are not followed automatically; each one is
  checked and followed only to the API origin or the archive origin, at most three times.
  Rejected: the HTTP client's own redirect handling, which decides the target and the headers
  by itself.
- **The access token is sent to the API origin only.** The archive host and any redirect
  target never receive it. `GITHUB_TOKEN` is optional; it raises the limit from 60 to 5000
  requests an hour and needs no scopes. Origins must use https, except on loopback for tests.
- **GitHub's answers are treated as untrusted input.** The reported repository name goes
  through the same parser as a user's URL, the branch name is encoded segment by segment
  before it is put in a path (so `release/1.0` keeps its slash and `#` or `?` cannot cut the
  path), and the commit SHA must be exactly 40 lower-case hex characters. A repository that
  GitHub reports as private is refused even when the token could read it.
  Rejected: the branch name as one encoded path variable, which turns `/` into `%2F`.
- **Zip archive confirmed, three API requests per review** (two when the commit is cached).
  The archive is requested by SHA, not by branch, so a push between two requests cannot
  change what is read. Rejected: the Contents API (one request per file), `git clone`
  (needs git and a working directory), the tarball (the JDK has no reader for it).
- **The archive is read as a stream and never written to disk.** Sizes declared inside the
  archive are ignored; only bytes actually read are counted, and entries that are skipped
  are inflated and counted too, so a bomb hidden in a skipped directory is stopped.
  Rejected: a temporary file with `ZipFile`.

  | Limit (`app.github.*`) | Default | When crossed |
  |---|---|---|
  | `max-repo-size`, as reported by GitHub | 1 GB | fails, `REPO_TOO_LARGE` |
  | `max-download-size`, compressed bytes | 50 MB | fails, `REPO_TOO_LARGE` |
  | `max-uncompressed-size`, skipped entries included | 200 MB | fails, `REPO_TOO_LARGE` |
  | `max-entries` in the archive | 20 000 | fails, `REPO_TOO_LARGE` |
  | `max-file-size` of one file | 200 KB | the file is skipped |
  | `max-files` kept for review | 500 | fails, `REPO_TOO_LARGE` |
  | `max-source-size` of the files kept | 2 MB | fails, `REPO_TOO_LARGE` |

- **`max-repo-size` is a coarse guard, set generously to 1 GB.** The number GitHub reports
  covers the whole history, is refreshed only now and then and is 0 for a new repository, so
  it says little about the code at one commit. It only avoids starting a download that is
  certain to fail; the limits applied while reading the archive are the real ones.
  Rejected: dropping the check, which would cost one request and up to 50 MB of download for
  an obviously huge repository.
- **A repository over the budget for kept files is rejected, not truncated.** Rejected:
  reviewing the first files only, which would give a score for part of a repository.
- **A path that escapes the archive root rejects the whole archive.** `..`, an absolute path
  or an entry outside the root directory cannot come from a git repository, so the answer is
  not what it claims to be. Paths that are legal but unusable (backslash, control characters,
  longer than 1024 characters, duplicates) are only skipped. Rejected: skipping every bad
  path quietly.
- **An archive counts as complete only if it ends with the zip end record.** The JDK's
  `ZipInputStream` returns "no more entries" without an error when the stream stops between
  two entries or inside an entry header; on an ordinary three-file archive 89 of 384 possible
  cut points went unnoticed. Without the check a dropped connection would give a shorter
  snapshot that looks complete and would then be cached for that commit. An archive cut
  short is a transient error (`GITHUB_UNAVAILABLE`) that can be retried; content that is not
  a zip, or whose bytes were changed, is a permanent `ARCHIVE_INVALID`.
- **The download deadline is the request timeout of a separate client for archives.** The
  request factory's read timeout covers the whole exchange, body included (measured on
  spring-web 7.0.9: with a 300 ms timeout a body that stalled and a body that trickled without
  any long pause were both cut at about 300 ms). There is no idle timeout: a server that stops
  sending holds the connection until the whole deadline runs out. Two clients are needed
  because one timeout cannot serve both API requests (30 s) and a download (60 s). Three tests
  pin this behaviour down, because nothing else enforces the deadline: if a later version
  applied the timeout only up to the response headers, they would fail.
  The first reason given for a deadline check inside the read loop, that the HTTP client does
  not limit the response body, was wrong; the measurement disproved it and the check was
  removed.
- **The client never retries and never waits.** A failure carries a code, whether it is
  retryable, and for a rate limit the time GitHub gave. Retrying belongs to the job (M5),
  which can postpone a review instead of blocking a worker. Rejected: a sleep-and-retry loop
  in the client; the primary limit can take up to an hour to reset.
- **One HTTP status per error code**, through the existing `ApiException` handling: 404 for
  a missing, private or blocked repository, 422 for an empty or oversized one, 503 for a rate
  limit (this server is limited by GitHub; the caller did nothing wrong), 502 for everything
  else, timeouts included.
- **File filter rules live in code, limits in configuration.** The lists of directories,
  lock files, extensions and markers are constants in `FileFilter`; numbers, timeouts and the
  token are under `app.github`. Rejected: lists in YAML, because changing one changes the
  result for a commit that may already be cached, so it should go through a commit and a
  test. Rejected: an allow-list of known source extensions, which would silently drop
  languages not on it and files such as `Dockerfile`, SQL and CI configuration.
- **Documentation and notice files are skipped:** `.md`, `.txt`, `.rst`, `.csv`, `.svg`, and
  `README`, `LICENSE`, `COPYING`, `NOTICE` and similar names with or without a suffix.
  `CMakeLists.txt` and `requirements*.txt` are kept because they are build files.
- **Known limitation: directories are excluded by name at any depth.** Source code in a
  directory called `build`, `out` or `target` (for example `src/build/Planner.java`) is not
  reviewed, and the same holds for `debug`, `release`, `external`, `deps`, `vendor` and
  `dist`. A notice-style name written in upper case with a suffix, such as `README.py`, is
  skipped too. Rejected: excluding these directories only at the repository root, because a
  monorepo has `backend/target` and `frontend/dist`.
- **Tests never call GitHub.** They run against a small HTTP server from the JDK on the
  loopback interface, and build zip archives, hostile ones included, in memory. Rejected: a
  mock of the HTTP layer, which would skip redirects, timeouts and streaming; binary archive
  fixtures in the repository; a mock-server library (a new dependency). One smoke test talks
  to the real GitHub; it runs only when `GITHUB_LIVE_TEST=true` is set, never in CI.

## 2026-10-12: M3 review domain and provider interface
- **No REST endpoint in this milestone.** Submitting and polling arrive with the worker (M5).
  Rejected: `POST /api/reviews` that only inserts a `QUEUED` row, because nothing would ever
  process it and the status could not change.
- **One migration creates `analyses`, `findings` and `reviews` in their final shape**, job and
  score columns included, so the next milestones add code and no migration for these tables.
  Rejected: adding columns milestone by milestone.
- **Constraints beyond the column list in `ARCHITECTURE.md`:** a review has an analysis
  exactly when it is `COMPLETED`; a `FAILED` review has an error code; progress, attempts and
  file counts cannot be negative; scores are 0 to 100; repository names are lower-case and a
  commit SHA is 40 lower-case hex characters in both tables; finding lines come as a pair
  inside a file. Every part of the line rule is tested for NULL explicitly, because a CHECK
  that evaluates to NULL passes.
- **An index on `findings(analysis_id)`.** PostgreSQL does not index a foreign key by itself.
- **Only `findings` cascades on delete.** A user or an analysis that a review points to
  cannot be deleted. Rejected: cascading from users, which no feature needs yet.
- **`error_code` and `analyses.provider` have no CHECK list.** New codes and providers must
  not need a migration. Closed sets (status, stage, kind, category, severity) have one.
- **Scores are NOT NULL.** An analysis is written once, complete. Consequence: under the
  current roadmap order a review can be completed only after M6 has produced scores, so the
  plan for M5 has to account for this. Rejected: nullable scores, which would let a
  half-finished analysis sit in the shared cache.
- **The finding enums live in `provider`,** and `RawFinding` uses them instead of strings.
  Dependencies point one way: `review` → `provider` → `github`. Rejected: enums in `review`,
  which would make the two packages import each other; string fields, which every consumer
  would have to parse.
- **`ReviewChunk` reuses `SourceFile`** from the GitHub client. Rejected: a second record of
  the same shape.
- **The interface has exactly `name()`, `model()` and `review(chunk)`.** Where the prompt
  version of the cache key comes from is decided with the first real prompt (M4).
- **The fake provider derives its findings from the chunk** instead of returning a fixed
  list. Rejected: fixed paths such as `src/Main.java`, which line verification would discard,
  leaving an empty report; random findings, which would make tests and screens unrepeatable.
- **The level of a finding is computed, not stored** (`FindingLevel.of(kind, severity)`); a
  combination the database forbids throws.
- **`Review` holds the user's id, not a `User`.** A request knows its user by id only (M1),
  and a review never reads user data. Rejected: `@ManyToOne User`.
- **`Finding` points to its `Analysis`; `Analysis` has no collection of findings.** Rejected:
  a bidirectional collection, which makes it easy to load hundreds of findings by accident
  when only a status is read.
- **`reviews.id` is generated by Hibernate before the insert.** Rejected: assigning it in the
  constructor (Spring Data would treat the entity as existing and issue a SELECT first); a
  database default (the id would be unknown until after the insert).
- **`Review` has no state transitions yet** and the repositories declare no queries. They
  arrive with the code that calls them.
- **The provider is chosen by `app.review.provider`,** bound to an enum, with the default
  `fake` in `application.yml`. A value that names no provider stops the startup. Rejected:
  only a condition on the bean, because nothing injects a provider yet and a typo would pass
  unnoticed. `REVIEW_PROVIDER` is not in `.env.example` while there is one value to choose.
- **Schema tests and mapping tests are separate classes.** One writes rows with SQL and
  checks each constraint by name; the other writes and reads through the entities, which
  proves they match the migration (Hibernate runs in validate mode).

## 2026-10-12: M4 Gemini provider
- **Model: `gemini-3.5-flash-lite`**, chosen by its free limits. The limits are not in the
  API documentation; they are per Google project and shown in AI Studio. Read there on
  2026-10-12 for this project's free tier (Google can change them at any time):

  | Models | Requests a minute | Tokens a minute | Requests a day |
  |---|---|---|---|
  | Gemini 3.5 Flash-Lite, 3.1 Flash-Lite | 15 | 250 000 | 500 |
  | Gemini 3.5 to 3.8 Flash | 5 | 250 000 | 20 |
  | Gemini 2.5 Flash-Lite | 10 | 250 000 | 20 |
  | Gemma 4 (26B, 31B) | 30 | 16 000 | 14 400 |
  | Pro models | not available | | |

  Rejected: the models with 20 requests a day, because one large repository would use up the
  day; Gemma, because 16 000 tokens a minute cannot take one chunk. The model stays
  configurable (`GEMINI_MODEL`).
- **The API key must belong to a Google project without billing.** On the free tier a call
  costs nothing; in a project with billing every call is charged, which would break the rule
  that this project costs nothing. The application cannot check this, so it is stated here
  and in `.env.example`.
- **On the free tier Google may use the submitted content to improve its products.**
  Accepted, because only public repositories are ever read (a private one is refused even
  when the GitHub token could read it). The request also sets `store: false`, so the API does
  not keep the interaction.
- **Interactions API** (`POST /v1beta/interactions`). Rejected: `generateContent`, which the
  documentation now calls legacy and no longer describes for structured output.
- **Chunk budget: 200 000 characters, counted on the numbered text.** Measured on this
  project's own Java sources: 199 897 characters were 71 353 input tokens, 2.8 characters per
  token, so three full chunks a minute fit the limit. Pacing the requests belongs to the job
  (M5). Rejected: a budget in tokens, which needs a tokenizer (a new dependency).
- **A file is never split and never left out.** A file larger than the budget is a chunk of
  its own; the model's context is far larger. Rejected: splitting by lines (a finding loses
  its context); skipping the file (a review that silently covers part of a repository).
- **Line numbers are added when the text for the model is made, not in the chunk.** A chunk
  keeps whole, unchanged files, so the validator and the snippet read the real content.
  Rejected: numbered text inside the chunk, which every other reader would have to strip.
- **One definition of a line, `SourceFile.lines()`** (the JDK's `String.lines()`), used by
  the numbering, the line check, the snippet and the fake provider. Rejected: splitting on
  `\n`, which keeps `\r` and counts an empty last line.
- **`promptVersion()` on the provider interface,** a constant next to the instructions.
  Rejected: a number in the configuration, which can drift from the prompt.
- **Mapping of errors was written against recorded answers, not the documentation.** The raw
  REST answers are not shown there, and several differ from it:

  | Case | Documentation | Observed |
  |---|---|---|
  | invalid key | 401, code `authentication` | 400; an array around an object with `status: INVALID_ARGUMENT` and the reason `API_KEY_INVALID` |
  | unknown model | 404, code `model_not_found` | 404, code `not_found` |
  | answer cut at the output limit | status `incomplete` | HTTP 200, `status: incomplete`, the partial text present |
  | successful answer | not shown | text in `steps[type = model_output].content[type = text].text`, after a `thought` step |

  So an invalid key is recognised from the body before a 400 is taken for a mistake in the
  request, and the error reader accepts both shapes. The recorded answers are test fixtures
  (`backend/src/test/resources/gemini/`). A rate limit, a server error and blocked content
  could not be produced on purpose; their handling follows the documentation.
- **At most 15 findings per chunk.** The API rejected the schema with nullable fields and
  `maxItems` 20 (`invalid_request`, no explanation); it accepted 15, 12, 10 and no `maxItems`
  at all; 16 to 19 were not tried. Rejected: optional instead of nullable fields, which the
  API accepts with 20 but which made the model leave `lineEnd` out of every issue even when
  told not to; no `maxItems`, which leaves nothing but the instructions to bound the answer.
- **The longest answer the validator accepts fits the output limit.** 15 findings, title up
  to 200 characters, explanation and suggestion up to 800, suggested code up to 2400: about
  69 000 characters, under 28 000 tokens even at 2.5 characters per token, against a limit of
  32 768. The instructions ask for less (80, 500, 500 and 40 lines). Rejected: only raising
  the limit. The first plan allowed 40 findings with far longer texts and could not have fit.
- **A truncated answer is not salvaged.** The provider reports `PROVIDER_OUTPUT_TRUNCATED`
  and uses nothing of it. Rejected: reading the complete findings out of unfinished JSON (the
  list would be silently incomplete); the continuation token (more requests and state for a
  case the limits already prevent). What the job does with such a chunk is decided in M5.
- **`FindingValidator` is the only place a finding is dropped.** The Gemini reader keeps a
  finding it cannot read as an empty one, so that the validator drops and counts it. A
  finding with one unreadable field (unknown value, wrong type) is unreadable as a whole;
  reading the rest would turn a wrong value into a missing one, and a missing value can be a
  valid finding. Rejected: dropping in the reader, which would need a second counter.
- **Rules for one field are Bean Validation constraints on `RawFinding`; rules across fields
  are code** in the validator (the shape of each kind, lines inside the file). Rejected: a
  JSON Schema library (a new dependency); a class-level custom constraint.
- **A finding that fails is dropped, never repaired,** not even by shortening a title.
  Rejected: truncating, because truncated suggested code is not code.
- **The path of a finding must equal a path of the chunk exactly.** `./a.py` is not `a.py`.
  Rejected: normalising, which could attach a finding to a file it was not written for.
- **A finding spans at most 100 lines.** Longer is about a file, not a place in it, and its
  snippet would be most of the file. The instructions state the same number.
- **The validator returns `VerifiedFinding`, not the entity.** `provider` must not depend on
  `review`. `findings_discarded` is the sum of the validator's counts over the chunks; the
  job adds them up.
- **The same good-practice title is counted once in M6, not here.** It is a rule of the
  report over the whole repository; the validator sees one chunk.
- **`ProviderException` and `ProviderErrorCode` are not specific to Gemini,** so the job
  handles one type for any provider. The daily quota is not retryable although it passes by
  itself: it returns at midnight Pacific time, far later than any retry of a job.
- **The key is required only when Gemini is selected,** and checked by the provider when it
  is created. The settings are read for every provider, so they cannot demand it.
- **No `temperature` and no thinking level in the request.** The documentation advises the
  default temperature for this model family, and the recorded answers show about 19 tokens
  beyond the visible output counted against the limit, which is negligible.
- **The fake HTTP server is shared by the GitHub and the Gemini tests,** and the check that
  a configured address is an https origin is shared by both settings classes. Rejected: a
  second copy of each.
- **Tests never call Gemini.** Two live tests run only with `GEMINI_LIVE_TEST=true` and a
  key, never in CI; each costs one request.

## 2026-10-13: M5 scoring formula
- **Scoring moved before the asynchronous jobs; the milestones were renumbered.** M5 is now
  the scoring formula and M6 the jobs. Earlier entries that say "M5" for the job and "M6" for
  scoring and saving refer to the old order. Three things tie the job to the scores: a review
  is `COMPLETED` only with an analysis, and an analysis needs its four scores (NOT NULL);
  saving the analysis and its findings is the last step of a job; and the unique cache key
  makes a second review of the same commit fail unless the job first looks for an existing
  analysis. So M6 also takes saving and the cache lookup.
  Rejected: nullable scores until the formula exists (a later migration could not compute the
  missing scores in SQL and would have to delete those analyses); a provisional formula in the
  job milestone (throwaway code, and its scores would stay in the cache).
- **The penalty is linear in issues per 1000 lines.** The same issues weigh half as much in
  twice as much code. Rejected: no normalisation (a large repository would always score 0);
  per file (a file of 10 lines and one of 2000 are not the same amount of code); a
  logarithmic or square-root curve (harder to explain in a report, for a benefit that shows
  only beyond the size limits of M2).
- **A line is a line of `SourceFile.lines()`,** blank lines and comments included. Only files
  that were actually reviewed count. Rejected: lines of code only, which needs a parser per
  language.
- **Less than 1000 lines is scored as 1000.** Without the minimum, one low issue in a
  forty-line script would leave a score of 25. Rejected: no minimum; a minimum of 100 lines,
  still too harsh for small repositories.
- **Points per 1000 lines: critical 30, high 15, medium 6, low 3.** Rejected: equal points
  (a style remark would cost as much as a vulnerability); 100, 10, 1 (one critical issue would
  decide the score alone and the other issues would not matter).
- **Overall score: security 40 %, maintainability 35 %, performance 25 %.** Security weighs
  most because its mistakes cost most; performance least because it is the hardest to judge
  from reading code alone. Rejected: equal weights; the lowest of the three scores, which
  would hide the other two.
- **The overall score is computed from the rounded category scores,** rounding half up, so
  it can be checked from the numbers shown. Rejected: from the exact values, where the shown
  and the computed score can differ by one.
- **The numbers are constants in the code,** like the rules of `FileFilter`: changing one
  changes results, so it goes through a commit and a test. Rejected: configuration.
- **`ScoreCalculator` is a final class with a static method,** like `FindingLevel` and
  `RepoUrlParser`. It has no state and no dependencies. An issue without a severity throws:
  validation does not let one through, so it would be a mistake in the code.
- **Known: one severe issue lowers the overall score only moderately.** A small repository
  with one critical security issue scores 70 for security and 88 overall, because the overall
  score is an average. The report shows critical issues in a counter of their own.
  Rejected for now: a cap on the overall score when a critical issue exists, one more rule to
  explain.
- **Known: a large repository cannot score very low.** A chunk reports at most 15 findings
  (M4), so the worst case of a full chunk of about 6000 lines is 25 in one category.
- **Known: changing the formula does not change analyses already stored.** The scores are
  saved with an analysis and the cache key has no version of the formula.

## 2026-10-13: M6 asynchronous jobs
- **The worker is a scheduled method on one thread, and one review runs at a time.** A round
  takes the oldest queued review that is due (`FOR UPDATE SKIP LOCKED`), runs the whole review
  on the scheduler's thread, and the next round starts only when it has returned. The earlier
  wording "one job at a time (configurable)" is dropped: nothing is configurable. Rejected: an
  executor with several threads, because the model's limit is per key, so parallel reviews
  would only share the same three chunks a minute. `SKIP LOCKED` stays, so a second worker
  later needs no change to the query.
- **The claim is a native query.** Rejected: JPQL with a pessimistic lock and a timeout hint
  that Hibernate turns into `SKIP LOCKED`; the native query shows what the database does.
- **Recovery after a stop.** The first round of the worker puts every review that is still
  `RUNNING` back into the queue, due at once; a review that has used all its attempts fails
  with `REVIEW_INTERRUPTED` instead, so a review that brings the application down cannot do so
  forever. Recovery runs on the same thread as the reviews, so it cannot touch one that is
  running. Assumes a single instance. Rejected: a lease column with an expiry, needed only
  for several instances.
- **A round that ends with an unhandled exception is followed by another recovery.** If, for
  example, the database cannot be reached when the end of a review is to be written, that
  review stays `RUNNING` although nobody runs it. The next round therefore recovers again.
  This is safe because everything happens on one thread: when a round begins, the worker is
  not in the middle of any review, so a review marked as running is a leftover.
  Rejected: recovering only on startup, which would leave such a review stuck until a restart.
- **A shutdown is not a failure of the review.** When the thread is interrupted, nothing is
  written: the review stays `RUNNING` and is recovered on the next start. A request that was
  cut off by the shutdown looks like a network error; it is not counted against the review.
- **A mistake in the application fails the review at once** with `INTERNAL_ERROR` and a
  generic message; the details go to the log only. It is not retried, because the same
  review would hit the same mistake.
- **Everything a job writes goes through one class, `ReviewJobStore`,** one short transaction
  per method, so no transaction is open during a request to GitHub or to the model. The
  methods are in a class of their own because a transaction starts only when a method is
  called through the proxy around the bean; called from inside the class that runs the job
  they would run without one. Rejected: a transaction template with lambdas inside the
  pipeline (steps and database code would mix); a bean that injects itself.
- **State transitions are methods on `Review`** that check the state they start from and
  throw otherwise. Rejected: setters with the checks in a service, which could be bypassed; a
  `RETRYING` status (a queued review with a later `run_after` says the same without a
  migration); a `CANCELLED` status (nothing can be cancelled in V1).
- **Requests to the model are paced by the job, in `RequestPacer`.** A sliding window of 60
  seconds over two budgets, requests and characters; a request waits only as long as it
  takes for both to fit, so a review of up to three chunks never waits. A chunk larger than
  the whole budget goes out when the window is empty. Rejected: waiting inside the provider
  (it never waits, M4); a fixed pause between requests, which slows small repositories for
  nothing; a counter in the database, needed only for several instances. The window is kept
  in memory: after a restart the limit can be crossed for a moment, and the rate limit that
  follows is retried.
- **Pacing for Gemini: 12 requests and 600 000 characters a minute**
  (`app.gemini.requests-per-minute`, `characters-per-minute`). The free limits are 15
  requests and 250 000 tokens; 600 000 characters are three full chunks, about 214 000 tokens
  at the measured 2.8 characters per token. A test fails if the shipped values reach the
  free limits.
- **The limits come from the provider:** `limits()` on `CodeReviewProvider`, not a default
  method, so every provider has to state its own. The fake has none. Rejected: one setting
  for all providers, which would slow the fake and, later, a local model.
- **The daily quota fails the review at once** (`PROVIDER_QUOTA_EXHAUSTED`) and nothing of
  it is stored: the quota is not a property of the code, so an incomplete analysis must not
  stay in the cache for that commit. The quota is not counted locally; the key may be used
  elsewhere and a counter would not survive a restart. Rejected: postponing the review until
  the quota returns, which would leave the progress screen waiting for hours.
- **Two levels of retry.** Inside a job only a request to the model is repeated: the same
  chunk is sent again after a rate limit, a server error or an answer that is not the agreed
  JSON, at most three sendings, after 5 and then 20 seconds or the time the provider gave. A
  wait longer than 60 seconds is handed to the job. The whole job is retried at most three
  times (`app.review.max-attempts`), 1 and then 5 minutes later, or at the time the service
  gave, bounded to one hour because that time comes from a response header. The pauses are
  constants in the code. Rejected: pauses in the configuration; nobody would tune them, and
  every setting needs validation and a test.
- **A retry of the job repeats the chunks that were already reviewed.** Nothing of a failed
  attempt is kept, so each attempt spends the model's quota again from the first chunk; this
  is why a failing request is first repeated inside the job.
- **GitHub failures are retried at the level of the job only.** This replaces the earlier
  statement that a single GitHub request is repeated inside a job. GitHub is asked before the
  first chunk, so starting over costs no quota of the model, and its hourly limit can take up
  to an hour to reset: the review is postponed through `run_after` and the worker is free for
  other reviews. Rejected: waiting inside the job, which would block the queue.
- **A chunk the model cannot review because of its content is split once.** When the answer
  is cut off or the content is refused, sending the same chunk again gives the same result. A
  chunk with several files is sent again as two halves; a half is not split further.
  Rejected: splitting down to single files, which could cost many requests of the daily quota
  for a rare case.
- **A chunk is split by the number of its files, not by their size.** The halves keep the
  order of the files, and with an odd number the first half is the larger one.
- **A partial analysis completes the review.** Files that still cannot be reviewed are
  counted in `files_failed`, their paths are logged, and the review is `COMPLETED` if at least
  one chunk was reviewed; if none was, it fails with the last error. M2 rejected a score for
  part of a repository; this differs because there an arbitrary first part would have been
  reviewed silently, while here a few files are missing and the report says how many.
  Rejected: failing the whole review, which would make a repository with one file the model
  refuses impossible to review.
- **`files_failed` and `lines_analyzed` are columns of `analyses`, not of `reviews`** (`V3`).
  Both describe the result, which every review of the commit shares, and a review served from
  the cache never reads the files, so it could not compute either. `lines_analyzed` is the
  second input of the score formula; stored next to the scores, every score can be checked
  from what the database holds. `files_failed` defaults to 0, a true value for the usual
  case; `lines_analyzed` has no default, because no value is true by default and a made-up 0
  would look like data. Rejected: counting failed files as skipped, which means "left out by
  the file filter before the review".
- **A repository with nothing to review fails** with `NO_REVIEWABLE_FILES`, without a
  request to the model and without an analysis. Rejected: an empty analysis, which would give
  a repository of documents a score of 100 and cache it; `REPO_EMPTY`, which means "no
  commits".
- **One good practice per title.** Before scoring and storing, the first good practice of
  every title is kept (compared without case and surrounding space); a model sees one chunk
  at a time and praises each for the same thing. Rejected: storing all and counting once when
  the report is read, which would leave rows that are never shown.
- **The cache is looked up in the job, right after the commit is known,** under the name
  GitHub currently uses for the repository, so a renamed repository leads to the same
  analysis. A partial analysis is cached like any other. Rejected: a lookup when the review
  is submitted; the commit is not known then, and submitting does not ask GitHub.
- **The API.** `POST /api/reviews` answers 202 with a `Location` and the status of the new
  review. Another user's review is answered exactly like a missing one, 404. The report of a
  review that is not completed is 409. `retryAt` in the status is set only while a review
  waits in the queue for a time that is still ahead; a new review and one put back by
  recovery have none. The history endpoint (`GET /api/reviews`) arrives with the screen that
  uses it (M7).
- **A token whose user no longer exists gets 401 when it submits a review,** the same answer
  as for a missing token. A token outlives its user until it expires (M1), and without this
  the foreign key on `reviews.user_id` would turn the request into a 500.
- **Tests never wait for time to pass.** Everything that waits uses a `Clock` and a `Sleeper`;
  in tests the sleeper moves the clock forward instead of sleeping. The worker's timer is
  switched off (`app.review.worker.enabled`) and a test runs a round by calling the worker,
  so every change of status happens on the test's thread. GitHub is the fake HTTP server and
  the provider is the fake one behind a wrapper that can be told to fail. Only the tests of
  the timer itself and of the real sleeper let real time pass, bounded and for a few
  milliseconds.

## 2026-10-14: M7 frontend
- **The mockup is followed in colour and type, but made denser.** Findings are rows of about
  40 px grouped by file, not one card each; the overall score, its verdict and the three
  sub-scores share one band instead of a ring and four counter tiles; the counters are the
  filter buttons. Radii are 3 to 10 px instead of 12 to 28, and pill shapes are gone. The
  landing page drops the badge above the headline, the three equal cards and the closing
  call-to-action box. Rejected: the mockup as drawn, which shows about four findings on a
  screen where a real repository has dozens.
- **Sub-score bars are neutral.** Rejected: yellow and green by value, which read as the
  colours of finding levels.
- **`react-router` is the one new dependency.** Rejected: a router of our own over the
  History API (code to maintain and test for no gain); TanStack Query or SWR (a dependency
  for three endpoints and one poll); axios (`fetch` is enough); a component library.
- **Fonts are served by the frontend** as three variable woff2 files from the fonts' own
  repositories. Rejected: Google Fonts at runtime (an external service, no offline use);
  `@fontsource` packages (three dependencies).
- **One route per review.** `/reviews/:id` shows progress, failure or report as the status
  says. Rejected: separate progress and report routes, which need a redirect when the status
  changes.
- **Polling with a chain of timeouts.** The next request is planned when the last one has
  answered. Rejected: `setInterval`, which stacks requests on a slow network; server-sent
  events or WebSockets, which need backend work the 2 s poll does not justify.
- **A queued review shows why its last attempt failed** (`Review.requeue` keeps the error
  code and message; the next start clears them). It reuses the `error_code` and
  `error_message` columns, so no migration. Rejected: separate `last_error_*` columns for
  the same data; showing only the attempt number and the retry time.
- **History is paged by the backend** with a response of our own shape. Rejected: returning
  Spring's `Page` (its JSON is not meant to be stable); cursor paging (not needed locally);
  one unpaged list.
- **The URL is checked by the backend only.** The form catches an empty field and shows the
  backend's message otherwise. Rejected: a GitHub URL pattern in the frontend, which would
  keep the rule in two places.
- **The failure screen offers "Try again" only where it can help.** A missing, empty or too
  large repository, or one without reviewable files, offers another repository instead.
- **The diff has two line-number columns**, old and new, sized to the largest number.
  Rejected: one column that repeats the number of a replaced line.
- **Motion is limited** to colour changes on hover, a slight press on the primary button, a
  fade when a finding opens and the progress bar; all of it stops under reduced motion.
- **Local lint stays in CI.** The linter binary cannot run on the development machine.
  Rejected: running it in a container, an extra setup for one command CI already runs.

## 2026-10-15: M7.5 frontend redesign
- **The second approved design replaces the shapes of M7, not its density.** Pills, radii of
  14 to 32 px and framed cards come back; findings stay rows of about 48 px grouped by file,
  and the overall score, verdict and sub-scores stay one band. Rejected: keeping the M7
  shapes (the design was approved to change them); one card per finding (the reason M7
  made the report denser still holds).
- **The 3D example is plain CSS** (`perspective`, `preserve-3d`, `translateZ`). Four flat
  cards of text are what CSS 3D does well, and the text stays real DOM: readable,
  selectable and reachable by keyboard. Rejected: three.js or `@react-three/fiber` (a large
  dependency that draws into a canvas, so the cards would leave the accessibility tree);
  Framer Motion (a dependency for what `transition` already does).
- **The pointer position is not React state.** It is written to two CSS variables, at most
  once a frame; only the card in focus is state. Rejected: state per pointer move, which
  re-renders the component dozens of times a second.
- **Motion stays limited.** 3D only on the new review screen; elsewhere hover colours, a
  press on buttons, the arrow of a primary button and the progress bar. No entrance or
  scroll animations. Without a mouse, below 720 px and under reduced motion the scene does
  not tilt; on narrow screens the cards are a flat list.
- **Tokens stay one file of semantic names.** New groups for surfaces and translucent fills,
  gradients, shadows, larger radii and a spring curve; the existing names kept their
  meaning, so no module had to change for the new values. Repeated patterns are components
  (`Panel`, `Eyebrow`, `StatusMark`, `Button` with an arrow). Rejected: a second layer of
  raw palette tokens (useful with several themes, not with one); tokens in TypeScript or
  CSS-in-JS; Tailwind.
- **The faint text colour is lighter than drawn** (`#7a8597` instead of `#6f7a8b`): the
  drawn one is 4.35:1 on a card, below 4.5:1 for small text. On the landing page, measured
  where it stands on the gradient, small text is at least 5.2:1.
- **A new route, `/reviews/new`, for the new review screen.** The existing routes are
  unchanged; the navigation's "New review" goes there. The landing page keeps its form for
  everyone, and the failure screen still offers the start page. Rejected: showing the new
  screen at `/` for signed-in users, which changes what an existing address shows.
- **The recent reviews use the history endpoint** with a page size of 3. Rejected: an
  endpoint of their own for the same data.
- **What the design shows and the API does not have is left out:** a readability
  sub-score and categories beyond security, maintainability and performance; file and
  line counts while a review is fetching; per-code failure headlines. The progress keeps
  its four steps (queued is one) and its sentences; the failure keeps "Try again" where it
  can help. The texts of buttons and headings stay as they were.
- **Review statuses are round dots**, finding levels stay square marks, so the two never
  look alike.
- **The background glows are clipped on both axes** (`overflow: clip`), so a short page does
  not grow to fit them. Rejected: `overflow: hidden`, which makes a scroll container and
  breaks the sticky file list of the report.
- **The cards of the example take an outline when focused.** They carry their own shadows,
  which would hide the shadow-based focus ring used elsewhere.

## 2026-10-16: M8 polish
- **Every base image is pinned to an exact tag:** `eclipse-temurin:21.0.12.1_1-jdk-resolute`
  and `eclipse-temurin:21.0.12.1_1-jre-resolute` for the backend, `node:24.21.0-alpine3.24`
  and `nginx:1.30.6-alpine3.24` for the frontend, `postgres:18` as before. Rejected: floating
  tags such as `21-jre` or `latest`, which change what a build produces without a commit.
- **The backend images are the Ubuntu variants.** They are Temurin's default images (glibc),
  and the runtime image already has `curl` for the health check. Rejected: the Alpine
  variants, smaller but built on musl instead of glibc.
  The frontend uses Alpine: Node only builds static files, and nginx on Alpine has `wget`.
- **Both images are built in two stages.** The final image holds only the jar, or only the
  built files, and runs without root rights. Tests do not run in the image build: they need
  Docker for their database, and CI runs them. A CI job builds both images with the example
  configuration, so a broken Dockerfile fails a pull request. Rejected: starting the whole
  stack in CI, slower and already covered by the tests.
- **nginx looks up the backend per request** (`resolver 127.0.0.11` and the address in a
  variable). It starts without the backend and follows a recreated container. Rejected: a
  fixed `proxy_pass http://backend:8080`, which is resolved once at startup. Hashed files under
  `/assets/` are cached for a year and a missing one is a 404, never the page; every other
  address that is not a file returns `index.html`.
- **Each service gets an explicit list of variables** from `.env`; the frontend gets none.
  Rejected: `env_file`, which hands every value, the Gemini key included, to every container.
- **Start order follows health checks:** `db` (`pg_isready`), then `backend` (`curl` on
  `/actuator/health`, healthy after Flyway has run at startup), then `frontend` (`wget` on
  `127.0.0.1`, because `localhost` may resolve to the IPv6 address on which nginx does not
  listen). Rejected: a separate Flyway container, a second copy of the same migrations.
- **`JWT_SECRET` must be set by hand** after copying `.env.example`: Compose refuses an empty
  value and the backend refuses the example value. Rejected: a default secret (public) and a
  secret generated at start (every token invalid after a restart).
- **A variable set in the shell or in the operating system's environment takes precedence
  over `.env`,** for Compose and for Spring Boot alike. This was found while testing, when a
  user-level variable silently replaced the value in `.env`; the README states it.
- **API documentation with springdoc-openapi 3.0.3** (`springdoc-openapi-starter-webmvc-ui`).
  The 3.0 line is the one for Spring Boot 4.0; 3.1 moved to Spring Boot 4.1. It brings
  swagger-core, which uses Jackson 2 modules next to the application's Jackson 3. The
  description and the viewer are public (GET only); the endpoints they describe still need a
  token, shown as one bearer scheme for all operations, cleared for registration and login.
  Generic error answers of the exception handler are not added to every operation; each lists
  its own. The viewer is at `http://localhost:8080/swagger-ui.html`, published on `127.0.0.1`
  only, and is to be turned off for a public version (ROADMAP, "Later").
- **A gateway status without a problem detail is shown as a network error.** 502, 503 and
  504 with an HTML or empty body come from the proxy while the backend is down or starting.
  The status number is kept, so polling keeps retrying. A 5xx answered by the backend itself
  keeps the generic message, as before.
- **An expired session is explained.** A 401 on a request that carried a token signs the user
  out with the reason "expired", and the login page says so; logging out and a first visit
  show nothing. The review form answers a 401 by going to the login page with the URL. If
  the session expires while the form on `/reviews/new` is open, the user returns to
  `/reviews/new` after logging in but the field is empty; the landing page keeps the URL.
  Rejected: a notification system for one message; reading the token's expiry in the browser.
- **An unreachable server keeps the session.** When the check of a stored token gets no
  answer (a network error or a 5xx), the token is kept and protected pages show "The server
  cannot be reached" with a button to check again; the navigation is hidden while it is not
  known who is signed in. Only a 401 signs the user out. Rejected: retrying in a loop.
- **The failure screen now offers `/reviews/new`** ("Review another repository"), like the
  navigation; the M7.5 entry above describes the earlier state.
- **The favicon matches the navigation logo:** two neutral rings on the dark tile instead of
  an accent ring. The version is 1.0.0 in `pom.xml`, `package.json` and the API description.
- **First real review with Gemini.** A public C# repository of 45 reviewable files (5 346
  lines, two chunks), model `gemini-3.5-flash-lite`, prompt version 1. Google answered 503
  three times; the retry inside the job (after 5 and 20 seconds) handled it, so the review sent
  5 requests for 2 chunks and took 1 min 17 s. No 429, no finding discarded, the lines
  checked against the repository correct, score 94 (maintainability 82). But six of the
  eight findings were HIGH and claimed that a wrong value changed the behaviour; following
  the code showed that the value had no effect on the outcome, so the claimed effect did not
  exist.
- **Generated resource files are not filtered for now.** The 14 `.resx` files of that
  repository, with `.gitignore` and `.gitattributes`, took an estimated 30 000 of the 78 000
  input tokens (by their share of the characters) and drew no finding. Rejected for now:
  adding them to the file filter on the evidence of one review; noted under "Later" in the
  ROADMAP.
- **The prompt asks for evidence (version 2).** Before reporting a consequence, the model is
  told to confirm in the given code that the value or code is used in the way that causes
  it; CRITICAL and HIGH must name the line where the failure happens and how it is
  triggered, otherwise only what is visible is reported, as MEDIUM or LOW. A test pins the
  SHA-256 of the instructions to the version, so a change without a new version fails.
  The rerun on the same commit sent 2 requests and took 32 s: four LOW findings, all correct
  (two kept from the first run, two new), the six unsupported HIGH findings gone, score 99.
  Limits: one repository and one run each, a model whose answers vary between runs, fewer
  findings overall, the unused value behind the six findings not reported at all although
  the prompt asks for it, and no good practice in that run. The prompt is not tuned further
  on this evidence.
