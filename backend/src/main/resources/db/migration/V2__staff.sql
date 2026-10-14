-- Staff log in with a 6-digit PIN only (no username), so the PIN itself must identify the staff member.
--
-- pin_hmac = HMAC-SHA256(server-side pepper, PIN), hex-encoded.
--   * Deterministic, so login can look a PIN up and the database can enforce uniqueness.
--   * Keyed, so a leaked table cannot be brute-forced offline (only 10^6 PINs exist) without the
--     pepper, which lives in Secrets Manager rather than the database.
CREATE TABLE staff (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name  VARCHAR(120) NOT NULL,
    pin_hmac   VARCHAR(64)  NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT staff_pin_hmac_uq UNIQUE (pin_hmac),
    CONSTRAINT staff_pin_hmac_format_ck CHECK (pin_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT staff_role_ck CHECK (role IN ('STAFF', 'MANAGER'))
);
