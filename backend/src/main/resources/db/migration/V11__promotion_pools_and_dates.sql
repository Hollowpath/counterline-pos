-- Promotion rules:
--  * the Birthday Reward is an upsize of Buy 3 Free 1: every 3 items bought earn one free item,
--    either the baby can or (in the member's birth month) the birthday can;
--  * a seasonal-flavour promotion gives a free item for each item bought from one folder;
--  * Spend promotions repeat per multiple of the threshold (one tote bag per RM150);
--  * limited-time promotions run between two dates.

ALTER TABLE promotion DROP CONSTRAINT promotion_params_ck;

ALTER TABLE promotion
    -- BUY_X_GET_FREE only: count items bought from this category instead of every qualifying one.
    ADD COLUMN qualifying_category_id BIGINT REFERENCES category (id),
    ADD COLUMN starts_on DATE,
    ADD COLUMN ends_on   DATE;

UPDATE promotion SET buy_quantity = 3 WHERE type = 'BIRTHDAY_REWARD' AND buy_quantity IS NULL;

ALTER TABLE promotion
    ADD CONSTRAINT promotion_params_ck CHECK (
        (type IN ('BUY_X_GET_FREE', 'BIRTHDAY_REWARD')) = (buy_quantity IS NOT NULL)
        AND (type = 'MIN_SPEND') = (min_spend IS NOT NULL)
        AND (type = 'POINTS_REDEMPTION') = (points_cost IS NOT NULL)
        AND (qualifying_category_id IS NULL OR type = 'BUY_X_GET_FREE')),
    ADD CONSTRAINT promotion_dates_ck CHECK (starts_on IS NULL OR ends_on IS NULL OR starts_on <= ends_on);
