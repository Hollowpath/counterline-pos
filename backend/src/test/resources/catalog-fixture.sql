-- Promotions too: each test class sets up the ones it needs (promotions-fixture.sql).
TRUNCATE item, category, quickgrid_tab, promotion RESTART IDENTITY CASCADE;

INSERT INTO quickgrid_tab (name, sort_order) VALUES
    ('Promotional', 2),
    ('Selling Items', 1),
    ('Empty Tab', 3);

-- Inserted out of grid order (explicit ids) so ordering is verified, not incidental.
INSERT INTO category (id, name, tab_id, grid_row, grid_col) OVERRIDING SYSTEM VALUE VALUES
    (3, 'Seasonal', 2, 5, 1),
    (2, 'Tin', 2, 1, 2),
    (1, 'Pouch', 2, 1, 1),
    (4, 'Promo', 1, 1, 1),
    (5, 'Library Only', NULL, NULL, NULL);

INSERT INTO item (category_id, name, price, sort_order, active) VALUES
    (1, 'Pouch Cheese',   16.00, 2, TRUE),
    (1, 'Pouch Caramel',  14.50, 1, TRUE),
    (1, 'Pouch Retired',  10.00, 3, FALSE),
    (2, 'Tin Caramel',    29.00, 1, TRUE),
    (4, 'Promo Tin',      26.00, 1, TRUE),
    (5, 'Hidden Item',     5.00, 1, TRUE);
