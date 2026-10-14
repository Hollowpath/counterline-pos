-- Till devices, seasonal items and member rules:
--  * an iPad is set up once for an outlet and only head office (or the outlet's manager) can reset
--    it, from the dashboard: tills are registered here with a secret key the iPad keeps;
--  * seasonal flavours are ordinary items (e.g. in Paper Can) marked as seasonal, instead of a
--    folder of their own: the seasonal-flavour promotion counts items with that mark;
--  * the free recycle bags (S and M) are given without a membership;
--  * a discount can be barred from members' sales (the staff discount).

CREATE TABLE till_device (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outlet_id     BIGINT       NOT NULL REFERENCES outlet (id),
    -- "iPad 1", "iPad 2"… numbered per outlet.
    label         VARCHAR(40)  NOT NULL,
    -- SHA-256 (hex) of the key the iPad sends when staff log in; the key itself is never stored.
    key_hash      VARCHAR(64)  NOT NULL UNIQUE,
    set_up_by     BIGINT       NOT NULL REFERENCES staff (id),
    set_up_at     TIMESTAMPTZ  NOT NULL,
    last_login_at TIMESTAMPTZ,
    -- Set when the iPad is reset from the dashboard; it then has to be set up again.
    reset_at      TIMESTAMPTZ,
    reset_by      VARCHAR(120),
    CONSTRAINT till_device_reset_ck CHECK ((reset_at IS NULL) = (reset_by IS NULL))
);

CREATE INDEX till_device_outlet_idx ON till_device (outlet_id) WHERE reset_at IS NULL;

ALTER TABLE item ADD COLUMN seasonal BOOLEAN NOT NULL DEFAULT FALSE;

-- The seasonal-flavour promotion counted the items of one folder; it now counts seasonal items.
ALTER TABLE promotion ADD COLUMN seasonal_items_only BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE item i SET seasonal = TRUE
FROM promotion p
WHERE p.qualifying_category_id = i.category_id;
UPDATE promotion SET seasonal_items_only = TRUE WHERE qualifying_category_id IS NOT NULL;

ALTER TABLE promotion DROP CONSTRAINT promotion_params_ck;
ALTER TABLE promotion DROP COLUMN qualifying_category_id;
ALTER TABLE promotion
    ADD CONSTRAINT promotion_params_ck CHECK (
        (type IN ('BUY_X_GET_FREE', 'BIRTHDAY_REWARD')) = (buy_quantity IS NOT NULL)
        AND (type = 'MIN_SPEND') = (min_spend IS NOT NULL)
        AND (type = 'POINTS_REDEMPTION') = (points_cost IS NOT NULL)
        AND (NOT seasonal_items_only OR type = 'BUY_X_GET_FREE'));

-- Promotional items need a member on the sale, except these (the free recycle bags).
ALTER TABLE promotion ADD COLUMN members_only BOOLEAN NOT NULL DEFAULT TRUE;

-- A discount that can't be given on a member's sale (the staff discount).
ALTER TABLE discount ADD COLUMN not_for_members BOOLEAN NOT NULL DEFAULT FALSE;
