-- The result of analysing one repository at one commit. Shared by every review of that
-- commit and never changed after it is written, so it also serves as the cache.
CREATE TABLE analyses (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo_owner            VARCHAR(39)  NOT NULL,
    repo_name             VARCHAR(100) NOT NULL,
    commit_sha            CHAR(40)     NOT NULL,
    provider              VARCHAR(20)  NOT NULL,
    model                 VARCHAR(100) NOT NULL,
    prompt_version        INT          NOT NULL,
    overall_score         SMALLINT     NOT NULL,
    security_score        SMALLINT     NOT NULL,
    maintainability_score SMALLINT     NOT NULL,
    performance_score     SMALLINT     NOT NULL,
    files_analyzed        INT          NOT NULL,
    files_skipped         INT          NOT NULL,
    findings_discarded    INT          NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The cache key. Provider, model and prompt version are part of it, so changing any of
    -- them does not serve a stale analysis.
    CONSTRAINT uq_analyses_cache_key
        UNIQUE (repo_owner, repo_name, commit_sha, provider, model, prompt_version),
    -- GitHub names are case-insensitive; one spelling keeps the cache key unambiguous.
    CONSTRAINT ck_analyses_repo_lowercase
        CHECK (repo_owner = lower(repo_owner) AND repo_name = lower(repo_name)),
    CONSTRAINT ck_analyses_commit_sha CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    CONSTRAINT ck_analyses_scores CHECK (
        overall_score BETWEEN 0 AND 100
        AND security_score BETWEEN 0 AND 100
        AND maintainability_score BETWEEN 0 AND 100
        AND performance_score BETWEEN 0 AND 100),
    CONSTRAINT ck_analyses_counts
        CHECK (files_analyzed >= 0 AND files_skipped >= 0 AND findings_discarded >= 0)
);

CREATE TABLE findings (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    analysis_id        BIGINT       NOT NULL,
    kind               VARCHAR(15)  NOT NULL,
    category           VARCHAR(20)  NOT NULL,
    severity           VARCHAR(10),
    file_path          VARCHAR(1024),
    line_start         INT,
    line_end           INT,
    title              VARCHAR(200) NOT NULL,
    explanation        TEXT,
    suggestion         TEXT,
    suggested_code     TEXT,
    code_snippet       TEXT,
    snippet_start_line INT,
    CONSTRAINT fk_findings_analysis
        FOREIGN KEY (analysis_id) REFERENCES analyses (id) ON DELETE CASCADE,
    CONSTRAINT ck_findings_kind CHECK (kind IN ('ISSUE', 'GOOD_PRACTICE')),
    CONSTRAINT ck_findings_category CHECK (category IN ('SECURITY', 'MAINTAINABILITY', 'PERFORMANCE')),
    CONSTRAINT ck_findings_severity CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    -- An issue points at code and has a severity. A good practice has no severity and may
    -- apply to the whole repository, so its location is optional.
    CONSTRAINT ck_findings_kind_shape CHECK (
        (kind = 'ISSUE' AND severity IS NOT NULL AND file_path IS NOT NULL
            AND line_start IS NOT NULL AND line_end IS NOT NULL)
        OR (kind = 'GOOD_PRACTICE' AND severity IS NULL)),
    -- Lines come as a pair, inside a file. Every part is tested for NULL explicitly,
    -- because a CHECK that evaluates to NULL passes.
    CONSTRAINT ck_findings_lines CHECK (
        (line_start IS NULL AND line_end IS NULL)
        OR (line_start IS NOT NULL AND line_end IS NOT NULL AND file_path IS NOT NULL
            AND line_start >= 1 AND line_end >= line_start)),
    CONSTRAINT ck_findings_snippet CHECK ((code_snippet IS NULL) = (snippet_start_line IS NULL))
);

-- PostgreSQL does not index a foreign key by itself; the report reads findings by analysis.
CREATE INDEX idx_findings_analysis ON findings (analysis_id);

-- One user's request to review a repository. Carries the state of the job.
CREATE TABLE reviews (
    id            UUID PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    repo_owner    VARCHAR(39)  NOT NULL,
    repo_name     VARCHAR(100) NOT NULL,
    branch        VARCHAR(255),
    commit_sha    CHAR(40),
    status        VARCHAR(20)  NOT NULL,
    stage         VARCHAR(20),
    chunks_done   INT          NOT NULL DEFAULT 0,
    chunks_total  INT          NOT NULL DEFAULT 0,
    attempts      INT          NOT NULL DEFAULT 0,
    run_after     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    error_code    VARCHAR(40),
    error_message VARCHAR(500),
    analysis_id   BIGINT,
    cache_hit     BOOLEAN      NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    -- No ON DELETE action: a user or an analysis that a review points to cannot be deleted.
    CONSTRAINT fk_reviews_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_reviews_analysis FOREIGN KEY (analysis_id) REFERENCES analyses (id),
    CONSTRAINT ck_reviews_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_reviews_stage CHECK (stage IN ('FETCHING', 'ANALYZING', 'SCORING')),
    CONSTRAINT ck_reviews_repo_lowercase
        CHECK (repo_owner = lower(repo_owner) AND repo_name = lower(repo_name)),
    CONSTRAINT ck_reviews_commit_sha CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    CONSTRAINT ck_reviews_progress CHECK (chunks_done >= 0 AND chunks_done <= chunks_total),
    CONSTRAINT ck_reviews_attempts CHECK (attempts >= 0),
    -- A review has an analysis exactly when it is completed.
    CONSTRAINT ck_reviews_completed_has_analysis
        CHECK ((status = 'COMPLETED') = (analysis_id IS NOT NULL)),
    CONSTRAINT ck_reviews_failed_has_error CHECK (status <> 'FAILED' OR error_code IS NOT NULL)
);

-- A user's history, newest first.
CREATE INDEX idx_reviews_user_created ON reviews (user_id, created_at DESC);
-- The worker's search for the next job that is due.
CREATE INDEX idx_reviews_status_run_after ON reviews (status, run_after);
