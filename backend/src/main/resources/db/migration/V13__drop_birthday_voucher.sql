-- The WhatsApp birthday voucher (B1 F1 BDG) is retired: the only birthday gift is
-- the Buy 3 Free 1 upsize, which needs no code. Nothing else takes a voucher code.

ALTER TABLE sale_order_promotion DROP COLUMN voucher_code;

ALTER TABLE promotion
    DROP CONSTRAINT promotion_voucher_prefix_ck,
    DROP COLUMN voucher_prefix;
