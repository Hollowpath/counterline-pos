-- 1. A member's points balance may go below zero. A refund takes back every point its sale earned, also those
--    spent since; later purchases pay the debt off. (Redemptions and manual take-aways still need the points.)
ALTER TABLE loyalty_account DROP CONSTRAINT loyalty_account_balance_ck;

-- 2. What an order's promotions are judged by is fixed when it is placed (and again when it is edited): the instant
--    its dates, birth month and registration window are read at, and, per line, whether the item's folder counted
--    towards promotions and whether the item was a seasonal flavour (with its window). Paying later honours these;
--    only the member's points and the once-only rewards are looked at again.
ALTER TABLE sale_order ADD COLUMN eligibility_at TIMESTAMPTZ;
UPDATE sale_order SET eligibility_at = created_at;
ALTER TABLE sale_order ALTER COLUMN eligibility_at SET NOT NULL;

ALTER TABLE sale_order_line
    ADD COLUMN counts_for_promotions BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN seasonal              BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN seasonal_from         DATE,
    ADD COLUMN seasonal_until        DATE;
UPDATE sale_order_line l
SET counts_for_promotions = c.counts_for_promotions, seasonal = i.seasonal,
    seasonal_from = i.seasonal_from, seasonal_until = i.seasonal_until
FROM item i JOIN category c ON c.id = i.category_id
WHERE l.item_id = i.id;
