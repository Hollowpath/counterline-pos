-- Vouchers and member adjustments:
--  * vouchers are physical, used once, and expire. Head office issues them in batches; each has a
--    printed code that the cashier keys in. Store credit is not used.
--  * staff can adjust a member's points (with a reason), and give back a free item a member was
--    owed but not given ("the staff forgot to give the free item").

-- ---- Vouchers ----

-- A voucher takes money off like a discount, and is the sale's one discount. Each batch has its
-- own discount (scope VOUCHER: never offered on the till's discount lists), so reports show
-- voucher redemptions with the other discounts.
ALTER TABLE discount DROP CONSTRAINT discount_scope_ck;
ALTER TABLE discount ADD CONSTRAINT discount_scope_ck CHECK (scope IN ('ITEM', 'ORDER', 'BOTH', 'VOUCHER'));

CREATE TABLE voucher_batch (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    discount_id BIGINT       NOT NULL REFERENCES discount (id),
    -- Valid until the end of this day (outlet time).
    expires_on  DATE         NOT NULL,
    created_by  VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT voucher_batch_discount_uq UNIQUE (discount_id)
);

CREATE TABLE voucher (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id     BIGINT      NOT NULL REFERENCES voucher_batch (id),
    code         VARCHAR(12) NOT NULL,
    -- The sale it was used on (placed or paid); cleared if that sale is voided.
    used_order_id BIGINT     REFERENCES sale_order (id),
    used_at      TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT voucher_code_uq UNIQUE (code),
    CONSTRAINT voucher_code_ck CHECK (code ~ '^[A-Z0-9]{8,12}$'),
    CONSTRAINT voucher_used_ck CHECK ((used_order_id IS NULL) = (used_at IS NULL))
);

CREATE INDEX voucher_batch_idx ON voucher (batch_id);
CREATE INDEX voucher_used_order_idx ON voucher (used_order_id) WHERE used_order_id IS NOT NULL;

-- ---- Points adjustments ----

ALTER TABLE loyalty_entry ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE loyalty_entry
    ADD COLUMN reason      VARCHAR(200),
    ADD COLUMN adjusted_by VARCHAR(100);
ALTER TABLE loyalty_entry DROP CONSTRAINT loyalty_entry_type_ck;
ALTER TABLE loyalty_entry DROP CONSTRAINT loyalty_entry_sign_ck;
ALTER TABLE loyalty_entry
    ADD CONSTRAINT loyalty_entry_type_ck CHECK (type IN ('EARN', 'REDEEM', 'REVERSE_EARN', 'REVERSE_REDEEM', 'ADJUST')),
    ADD CONSTRAINT loyalty_entry_sign_ck CHECK (
        (type IN ('EARN', 'REVERSE_REDEEM') AND points > 0)
        OR (type IN ('REDEEM', 'REVERSE_EARN') AND points < 0)
        OR (type = 'ADJUST' AND points <> 0)),
    -- A sale's entries belong to it; an adjustment has no sale, and says why and who.
    ADD CONSTRAINT loyalty_entry_adjust_ck CHECK (
        (type = 'ADJUST') = (order_id IS NULL)
        AND (type = 'ADJUST') = (reason IS NOT NULL)
        AND (type = 'ADJUST') = (adjusted_by IS NOT NULL));
ALTER TABLE loyalty_account ADD CONSTRAINT loyalty_account_balance_ck CHECK (points_balance >= 0);

-- ---- Free items owed to a member ----

CREATE TABLE member_owed_item (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id    BIGINT       NOT NULL REFERENCES customer (id),
    promotion_id   BIGINT       NOT NULL REFERENCES promotion (id),
    quantity       SMALLINT     NOT NULL,
    quantity_used  SMALLINT     NOT NULL DEFAULT 0,
    reason         VARCHAR(200) NOT NULL,
    given_by       VARCHAR(100) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT member_owed_item_quantity_ck CHECK (quantity > 0 AND quantity_used BETWEEN 0 AND quantity)
);

CREATE INDEX member_owed_item_open_idx ON member_owed_item (customer_id, promotion_id) WHERE quantity_used < quantity;
