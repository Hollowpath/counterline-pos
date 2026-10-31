-- Head-office sessions can be ended: every admin token carries the account's authentication session version (like a
-- staff member's), raised by a password change or a deactivation, and checked on every request.
ALTER TABLE admin_user ADD COLUMN auth_session_version INT NOT NULL DEFAULT 0;

-- A logged-out token, by its id, until the token would have expired anyway. Only that token ends: the same person's
-- other sessions are untouched.
CREATE TABLE revoked_token (
    token_id   VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX revoked_token_expires_idx ON revoked_token (expires_at);

-- The login throttle admits an attempt before it is checked: the attempts under way on a key are counted apart from the
-- failures, so a burst of parallel guesses is held to the limit but only real failures lock the key.
ALTER TABLE login_throttle ADD COLUMN pending INT NOT NULL DEFAULT 0;
ALTER TABLE login_throttle DROP CONSTRAINT login_throttle_failures_ck;
ALTER TABLE login_throttle ADD CONSTRAINT login_throttle_counts_ck
    CHECK (failures >= 0 AND pending >= 0 AND (failures > 0 OR pending > 0));

-- It forgets rows a window after their last wrong attempt; finding them is an index scan.
CREATE INDEX login_throttle_last_failed_idx ON login_throttle (last_failed_at);
