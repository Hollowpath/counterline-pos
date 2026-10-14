-- Item options ("CHOOSE 10"), line and order discounts, and custom amounts.

-- ---- Catalogue: modifier groups and options ----

-- A group requires exactly choose_exactly selections in total (options can be picked several times).
CREATE TABLE modifier_group (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id        BIGINT      NOT NULL REFERENCES item (id),
    name           VARCHAR(80) NOT NULL,
    choose_exactly SMALLINT    NOT NULL,
    sort_order     SMALLINT    NOT NULL,
    CONSTRAINT modifier_group_choose_ck CHECK (choose_exactly BETWEEN 1 AND 99),
    CONSTRAINT modifier_group_item_sort_uq UNIQUE (item_id, sort_order)
);

CREATE TABLE modifier_option (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id   BIGINT         NOT NULL REFERENCES modifier_group (id),
    name       VARCHAR(80)    NOT NULL,
    price      NUMERIC(10, 2) NOT NULL DEFAULT 0,
    sort_order SMALLINT       NOT NULL,
    active     BOOLEAN        NOT NULL DEFAULT TRUE,
    CONSTRAINT modifier_option_price_ck CHECK (price >= 0),
    CONSTRAINT modifier_option_group_sort_uq UNIQUE (group_id, sort_order)
);

-- ---- Discounts ----

CREATE TABLE discount (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(80)    NOT NULL,
    kind       VARCHAR(10)    NOT NULL,
    value      NUMERIC(10, 2) NOT NULL,
    -- ITEM: offered in the item dialog; ORDER: on the % tab; BOTH: in both places.
    scope      VARCHAR(10)    NOT NULL,
    sort_order SMALLINT       NOT NULL,
    active     BOOLEAN        NOT NULL DEFAULT TRUE,
    CONSTRAINT discount_name_uq UNIQUE (name),
    CONSTRAINT discount_kind_ck CHECK (kind IN ('PERCENT', 'AMOUNT')),
    CONSTRAINT discount_scope_ck CHECK (scope IN ('ITEM', 'ORDER', 'BOTH')),
    CONSTRAINT discount_value_ck CHECK (value > 0 AND (kind <> 'PERCENT' OR value <= 100))
);

-- ---- Orders: amounts before and after discounts ----

-- subtotal = sum of line subtotals (gross); total = subtotal − discount_total.
ALTER TABLE sale_order
    ADD COLUMN subtotal       NUMERIC(12, 2),
    ADD COLUMN discount_total NUMERIC(12, 2) NOT NULL DEFAULT 0;
UPDATE sale_order SET subtotal = total;
ALTER TABLE sale_order
    ALTER COLUMN subtotal SET NOT NULL,
    ADD CONSTRAINT sale_order_discount_ck CHECK (discount_total >= 0 AND discount_total <= subtotal),
    ADD CONSTRAINT sale_order_amounts_ck CHECK (total = subtotal - discount_total);

-- A line is either a catalogue item or a custom amount (item_id NULL).
-- line_subtotal = (unit_price + modifier_unit_price) × quantity; line_total = line_subtotal − discount_total.
ALTER TABLE sale_order_line
    ALTER COLUMN item_id DROP NOT NULL,
    ADD COLUMN note                VARCHAR(200),
    ADD COLUMN modifier_unit_price NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN line_subtotal       NUMERIC(12, 2),
    ADD COLUMN discount_total      NUMERIC(12, 2) NOT NULL DEFAULT 0;
UPDATE sale_order_line SET line_subtotal = line_total;
ALTER TABLE sale_order_line
    ALTER COLUMN line_subtotal SET NOT NULL,
    DROP CONSTRAINT sale_order_line_total_ck,
    ADD CONSTRAINT sale_order_line_subtotal_ck CHECK (line_subtotal = (unit_price + modifier_unit_price) * quantity),
    ADD CONSTRAINT sale_order_line_total_ck CHECK (line_total = line_subtotal - discount_total),
    ADD CONSTRAINT sale_order_line_discount_ck CHECK (discount_total >= 0 AND discount_total <= line_subtotal);

-- Chosen options per unit of the line, snapshotted like the item itself.
CREATE TABLE sale_order_line_modifier (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id     BIGINT         NOT NULL REFERENCES sale_order_line (id),
    option_id   BIGINT         NOT NULL REFERENCES modifier_option (id),
    group_name  VARCHAR(80)    NOT NULL,
    option_name VARCHAR(80)    NOT NULL,
    unit_price  NUMERIC(10, 2) NOT NULL,
    quantity    SMALLINT       NOT NULL,
    CONSTRAINT sale_order_line_modifier_quantity_ck CHECK (quantity > 0)
);

CREATE INDEX sale_order_line_modifier_line_idx ON sale_order_line_modifier (line_id);

-- Discounts applied, with the amount they took off. line_id NULL = an order-level discount.
CREATE TABLE sale_order_discount (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    BIGINT         NOT NULL REFERENCES sale_order (id),
    line_id     BIGINT REFERENCES sale_order_line (id),
    discount_id BIGINT         NOT NULL REFERENCES discount (id),
    name        VARCHAR(80)    NOT NULL,
    kind        VARCHAR(10)    NOT NULL,
    value       NUMERIC(10, 2) NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    CONSTRAINT sale_order_discount_amount_ck CHECK (amount >= 0)
);

CREATE INDEX sale_order_discount_order_idx ON sale_order_discount (order_id);
CREATE INDEX sale_order_discount_line_idx ON sale_order_discount (line_id) WHERE line_id IS NOT NULL;
