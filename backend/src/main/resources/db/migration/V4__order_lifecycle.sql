-- Past orders: paying open orders later, voiding, refunding, and searching.

ALTER TABLE sale_order
    -- Optimistic locking: two terminals changing the same order cannot silently overwrite each other.
    ADD COLUMN version            BIGINT NOT NULL DEFAULT 0,
    -- Idempotency key of the request that paid an OPEN order (a retry returns the paid order).
    ADD COLUMN payment_request_id UUID,
    ADD COLUMN void_reason        VARCHAR(200),
    ADD COLUMN voided_by          BIGINT REFERENCES staff (id),
    ADD COLUMN voided_at          TIMESTAMPTZ,
    ADD COLUMN refund_reason      VARCHAR(200),
    ADD COLUMN refunded_by        BIGINT REFERENCES staff (id),
    ADD COLUMN refunded_at        TIMESTAMPTZ,
    ADD CONSTRAINT sale_order_payment_request_uq UNIQUE (payment_request_id),
    -- Void details are present exactly when the order is voided.
    ADD CONSTRAINT sale_order_void_ck CHECK (
        (status = 'VOIDED') = (voided_at IS NOT NULL)
        AND (voided_at IS NULL) = (void_reason IS NULL)
        AND (voided_at IS NULL) = (voided_by IS NULL)),
    -- A refund is all-or-nothing and only applies to a closed (paid) order.
    ADD CONSTRAINT sale_order_refund_ck CHECK (
        (refunded_at IS NULL) = (refund_reason IS NULL)
        AND (refunded_at IS NULL) = (refunded_by IS NULL)
        AND (refunded_at IS NULL OR status = 'CLOSED'));

-- Past Orders lists one status tab at a time, newest first.
CREATE INDEX sale_order_status_created_idx ON sale_order (status, created_at DESC, id DESC);

-- Receipt-code search is a prefix match (codes start with the date, e.g. "261012");
-- pattern_ops lets LIKE 'prefix%' use the index regardless of the database collation.
CREATE INDEX sale_order_receipt_code_prefix_idx ON sale_order (receipt_code varchar_pattern_ops);
