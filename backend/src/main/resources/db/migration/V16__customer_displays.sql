-- Customer displays: a second iPad facing the customer, paired with one POS terminal by an access
-- code. The POS publishes its cart here and the display polls it; a customer who logs in or signs
-- up on the display waits here until the cashier confirms them on the POS. Kept in the database
-- (not in memory) so any API task behind the load balancer can serve either side.

CREATE TABLE customer_display (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outlet_id           BIGINT       NOT NULL REFERENCES outlet (id),
    -- Shown on the POS until a display connects with it.
    access_code         VARCHAR(6)   NOT NULL,
    created_by          BIGINT       NOT NULL REFERENCES staff (id),
    created_at          TIMESTAMPTZ  NOT NULL,
    connected_at        TIMESTAMPTZ,
    display_seen_at     TIMESTAMPTZ,
    -- Disconnected from the POS; the display's token stops working.
    revoked_at          TIMESTAMPTZ,
    -- What the customer sees: the POS's cart (lines, totals, member), as JSON text (only relayed, never queried).
    cart                TEXT,
    -- A member who logged in or signed up on the display, waiting for the cashier.
    pending_customer_id BIGINT REFERENCES customer (id),
    pending_new_member  BOOLEAN      NOT NULL DEFAULT FALSE,
    -- The cashier's answer, shown on the display: CONFIRMED or REJECTED.
    member_result       VARCHAR(10),
    result_customer_id  BIGINT REFERENCES customer (id),
    -- Bumped on every change, so the display can ask "anything newer than version N?".
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT customer_display_code_ck CHECK (access_code ~ '^[0-9]{6}$'),
    CONSTRAINT customer_display_result_ck CHECK (member_result IS NULL OR member_result IN ('CONFIRMED', 'REJECTED'))
);

-- A code identifies a display waiting to connect, so it must be unique among those.
CREATE UNIQUE INDEX customer_display_waiting_code_uq ON customer_display (access_code)
    WHERE connected_at IS NULL AND revoked_at IS NULL;
