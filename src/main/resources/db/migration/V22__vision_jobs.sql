-- TENTATIVE MIGRATION NUMBER: the Neon database already has an unrelated V21 that is not in this
-- repository. This file MUST be renumbered (to the next free version) before it is merged/deployed.

CREATE TABLE vision_jobs (
    id               UUID PRIMARY KEY,
    user_id          BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    style_prompt     VARCHAR(300)  NOT NULL,
    input_image_url  VARCHAR(1000) NOT NULL,
    result_image_url VARCHAR(1000),
    status           VARCHAR(20)   NOT NULL,
    error            VARCHAR(300),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    completed_at     TIMESTAMP,
    version          BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_vision_jobs_user_created ON vision_jobs (user_id, created_at);
CREATE INDEX idx_vision_jobs_status_created ON vision_jobs (status, created_at);
