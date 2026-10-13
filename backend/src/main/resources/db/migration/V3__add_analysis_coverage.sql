-- How much of the repository an analysis covers. Both numbers belong to the analysis and not
-- to a review: every review of the commit shares them, and a review served from the cache
-- never reads the files.
ALTER TABLE analyses
    -- Files a provider could not review (answer cut off or content refused). Above zero the
    -- analysis is partial.
    ADD COLUMN files_failed INT NOT NULL DEFAULT 0,
    -- Lines of the files that were reviewed: the second input of the score formula, stored
    -- so that the scores can be checked from the findings. No default on purpose: there is
    -- no true value to assume, and a made-up 0 would look like data.
    ADD COLUMN lines_analyzed INT NOT NULL,
    ADD CONSTRAINT ck_analyses_files_failed CHECK (files_failed >= 0),
    ADD CONSTRAINT ck_analyses_lines_analyzed CHECK (lines_analyzed >= 0);
