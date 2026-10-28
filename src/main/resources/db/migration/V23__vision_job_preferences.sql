-- TENTATIVE MIGRATION NUMBER: the Neon database already has an unrelated V21 that is not in this
-- repository, and V22 is also tentative. This file MUST be renumbered (to the next free version) before it is merged/deployed.
--
-- `preferences` is a plain-TEXT JSON snapshot of the structured request. It deviates deliberately from the
-- "no JSON blob" guidance because it is write-once and never queried; TEXT (not jsonb) keeps H2/Postgres
-- parity under ddl-auto=validate.

ALTER TABLE vision_jobs ADD COLUMN preferences         TEXT;
ALTER TABLE vision_jobs ADD COLUMN built_prompt        TEXT;
ALTER TABLE vision_jobs ADD COLUMN image_strength      NUMERIC(3, 2);
ALTER TABLE vision_jobs ADD COLUMN request_fingerprint VARCHAR(64);

CREATE INDEX idx_vision_jobs_user_fingerprint ON vision_jobs (user_id, request_fingerprint, created_at);
