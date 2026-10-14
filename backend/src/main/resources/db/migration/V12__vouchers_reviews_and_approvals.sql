-- Rewards and controls:
--  * the WhatsApp birthday voucher: the customer shows a code starting "BDG"; the cashier keys it in;
--  * the Google Review reward: once per member, shown to the staff (no code);
--  * department "Special Approved" comps need a manager's approval at the till.

ALTER TABLE promotion DROP CONSTRAINT promotion_type_ck;

ALTER TABLE promotion
    ADD CONSTRAINT promotion_type_ck CHECK (type IN
        ('BUY_X_GET_FREE', 'MIN_SPEND', 'POINTS_REDEMPTION', 'REGISTRATION_REWARD', 'REVIEW_REWARD',
         'BIRTHDAY_REWARD')),
    -- The promotion needs a voucher code starting with this (e.g. 'BDG'); NULL = no code.
    ADD COLUMN voucher_prefix VARCHAR(10),
    ADD CONSTRAINT promotion_voucher_prefix_ck CHECK (voucher_prefix IS NULL OR voucher_prefix <> '');

ALTER TABLE sale_order_promotion ADD COLUMN voucher_code VARCHAR(40);

ALTER TABLE discount ADD COLUMN requires_approval BOOLEAN NOT NULL DEFAULT FALSE;

-- The manager who approved a discount that needs approval.
ALTER TABLE sale_order_discount ADD COLUMN approved_by BIGINT REFERENCES staff (id);
