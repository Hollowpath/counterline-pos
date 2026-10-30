-- A head-office or manager correction closes a shift that was left open: it is an extra OUT event
-- (the IN is never edited or deleted) that says who made it, when, and why.
ALTER TABLE timecard_event
    ADD COLUMN corrected_by     VARCHAR(80),
    ADD COLUMN corrected_reason VARCHAR(200),
    ADD COLUMN corrected_at     TIMESTAMPTZ,
    ADD CONSTRAINT timecard_event_correction_ck CHECK (
        (corrected_by IS NULL AND corrected_reason IS NULL AND corrected_at IS NULL)
        OR (type = 'OUT' AND corrected_by IS NOT NULL AND corrected_reason IS NOT NULL AND corrected_at IS NOT NULL));
