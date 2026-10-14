-- Promotions and member loyalty, as run at the outlet.
--
-- A promotional item (e.g. "B3 F1 BC Caramel") is valued at its normal price and given free when
-- its promotion applies; the deduction is recorded per line. Every promotional item needs a member
-- on the sale. Members earn points on what they pay and spend them on Member Redemption items.

CREATE TABLE promotion (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- As printed under the item on the receipt, e.g. "Buy 3 Free 1 Promo".
    name            VARCHAR(80) NOT NULL,
    type            VARCHAR(30) NOT NULL,
    -- BUY_X_GET_FREE: qualifying items bought per free item.
    buy_quantity    SMALLINT,
    -- MIN_SPEND: the sale total needed.
    min_spend       NUMERIC(10, 2),
    -- POINTS_REDEMPTION: points per item.
    points_cost     INT,
    -- At most this many items of the promotion per sale (NULL: only the rule limits it).
    max_per_sale    SMALLINT,
    -- At most one item per sale across promotions in the same group (e.g. one free bag).
    exclusive_group VARCHAR(40),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT promotion_name_uq UNIQUE (name),
    CONSTRAINT promotion_type_ck CHECK (type IN
        ('BUY_X_GET_FREE', 'MIN_SPEND', 'POINTS_REDEMPTION', 'REGISTRATION_REWARD', 'BIRTHDAY_REWARD')),
    CONSTRAINT promotion_params_ck CHECK (
        (type = 'BUY_X_GET_FREE') = (buy_quantity IS NOT NULL)
        AND (type = 'MIN_SPEND') = (min_spend IS NOT NULL)
        AND (type = 'POINTS_REDEMPTION') = (points_cost IS NOT NULL)),
    CONSTRAINT promotion_values_ck CHECK (
        (buy_quantity IS NULL OR buy_quantity > 0)
        AND (min_spend IS NULL OR min_spend > 0)
        AND (points_cost IS NULL OR points_cost > 0)
        AND (max_per_sale IS NULL OR max_per_sale > 0))
);

ALTER TABLE item ADD COLUMN promotion_id BIGINT REFERENCES promotion (id);

-- Items in these categories count towards "Buy 3" (stand pouches, cans, gift boxes...).
ALTER TABLE category ADD COLUMN counts_for_promotions BOOLEAN NOT NULL DEFAULT FALSE;

-- The promotion that made a line free, snapshotted like a discount.
CREATE TABLE sale_order_promotion (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT         NOT NULL REFERENCES sale_order (id),
    line_id      BIGINT         NOT NULL REFERENCES sale_order_line (id),
    promotion_id BIGINT         NOT NULL REFERENCES promotion (id),
    name         VARCHAR(80)    NOT NULL,
    type         VARCHAR(30)    NOT NULL,
    amount       NUMERIC(12, 2) NOT NULL,
    -- Points the line costs the member (Member Redemption), 0 otherwise.
    points       INT            NOT NULL DEFAULT 0,
    CONSTRAINT sale_order_promotion_amount_ck CHECK (amount >= 0),
    CONSTRAINT sale_order_promotion_points_ck CHECK (points >= 0),
    CONSTRAINT sale_order_promotion_line_uq UNIQUE (line_id)
);

CREATE INDEX sale_order_promotion_order_idx ON sale_order_promotion (order_id);
-- "Has this member already had their registration / birthday reward?"
CREATE INDEX sale_order_promotion_type_idx ON sale_order_promotion (type, order_id);

-- ---- Loyalty ----

-- Kept apart from customer so earning points never conflicts with a staff edit of the customer.
-- The row is locked while a sale settles points, so two terminals cannot spend the same points.
CREATE TABLE loyalty_account (
    customer_id    BIGINT      PRIMARY KEY REFERENCES customer (id),
    points_balance INT         NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Every change to a balance; the balance is the sum of its entries.
CREATE TABLE loyalty_entry (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT      NOT NULL REFERENCES customer (id),
    order_id    BIGINT      NOT NULL REFERENCES sale_order (id),
    type        VARCHAR(20) NOT NULL,
    points      INT         NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT loyalty_entry_type_ck CHECK (type IN ('EARN', 'REDEEM', 'REVERSE_EARN', 'REVERSE_REDEEM')),
    CONSTRAINT loyalty_entry_sign_ck CHECK (
        (type IN ('EARN', 'REVERSE_REDEEM') AND points > 0)
        OR (type IN ('REDEEM', 'REVERSE_EARN') AND points < 0))
);

CREATE INDEX loyalty_entry_customer_idx ON loyalty_entry (customer_id, created_at);
CREATE INDEX loyalty_entry_order_idx ON loyalty_entry (order_id);
CREATE INDEX loyalty_entry_created_at_idx ON loyalty_entry (created_at);

-- The receipt's LOYALTY PROGRAM block: points before the sale, earned, redeemed.
ALTER TABLE sale_order
    ADD COLUMN loyalty_points_before   INT,
    ADD COLUMN loyalty_points_earned   INT,
    ADD COLUMN loyalty_points_redeemed INT,
    ADD CONSTRAINT sale_order_loyalty_ck CHECK (
        (loyalty_points_before IS NULL) = (loyalty_points_earned IS NULL)
        AND (loyalty_points_before IS NULL) = (loyalty_points_redeemed IS NULL)
        AND (loyalty_points_earned IS NULL OR loyalty_points_earned >= 0)
        AND (loyalty_points_redeemed IS NULL OR loyalty_points_redeemed >= 0));
