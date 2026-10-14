-- Run after catalog-fixture.sql (whose TRUNCATE item … CASCADE also clears sale_order_promotion).
-- Pouch (1), Tin (2) and Seasonal (3) count towards "Buy 3"; category 4 (Promo) holds the promotional
-- items. Item 30 (PC Curry) is the seasonal flavour. The free recycle bags don't need a member.
DELETE FROM promotion;
ALTER TABLE promotion ALTER COLUMN id RESTART WITH 1;
UPDATE category SET counts_for_promotions = (id IN (1, 2, 3));

INSERT INTO promotion (name, type, buy_quantity, min_spend, points_cost, max_per_sale, exclusive_group,
                       seasonal_items_only, members_only) VALUES
    ('Buy 3 Free 1 Promo',        'BUY_X_GET_FREE',      3,    NULL,   NULL, NULL, NULL,               FALSE, TRUE),
    ('Spend RM50 Promo',          'MIN_SPEND',           NULL, 50.00,  NULL, 1,    NULL,               FALSE, TRUE),
    ('Member Redemption',         'POINTS_REDEMPTION',   NULL, NULL,   7,    NULL, NULL,               FALSE, TRUE),
    ('Registration Reward Promo', 'REGISTRATION_REWARD', NULL, NULL,   NULL, 1,    NULL,               FALSE, TRUE),
    ('Birthday Reward',           'BIRTHDAY_REWARD',     3,    NULL,   NULL, 2,    NULL,               FALSE, TRUE),
    ('Spend RM50 Free Bag (S)',   'MIN_SPEND',           NULL, 50.00,  NULL, NULL, 'free recycle bag', FALSE, FALSE),
    ('Spend RM80 Free Bag (M)',   'MIN_SPEND',           NULL, 80.00,  NULL, NULL, 'free recycle bag', FALSE, FALSE),
    ('Spend RM150 Free Tote Bag', 'MIN_SPEND',           NULL, 150.00, NULL, NULL, NULL,               FALSE, TRUE),
    ('Seasonal Flavour Promo',    'BUY_X_GET_FREE',      3,    NULL,   NULL, NULL, NULL,               TRUE,  TRUE),
    ('Google Review Reward',      'REVIEW_REWARD',       NULL, NULL,   NULL, 1,    NULL,               FALSE, TRUE);
-- Retired at the outlet (kept so ids stay put): its item is no longer free.
UPDATE promotion SET active = FALSE WHERE name = 'Spend RM50 Promo';

-- Explicit ids, so tests don't depend on what other fixtures inserted.
INSERT INTO item (id, category_id, name, price, sort_order, promotion_id) OVERRIDING SYSTEM VALUE VALUES
    (21, 4, 'B3 F1 BC Caramel',           6.50,  2, 1),
    (22, 4, 'Spend RM50 FOC BC Cheese',   6.50,  3, 2),
    (23, 4, 'MFOC PC BBQ',                10.00, 4, 3),
    (24, 4, 'Registration Reward MBP',    3.50,  5, 4),
    (25, 4, 'Birthday Reward PC Caramel', 12.00, 6, 5),
    (26, 4, 'FOC Recycle Bag (S)',        0.50,  7, 6),
    (27, 4, 'FOC Recycle Bag (M)',        0.80,  8, 7),
    (28, 4, 'FOC Tote Bag',               10.00, 9, 8),
    (29, 4, 'Seasonal Flavour FOC BC Cheese', 6.50, 10, 9),
    (31, 4, 'GRG MBP Classic Mix',        3.50,  11, 10);
INSERT INTO item (id, category_id, name, price, sort_order, seasonal) OVERRIDING SYSTEM VALUE VALUES
    (30, 3, 'PC Curry', 14.00, 1, TRUE);
