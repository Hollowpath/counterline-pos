-- Demo catalogue for local development (loaded only with the "local" profile).
--
-- A snack-retail range named by packaging code (WSP white stand pouch, PC paper can 70 g, BC baby
-- can 35 g, CSP crispy roll stand pouch). Folders sit in the top and bottom rows of the grid.
--
-- Repeatable and idempotent: it re-runs whenever this file changes. Categories and items that are
-- no longer listed are taken off the grid and deactivated (never deleted: past sales refer to
-- them), and prices are updated in place.

INSERT INTO quickgrid_tab (name, sort_order) VALUES
    ('Selling Items', 1),
    ('Promotional', 2),
    ('Sample Items', 3)
ON CONFLICT (name) DO NOTHING;

-- ---- Folders ----

CREATE TEMP TABLE demo_category (name, tab_name, grid_row, grid_col) ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('White Stand Pouch',          'Selling Items', 1, 1),
    ('Paper Can',                  'Selling Items', 1, 2),
    ('Crispy Stand Pouch',         'Selling Items', 1, 3),
    ('General Gift Box',           'Selling Items', 1, 4),
    ('Special Items',              'Selling Items', 5, 1),
    ('Other Items',                'Selling Items', 5, 2),
    ('B3 F1 Promo Baby Can',       'Promotional',   1, 1),
    ('Member FOC Paper Can',       'Promotional',   1, 2),
    ('Member FOC Crispy Stand Pouch', 'Promotional', 1, 3),
    ('Seasonal Flavour Promo FOC Baby Can', 'Promotional', 1, 4),
    ('FOC Special Items',          'Promotional',   5, 1),
    ('FOC Other Items',            'Promotional',   5, 2),
    ('Sample White Stand Pouch',   'Sample Items',  1, 1),
    ('Sample Paper Can',           'Sample Items',  1, 2),
    ('Sample Baby Can',            'Sample Items',  1, 3),
    ('Sample Crispy Stand Pouch',  'Sample Items',  1, 4),
    ('Aluminium Sample Pack',      'Sample Items',  5, 1),
    ('Sample FMCGSP',              'Sample Items',  5, 2)
) AS c (name, tab_name, grid_row, grid_col);

-- Clear every grid cell first so folders can move without colliding (category_grid_cell_uq),
-- which also takes folders that are no longer listed off the grid.
UPDATE category SET tab_id = NULL, grid_row = NULL, grid_col = NULL;

INSERT INTO category (name, tab_id, grid_row, grid_col)
SELECT c.name, t.id, c.grid_row, c.grid_col
FROM demo_category c
JOIN quickgrid_tab t ON t.name = c.tab_name
ON CONFLICT (name) DO UPDATE
    SET tab_id = EXCLUDED.tab_id, grid_row = EXCLUDED.grid_row, grid_col = EXCLUDED.grid_col;

-- ---- Flavours, with the price of each pack size ----

CREATE TEMP TABLE flavour (name, wsp, pc, bc) ON COMMIT DROP AS
SELECT * FROM (VALUES
    -- name                        WSP     PC      BC
    ('Original (Sea Salt)',        15.00,   9.50,  6.00),
    ('Caramel',                    16.00,  10.00,  6.50),
    ('Cheese',                     16.00,  10.00,  6.50),
    ('BBQ',                        16.00,  10.00,  6.50),
    ('Cocoa Malt',                 16.00,  10.00,  6.50),
    ('Seaweed',                    16.00,  10.00,  6.50),
    ('Sour Cream & Onion',         16.00,  10.00,  6.50),
    ('Tomato',                     16.00,  10.00,  6.50),
    ('Dark Choc',                  17.00,  11.00,  7.00),
    ('Kimchi',                     17.00,  11.00,  NULL),
    ('Seaweed Wasabi',             NULL,   11.00,  6.50),
    ('Salted Egg',                 18.00,  12.00,  NULL),
    ('Spicy Cuttlefish',           18.00,  12.00,  NULL),
    ('Lobster Cheese',             21.00,  14.00,  NULL),
    ('Tom Yum Goong',              21.00,  14.00,  NULL),
    ('Mala',                       NULL,   14.00,  NULL),
    ('Twin Cheese',                NULL,   14.00,  NULL),
    ('Nasi Lemak',                 NULL,   14.00,  NULL),
    ('Choc Chip Cookies',          27.00,  16.50,  NULL),
    ('Salted Egg Crab',            NULL,   16.50,  NULL),
    ('Butter Caramel',             29.00,  18.00,  NULL),
    ('Cereal + Butter Caramel',    29.00,  18.00,  NULL),
    ('Durian',                     NULL,   18.00,  NULL),
    ('Matcha Latte',               NULL,   20.00,  NULL)
) AS f (name, wsp, pc, bc);

-- The everyday flavours used for gift boxes, member redemptions and FOC baby cans.
CREATE TEMP TABLE classic_flavour (name) ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('Original (Sea Salt)'), ('Caramel'), ('Cheese'), ('BBQ'), ('Cocoa Malt'),
    ('Seaweed'), ('Sour Cream & Onion'), ('Tomato'), ('Dark Choc')
) AS c (name);

-- Seasonal flavours: paper cans for a limited time (Seasonal Flavour Promo: a free baby can for
-- every 3 items bought with a seasonal flavour among them). Which items are seasonal, and until
-- when, is set on the dashboard (Items & Prices).
CREATE TEMP TABLE seasonal_flavour (name, pc) ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('Coconut Caramel',            16.50),
    ('Unicorn',                    18.00)
) AS s (name, pc);

-- ---- Items ----

CREATE TEMP TABLE demo_item (category_name, name, price, sort_order) ON COMMIT DROP AS
-- Selling Items
SELECT 'White Stand Pouch', 'WSP ' || f.name, f.wsp, row_number() OVER (ORDER BY f.wsp, f.name)::int
FROM flavour f WHERE f.wsp IS NOT NULL
UNION ALL
-- Paper cans, the seasonal flavours (marked below) among them.
SELECT 'Paper Can', 'PC ' || p.name, p.pc, row_number() OVER (ORDER BY p.pc, p.name)::int
FROM (SELECT f.name, f.pc FROM flavour f WHERE f.pc IS NOT NULL
      UNION ALL
      SELECT s.name, s.pc FROM seasonal_flavour s) AS p
UNION ALL
SELECT * FROM (VALUES
    ('Crispy Stand Pouch', 'CSP Cheese',                     11.00, 1),
    ('Crispy Stand Pouch', 'CSP Salted Egg',                 11.00, 2),
    ('Crispy Stand Pouch', 'CSP Sour Cream & Onion',         11.00, 3),
    ('General Gift Box',   'General Gift Box (Paper Can)',   58.00, 1),
    ('General Gift Box',   'General Gift Box (Baby Can)',    70.00, 2),
    ('Special Items',      'Malaysia Gift Box',              38.00, 1),
    ('Other Items',        'Recycle Bag (S)',                 0.50, 1),
    ('Other Items',        'Recycle Bag (M)',                 0.80, 2)
) AS v (category_name, name, price, sort_order)
UNION ALL
-- Promotional: valued at the normal price, and given free when the promotion applies.
SELECT 'B3 F1 Promo Baby Can', 'B3 F1 BC ' || f.name, f.bc, row_number() OVER (ORDER BY f.bc, f.name)::int
FROM flavour f WHERE f.bc IS NOT NULL
UNION ALL
SELECT 'Member FOC Paper Can', 'MFOC PC ' || f.name, f.pc, row_number() OVER (ORDER BY f.name)::int
FROM flavour f WHERE f.name IN ('BBQ', 'Cocoa Malt', 'Mala', 'Original (Sea Salt)', 'Sour Cream & Onion', 'Tomato')
UNION ALL
SELECT * FROM (VALUES
    ('Member FOC Crispy Stand Pouch', 'MFOC CSP Cheese',             11.00, 1),
    ('Member FOC Crispy Stand Pouch', 'MFOC CSP Salted Egg',         11.00, 2),
    ('Member FOC Crispy Stand Pouch', 'MFOC CSP Sour Cream & Onion', 11.00, 3)
) AS v (category_name, name, price, sort_order)
UNION ALL
SELECT 'Seasonal Flavour Promo FOC Baby Can', 'Seasonal Flavour FOC BC ' || f.name, f.bc,
       row_number() OVER (ORDER BY f.bc, f.name)::int
FROM flavour f WHERE f.bc IS NOT NULL
UNION ALL
SELECT * FROM (VALUES
    -- The birthday can: the Buy 3 Free 1 upsize in the member's birth month.
    ('FOC Special Items',  'B3 F1 BDG PC Birthday',              12.00, 1),
    ('FOC Other Items',    'FOC Recycle Bag (S)',                 0.50, 1),
    ('FOC Other Items',    'FOC Recycle Bag (M)',                 0.80, 2),
    ('FOC Other Items',    'FOC Tote Bag',                       10.00, 3),
    -- Member Registration and Google Review rewards.
    ('FOC Other Items',    'MRG MBP Classic Mix',                 3.50, 4),
    ('FOC Other Items',    'GRG MBP Classic Mix',                 3.50, 5)
) AS v (category_name, name, price, sort_order)
UNION ALL
-- Sample Items: always free.
SELECT 'Sample White Stand Pouch', 'SPL WSP ' || f.name, 0.00, row_number() OVER (ORDER BY f.wsp, f.name)::int
FROM flavour f WHERE f.wsp IS NOT NULL
UNION ALL
SELECT 'Sample Paper Can', 'SPL PC ' || f.name, 0.00, row_number() OVER (ORDER BY f.name)::int
FROM flavour f WHERE f.pc IS NOT NULL
UNION ALL
SELECT 'Sample Baby Can', 'SPL BC ' || f.name, 0.00, row_number() OVER (ORDER BY f.name)::int
FROM flavour f WHERE f.bc IS NOT NULL
UNION ALL
SELECT 'Aluminium Sample Pack', 'SPL ' || f.name, 0.00, row_number() OVER (ORDER BY f.name)::int
FROM flavour f WHERE f.name IN ('Durian', 'Mala', 'Nasi Lemak', 'Twin Cheese', 'Matcha Latte',
                                'Seaweed Wasabi', 'Salted Egg Crab')
UNION ALL
SELECT 'Sample FMCGSP', 'SPL FMCGSP ' || f.name, 0.00, row_number() OVER (ORDER BY f.name)::int
FROM flavour f WHERE f.pc IS NOT NULL
UNION ALL
SELECT * FROM (VALUES
    ('Sample Crispy Stand Pouch', 'SPL CSP Cheese',             0.00, 1),
    ('Sample Crispy Stand Pouch', 'SPL CSP Salted Egg',         0.00, 2),
    ('Sample Crispy Stand Pouch', 'SPL CSP Sour Cream & Onion', 0.00, 3)
) AS v (category_name, name, price, sort_order);

-- Move existing sort positions out of the way (item_category_sort_order_uq) and retire
-- whatever is no longer listed; the upsert below reactivates and re-sorts the rest.
UPDATE item SET sort_order = -id;
UPDATE item i SET active = FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM demo_item d JOIN category c ON c.name = d.category_name
    WHERE c.id = i.category_id AND d.name = i.name);

INSERT INTO item (category_id, name, price, sort_order)
SELECT c.id, d.name, d.price, d.sort_order
FROM demo_item d
JOIN category c ON c.name = d.category_name
ON CONFLICT (category_id, name) DO UPDATE
    SET price = EXCLUDED.price, sort_order = EXCLUDED.sort_order, active = TRUE;

UPDATE item i SET seasonal = EXISTS (
    SELECT 1 FROM seasonal_flavour s JOIN category c ON c.name = 'Paper Can'
    WHERE c.id = i.category_id AND i.name = 'PC ' || s.name);
-- The demo season: three months from when the demo data was (re)loaded.
UPDATE item SET seasonal_from = CASE WHEN seasonal THEN CURRENT_DATE END,
                seasonal_until = CASE WHEN seasonal THEN CURRENT_DATE + 90 END;

-- ---- Promotions ----

INSERT INTO promotion (name, type, buy_quantity, min_spend, points_cost, max_per_sale, exclusive_group,
                       seasonal_items_only, members_only)
SELECT p.name, p.type, p.buy_quantity, p.min_spend, p.points_cost, p.max_per_sale, p.exclusive_group,
       p.seasonal_items_only, p.members_only
FROM (VALUES
    -- Every 3 items bought earn 1 free baby can, with no limit...
    ('Buy 3 Free 1 Promo',        'BUY_X_GET_FREE',      3,    NULL::numeric, NULL, NULL, NULL, FALSE, TRUE),
    -- ...or, in the member's birth month, the birthday can instead (up to 2 a receipt).
    ('Birthday Reward',           'BIRTHDAY_REWARD',     3,    NULL,   NULL, 2,    NULL, FALSE, TRUE),
    ('Member Redemption',         'POINTS_REDEMPTION',   NULL, NULL,   7,    NULL, NULL, FALSE, TRUE),
    -- Once per member within 24 hours of signing up / once per member at each outlet, for a
    -- Google review of that outlet.
    ('Registration Reward Promo', 'REGISTRATION_REWARD', NULL, NULL,   NULL, 1,    NULL, FALSE, TRUE),
    ('Google Review Reward',      'REVIEW_REWARD',       NULL, NULL,   NULL, 1,    NULL, FALSE, TRUE),
    -- One free bag per sale, member or not: small from RM50, medium from RM80.
    ('Spend RM50 Free Bag (S)',   'MIN_SPEND',           NULL, 50.00,  NULL, NULL, 'free recycle bag', FALSE, FALSE),
    ('Spend RM80 Free Bag (M)',   'MIN_SPEND',           NULL, 80.00,  NULL, NULL, 'free recycle bag', FALSE, FALSE),
    ('Spend RM150 Free Tote Bag', 'MIN_SPEND',           NULL, 150.00, NULL, NULL, NULL, FALSE, TRUE),
    -- A free baby can for every 3 items bought with a seasonal flavour among them, on top of
    -- Buy 3 Free 1 (so at most one per seasonal flavour bought).
    ('Seasonal Flavour Promo',    'BUY_X_GET_FREE',      3,    NULL,   NULL, NULL, NULL, TRUE, TRUE)
) AS p (name, type, buy_quantity, min_spend, points_cost, max_per_sale, exclusive_group, seasonal_items_only,
        members_only)
ON CONFLICT (name) DO UPDATE
    SET type = EXCLUDED.type, buy_quantity = EXCLUDED.buy_quantity, min_spend = EXCLUDED.min_spend,
        points_cost = EXCLUDED.points_cost, max_per_sale = EXCLUDED.max_per_sale,
        exclusive_group = EXCLUDED.exclusive_group, seasonal_items_only = EXCLUDED.seasonal_items_only,
        members_only = EXCLUDED.members_only, active = TRUE;

-- No longer running.
UPDATE promotion SET active = FALSE WHERE name IN ('Spend RM50 Promo', 'Birthday Voucher');

-- Items bought from these folders count towards "Buy 3" (a gift box counts as one item).
UPDATE category SET counts_for_promotions = name IN
    ('White Stand Pouch', 'Paper Can', 'Crispy Stand Pouch', 'General Gift Box', 'Special Items');

UPDATE item i SET promotion_id = NULL;
UPDATE item i SET promotion_id = p.id
FROM category c, promotion p, (VALUES
    ('B3 F1 Promo Baby Can',    NULL,                                  'Buy 3 Free 1 Promo'),
    ('Member FOC Paper Can',    NULL,                                  'Member Redemption'),
    ('Member FOC Crispy Stand Pouch', NULL,                            'Member Redemption'),
    ('Seasonal Flavour Promo FOC Baby Can', NULL,                      'Seasonal Flavour Promo'),
    ('FOC Special Items',       'B3 F1 BDG PC Birthday',               'Birthday Reward'),
    ('FOC Other Items',         'FOC Recycle Bag (S)',                 'Spend RM50 Free Bag (S)'),
    ('FOC Other Items',         'FOC Recycle Bag (M)',                 'Spend RM80 Free Bag (M)'),
    ('FOC Other Items',         'FOC Tote Bag',                        'Spend RM150 Free Tote Bag'),
    ('FOC Other Items',         'MRG MBP Classic Mix',                 'Registration Reward Promo'),
    ('FOC Other Items',         'GRG MBP Classic Mix',                 'Google Review Reward')
) AS link (category_name, item_name, promotion_name)
WHERE c.id = i.category_id AND c.name = link.category_name
  AND (link.item_name IS NULL OR i.name = link.item_name)
  AND p.name = link.promotion_name;

-- ---- Gift boxes: composed in the item dialog ("CHOOSE 5" / "CHOOSE 10") ----

CREATE TEMP TABLE demo_group (item_name, name, choose_exactly, prefix) ON COMMIT DROP AS
SELECT * FROM (VALUES
    -- Named as on the printed Modifiers report.
    ('General Gift Box (Paper Can)', 'General Gift Box Paper Can', 5,  'GB PC '),
    ('General Gift Box (Baby Can)',  'General Gift Box Baby Can',  10, 'GB BC ')
) AS g (item_name, name, choose_exactly, prefix);

INSERT INTO modifier_group (item_id, name, choose_exactly, sort_order)
SELECT i.id, g.name, g.choose_exactly, 1
FROM demo_group g
JOIN item i ON i.name = g.item_name
ON CONFLICT (item_id, sort_order) DO UPDATE
    SET name = EXCLUDED.name, choose_exactly = EXCLUDED.choose_exactly;

-- Options are named as on the Modifiers report, e.g. "GB PC Caramel".
CREATE TEMP TABLE demo_option (group_name, name, sort_order) ON COMMIT DROP AS
SELECT g.name, g.prefix || f.name, row_number() OVER (PARTITION BY g.name ORDER BY f.name)::int
FROM demo_group g
JOIN flavour f ON f.name IN (SELECT name FROM classic_flavour)
    OR (g.name = 'General Gift Box Baby Can' AND f.name = 'Seaweed Wasabi');

UPDATE modifier_option SET sort_order = -id;
UPDATE modifier_option o SET active = FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM demo_option d JOIN modifier_group mg ON mg.name = d.group_name
    WHERE mg.id = o.group_id AND d.name = o.name);

-- modifier_option has no unique name per group, so match on name explicitly.
UPDATE modifier_option o SET sort_order = d.sort_order, active = TRUE, price = 0
FROM demo_option d JOIN modifier_group mg ON mg.name = d.group_name
WHERE mg.id = o.group_id AND o.name = d.name;

INSERT INTO modifier_option (group_id, name, price, sort_order)
SELECT mg.id, d.name, 0.00, d.sort_order
FROM demo_option d
JOIN modifier_group mg ON mg.name = d.group_name
WHERE NOT EXISTS (SELECT 1 FROM modifier_option o WHERE o.group_id = mg.id AND o.name = d.name);

-- ---- Discounts (one per sale) ----

INSERT INTO discount (name, kind, value, scope, sort_order, requires_approval) VALUES
    ('Staff Discount',              'PERCENT', 30.00,  'BOTH',  1, FALSE),
    ('Special Approved 10%',        'PERCENT', 10.00,  'BOTH',  2, FALSE),
    ('Special Approved 15%',        'PERCENT', 15.00,  'BOTH',  3, FALSE),
    ('Special Approved 20%',        'PERCENT', 20.00,  'BOTH',  4, FALSE),
    ('E-Wallet Promo',              'PERCENT', 10.00,  'ORDER', 5, FALSE),
    ('RM10 Voucher',                'AMOUNT',  10.00,  'ITEM',  6, FALSE),
    -- Department comps (stock given out for a department's own use): a manager approves at the till.
    ('Operation Department Special Approved', 'PERCENT', 100.00, 'BOTH', 7, TRUE),
    ('BD Department Special Approved',        'PERCENT', 100.00, 'BOTH', 8, TRUE),
    ('Marketing Department Special Approved', 'PERCENT', 100.00, 'BOTH', 9, TRUE)
ON CONFLICT (name) DO UPDATE
    SET kind = EXCLUDED.kind, value = EXCLUDED.value, scope = EXCLUDED.scope, sort_order = EXCLUDED.sort_order,
        requires_approval = EXCLUDED.requires_approval, active = TRUE;

-- Replaced by the department comps above.
UPDATE discount SET active = FALSE WHERE name = 'Department Approved (Comp)';

-- The staff discount is for staff purchases: never on a member's sale.
UPDATE discount SET not_for_members = (name = 'Staff Discount') WHERE scope <> 'VOUCHER';

-- The till's RM10 Voucher is a paper voucher keyed in by hand: its sale gives no free items.
UPDATE discount SET counts_as_voucher = (name = 'RM10 Voucher') WHERE scope <> 'VOUCHER';
