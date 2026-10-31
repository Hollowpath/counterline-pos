-- Who changed what, durably.
--
-- 1. Staff administration: an append-only log of the actor (type and stable id), the person changed, the action and when.
--    It records what was done, never a PIN, password, hash or e-mail.
-- 2. Timecard corrections: the stable identity of whoever made the correction beside the display name already kept for reports.

CREATE TABLE staff_audit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- ADMIN: admin_user.id (a head-office account). MANAGER: staff.id of an outlet manager. No foreign key: the id's table depends on the type.
    actor_type  VARCHAR(10) NOT NULL,
    actor_id    BIGINT      NOT NULL,
    staff_id    BIGINT      NOT NULL REFERENCES staff (id),
    action      VARCHAR(20) NOT NULL,
    CONSTRAINT staff_audit_actor_type_ck CHECK (actor_type IN ('ADMIN', 'MANAGER')),
    CONSTRAINT staff_audit_action_ck CHECK (action IN
        ('CREATE', 'EDIT', 'PIN_RESET', 'LOGIN_CHANGED', 'LOGIN_REMOVED', 'DEACTIVATE', 'REACTIVATE'))
);

-- What happened to a person (also serves the foreign key), and what an actor did.
CREATE INDEX staff_audit_staff_idx ON staff_audit (staff_id, occurred_at DESC, id DESC);
CREATE INDEX staff_audit_actor_idx ON staff_audit (actor_type, actor_id, occurred_at DESC, id DESC);

CREATE FUNCTION staff_audit_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'staff_audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER staff_audit_append_only_tg
    BEFORE UPDATE OR DELETE ON staff_audit
    FOR EACH ROW EXECUTE FUNCTION staff_audit_append_only();

-- Corrections made before this migration only ever kept a name, so who they were cannot be recovered: they are marked
-- LEGACY with no id, never guessed from the name (names are not unique and people are renamed).
ALTER TABLE timecard_event
    ADD COLUMN corrected_by_type VARCHAR(10),
    ADD COLUMN corrected_by_id   BIGINT;

UPDATE timecard_event SET corrected_by_type = 'LEGACY' WHERE corrected_by IS NOT NULL;

ALTER TABLE timecard_event
    ADD CONSTRAINT timecard_event_corrector_ck CHECK (
        (corrected_by IS NULL AND corrected_by_type IS NULL AND corrected_by_id IS NULL)
        -- Each branch states what is not null: a CHECK passes on an unknown result, so a missing type must not slip through.
        OR (corrected_by IS NOT NULL AND corrected_by_type IS NOT NULL AND corrected_by_type IN ('ADMIN', 'MANAGER')
            AND corrected_by_id IS NOT NULL)
        OR (corrected_by IS NOT NULL AND corrected_by_type IS NOT NULL AND corrected_by_type = 'LEGACY' AND corrected_by_id IS NULL));

-- The corrections an actor made, newest first. Partial: most events are not corrections.
CREATE INDEX timecard_event_corrector_idx ON timecard_event (corrected_by_type, corrected_by_id, corrected_at DESC)
    WHERE corrected_by IS NOT NULL;
