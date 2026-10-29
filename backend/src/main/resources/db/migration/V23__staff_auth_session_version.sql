-- Every staff-issued token (till, manager dashboard, manager approval) carries the authentication
-- session version it was issued under, and only the exact current value is accepted. Anything that
-- changes how a staff member authenticates (deactivation, role change, moving outlet, a new PIN, a
-- changed or removed dashboard login, closing their outlet) raises the version, so no earlier token
-- can become valid again, whatever is later reactivated, restored or recreated.
ALTER TABLE staff ADD COLUMN auth_session_version INTEGER NOT NULL DEFAULT 0;
