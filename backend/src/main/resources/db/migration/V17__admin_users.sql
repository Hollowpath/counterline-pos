-- Head-office accounts for the back-office dashboard: they log in
-- with email and password and see every outlet. Outlet managers log in to the dashboard with their
-- staff PIN instead and see their own outlet.

CREATE TABLE admin_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    -- BCrypt: a salted, slow hash (unlike PINs, passwords never need looking up by value).
    password_hash VARCHAR(100) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT admin_user_email_uq UNIQUE (email),
    CONSTRAINT admin_user_email_lower_ck CHECK (email = lower(email))
);
