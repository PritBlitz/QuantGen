-- V2: saved billers (F-PAY-11), so a bill can be paid again without retyping it and Autopilot PAY_BILL has a target.
CREATE TABLE saved_billers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users (id),
    biller_id        UUID         NOT NULL REFERENCES billers (id),
    consumer_number  VARCHAR(20)  NOT NULL,
    nickname         VARCHAR(40),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT saved_billers_uq UNIQUE (user_id, biller_id, consumer_number)
);
CREATE INDEX saved_billers_user_idx ON saved_billers (user_id, created_at);
