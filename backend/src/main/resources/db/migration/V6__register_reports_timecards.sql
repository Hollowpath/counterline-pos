-- Manage Register, Reports and Clock In/Out.

-- A sale is reported on the day it was paid (an order may be placed one day and paid the next).
ALTER TABLE sale_order ADD COLUMN closed_at TIMESTAMPTZ;
UPDATE sale_order o
SET closed_at = COALESCE((SELECT max(p.created_at) FROM payment p WHERE p.order_id = o.id), o.created_at)
WHERE status = 'CLOSED';
ALTER TABLE sale_order
    ADD CONSTRAINT sale_order_closed_at_ck CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL));

-- Daily reports: sales by closed_at, refunds by refunded_at.
CREATE INDEX sale_order_closed_at_idx ON sale_order (closed_at) WHERE status = 'CLOSED';
CREATE INDEX sale_order_refunded_at_idx ON sale_order (refunded_at) WHERE refunded_at IS NOT NULL;

-- Category Sales reports the category an item was in when sold, like the name/price snapshot.
ALTER TABLE sale_order_line ADD COLUMN category_name VARCHAR(80);
UPDATE sale_order_line l
SET category_name = c.name
FROM item i
JOIN category c ON c.id = i.category_id
WHERE i.id = l.item_id;
ALTER TABLE sale_order_line ALTER COLUMN category_name SET NOT NULL;

-- ---- Register sessions ("Yes, we're OPEN") ----

CREATE TABLE register_session (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opened_at    TIMESTAMPTZ  NOT NULL,
    opened_by    BIGINT       NOT NULL REFERENCES staff (id),
    closed_at    TIMESTAMPTZ,
    closed_by    BIGINT REFERENCES staff (id),
    closing_note VARCHAR(500),
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT register_session_closed_ck CHECK ((closed_at IS NULL) = (closed_by IS NULL)),
    CONSTRAINT register_session_order_ck CHECK (closed_at IS NULL OR closed_at >= opened_at)
);

-- At most one open register: a unique index over a constant, restricted to open sessions.
CREATE UNIQUE INDEX register_session_one_open_uq ON register_session ((TRUE)) WHERE closed_at IS NULL;

CREATE TABLE register_movement (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT         NOT NULL REFERENCES register_session (id),
    direction  VARCHAR(3)     NOT NULL,
    amount     NUMERIC(12, 2) NOT NULL,
    reason     VARCHAR(200)   NOT NULL,
    staff_id   BIGINT         NOT NULL REFERENCES staff (id),
    created_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT register_movement_direction_ck CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT register_movement_amount_ck CHECK (amount > 0)
);

CREATE INDEX register_movement_session_idx ON register_movement (session_id, created_at);

-- ---- Clock In/Out ----

CREATE TABLE timecard_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    staff_id    BIGINT      NOT NULL REFERENCES staff (id),
    type        VARCHAR(3)  NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT timecard_event_type_ck CHECK (type IN ('IN', 'OUT'))
);

-- Timecards report (by day) and "is this staff member clocked in?" (latest event per staff).
CREATE INDEX timecard_event_occurred_idx ON timecard_event (occurred_at);
CREATE INDEX timecard_event_staff_idx ON timecard_event (staff_id, occurred_at DESC);
