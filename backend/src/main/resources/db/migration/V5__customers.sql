-- Customers, searchable by name / phone / email (contains) and reference ID (exact),
-- and attached to sales for the order history and customer analytics.

-- Trigram indexes make case-insensitive "contains" searches (ILIKE '%term%') indexable.
-- pg_trgm ships with PostgreSQL and is supported on Amazon RDS.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE customer (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference_id    VARCHAR(40),
    first_name      VARCHAR(60)  NOT NULL,
    last_name       VARCHAR(60),
    full_name       VARCHAR(121) GENERATED ALWAYS AS (
                        CASE WHEN last_name IS NULL THEN first_name ELSE first_name || ' ' || last_name END) STORED,
    company_name    VARCHAR(120),
    date_of_birth   DATE,
    gender          VARCHAR(10),
    -- Stored normalised: lower-case email, E.164 phone (e.g. +60112345678).
    email           VARCHAR(254),
    phone           VARCHAR(16),
    marketing_email BOOLEAN      NOT NULL DEFAULT FALSE,
    marketing_sms   BOOLEAN      NOT NULL DEFAULT FALSE,
    marketing_call  BOOLEAN      NOT NULL DEFAULT FALSE,
    street_address  VARCHAR(200),
    apt_suite       VARCHAR(60),
    city            VARCHAR(80),
    postal_code     VARCHAR(20),
    country         VARCHAR(60),
    notes           VARCHAR(1000),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    -- Soft delete: past orders keep pointing at the customer they were sold to.
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT customer_gender_ck CHECK (gender IN ('MALE', 'FEMALE')),
    CONSTRAINT customer_phone_format_ck CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT customer_email_lower_ck CHECK (email = lower(email))
);

-- Unique among active customers only, so a deleted customer's phone can be registered again.
CREATE UNIQUE INDEX customer_phone_uq ON customer (phone) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX customer_email_uq ON customer (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX customer_reference_uq ON customer (reference_id) WHERE deleted_at IS NULL;

CREATE INDEX customer_full_name_trgm_idx ON customer USING gin (full_name gin_trgm_ops);
CREATE INDEX customer_phone_trgm_idx ON customer USING gin (phone gin_trgm_ops);
CREATE INDEX customer_email_trgm_idx ON customer USING gin (email gin_trgm_ops);

ALTER TABLE sale_order ADD COLUMN customer_id BIGINT REFERENCES customer (id);

-- Customer analytics and history: orders of one customer.
CREATE INDEX sale_order_customer_idx ON sale_order (customer_id, created_at DESC) WHERE customer_id IS NOT NULL;
