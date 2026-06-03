-- ──────────────────────────────────────────────────────────────────────────
-- V1 — Initial schema
-- ──────────────────────────────────────────────────────────────────────────

-- jobs: one row per processing run
CREATE TABLE jobs (
                      id           UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                      status       VARCHAR(30)  NOT NULL DEFAULT 'pending',
                      total_urls   INTEGER      NOT NULL DEFAULT 0,
                      created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
                      started_at   TIMESTAMP,
                      completed_at TIMESTAMP
);

-- tasks: one row per URL per job
CREATE TABLE tasks (
                       id                UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                       job_id            UUID        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                       url               TEXT        NOT NULL,
                       status            VARCHAR(20) NOT NULL DEFAULT 'pending',
                       error             TEXT,
                       records_extracted INTEGER     NOT NULL DEFAULT 0,
                       attempt_count     INTEGER     NOT NULL DEFAULT 0,
                       started_at        TIMESTAMP,
                       completed_at      TIMESTAMP
);

CREATE INDEX idx_tasks_job_id ON tasks(job_id);
CREATE INDEX idx_tasks_status  ON tasks(status);

-- feed_records: one row per extracted article/item
CREATE TABLE feed_records (
                              id             UUID      NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                              task_id        UUID      NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                              job_id         UUID      NOT NULL,
                              title          TEXT,
                              link           TEXT,
                              published_date TIMESTAMP,
                              author         TEXT,
                              summary        TEXT,
                              created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_records_task_id ON feed_records(task_id);
CREATE INDEX idx_records_job_id  ON feed_records(job_id);
