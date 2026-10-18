-- Emails fetched with "new mails only" pulls. The Graph message id (an immutable id, requested with
-- Prefer: IdType="ImmutableId", so it survives moves between folders) is the key, so a mail delivered twice
-- (a redelivered delta page, the boundary of a resync) is stored once.
CREATE TABLE email (
    id             VARCHAR(512)             NOT NULL PRIMARY KEY,
    mailbox        VARCHAR(320)             NOT NULL,
    sender_name    VARCHAR(1000),
    sender_address VARCHAR(320),
    subject        VARCHAR(4000),
    received_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    body           CLOB,
    fetched_via    VARCHAR(10)              NOT NULL,
    stored_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT                   NOT NULL
);

-- The newest stored mail per mailbox is where a new sync starts after a completed sync's delta token expired.
CREATE INDEX email_mailbox_received_at ON email (mailbox, received_at);

-- One delta sync per mailbox, shared by rest and sdk mode: Graph's links are plain URLs either can follow.
--   delta_link  where the next pull continues once a sync completed
--   next_link   the next page of a sync that stopped part-way (a failure or the page cap); followed first
--   sync_since  the start point of a sync that hasn't completed yet; an expired next_link restarts from here,
--               so the pages that sync didn't reach yet are not skipped
CREATE TABLE sync_state (
    mailbox    VARCHAR(320)             NOT NULL PRIMARY KEY,
    delta_link VARCHAR(8000),
    next_link  VARCHAR(8000),
    sync_since TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL
);
