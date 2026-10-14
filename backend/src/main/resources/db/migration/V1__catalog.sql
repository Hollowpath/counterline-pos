-- Catalogue as presented on the sell screen:
-- a quickgrid tab holds category folders placed at fixed grid cells,
-- and each category holds items in display order.

CREATE TABLE quickgrid_tab (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(60) NOT NULL,
    sort_order SMALLINT    NOT NULL,
    CONSTRAINT quickgrid_tab_name_uq UNIQUE (name),
    CONSTRAINT quickgrid_tab_sort_order_uq UNIQUE (sort_order)
);

-- A category without a tab is not on the grid but still exists in the library and reports.
CREATE TABLE category (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     VARCHAR(80) NOT NULL,
    tab_id   BIGINT REFERENCES quickgrid_tab (id),
    grid_row SMALLINT,
    grid_col SMALLINT,
    CONSTRAINT category_name_uq UNIQUE (name),
    CONSTRAINT category_grid_cell_uq UNIQUE (tab_id, grid_row, grid_col),
    CONSTRAINT category_grid_cell_ck CHECK (
        (tab_id IS NULL AND grid_row IS NULL AND grid_col IS NULL)
        OR (tab_id IS NOT NULL AND grid_row BETWEEN 1 AND 5 AND grid_col BETWEEN 1 AND 5)
    )
);

CREATE TABLE item (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id BIGINT        NOT NULL REFERENCES category (id),
    name        VARCHAR(120)  NOT NULL,
    price       NUMERIC(10, 2) NOT NULL,
    image_url   VARCHAR(500),
    sort_order  INT           NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT item_price_ck CHECK (price >= 0),
    CONSTRAINT item_category_name_uq UNIQUE (category_id, name),
    -- Also serves the "items of a category in display order" query.
    CONSTRAINT item_category_sort_order_uq UNIQUE (category_id, sort_order)
);
