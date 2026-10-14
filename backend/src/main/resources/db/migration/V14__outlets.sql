-- Multiple outlets. Each POS terminal is bound to one outlet; sales, registers, clock-ins and
-- reports belong to it. The catalogue, staff and members (with their points) are shared by the
-- chain. All outlets use the application's time zone (pos.outlet.time-zone).

CREATE TABLE outlet (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Short code, e.g. for the terminal set-up screen and the dashboard.
    code       VARCHAR(10)  NOT NULL,
    name       VARCHAR(80)  NOT NULL,
    address    VARCHAR(200),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT outlet_code_uq UNIQUE (code),
    CONSTRAINT outlet_name_uq UNIQUE (name)
);

-- Everything recorded so far happened at the one outlet there was.
INSERT INTO outlet (code, name) VALUES ('O1', 'Outlet 1');

ALTER TABLE sale_order ADD COLUMN outlet_id BIGINT REFERENCES outlet (id);
UPDATE sale_order SET outlet_id = (SELECT id FROM outlet WHERE code = 'O1');
ALTER TABLE sale_order ALTER COLUMN outlet_id SET NOT NULL;

ALTER TABLE register_session ADD COLUMN outlet_id BIGINT REFERENCES outlet (id);
UPDATE register_session SET outlet_id = (SELECT id FROM outlet WHERE code = 'O1');
ALTER TABLE register_session ALTER COLUMN outlet_id SET NOT NULL;

ALTER TABLE timecard_event ADD COLUMN outlet_id BIGINT REFERENCES outlet (id);
UPDATE timecard_event SET outlet_id = (SELECT id FROM outlet WHERE code = 'O1');
ALTER TABLE timecard_event ALTER COLUMN outlet_id SET NOT NULL;

-- At most one open register per outlet.
DROP INDEX register_session_one_open_uq;
CREATE UNIQUE INDEX register_session_one_open_uq ON register_session (outlet_id) WHERE closed_at IS NULL;

-- Past Orders lists one outlet's orders; the daily reports are per outlet (sale_order_closed_at_idx
-- stays for chain-wide totals).
DROP INDEX sale_order_status_created_idx;
CREATE INDEX sale_order_outlet_status_created_idx ON sale_order (outlet_id, status, created_at DESC, id DESC);
CREATE INDEX sale_order_outlet_closed_at_idx ON sale_order (outlet_id, closed_at) WHERE status = 'CLOSED';
CREATE INDEX timecard_event_outlet_occurred_idx ON timecard_event (outlet_id, occurred_at);
