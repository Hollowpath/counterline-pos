-- Run after catalog-fixture.sql (which restarts item, modifier and order identities).
TRUNCATE discount RESTART IDENTITY CASCADE;

-- Item 7: a gift box in category 2 ("Tin") composed of exactly 3 flavours.
INSERT INTO item (category_id, name, price, sort_order) VALUES (2, 'Gift Box', 70.00, 2);

-- Group 1 belongs to item 7. Options: 1 Caramel, 2 Cheese, 3 Matcha (+2.00), 4 Retired (inactive).
INSERT INTO modifier_group (item_id, name, choose_exactly, sort_order) VALUES (7, 'Flavours', 3, 1);
INSERT INTO modifier_option (group_id, name, price, sort_order, active) VALUES
    (1, 'Caramel', 0.00, 1, TRUE),
    (1, 'Cheese',  0.00, 2, TRUE),
    (1, 'Matcha',  2.00, 3, TRUE),
    (1, 'Retired', 0.00, 4, FALSE);

-- Discounts 1 Staff 30% (both), 2 Voucher RM10 (item), 3 Order 10% (order), 4 Comp 100% (both),
-- 5 Marketing Dept 100% (order, needs a manager's approval).
INSERT INTO discount (name, kind, value, scope, sort_order, requires_approval) VALUES
    ('Staff 30%',            'PERCENT', 30.00,  'BOTH',  1, FALSE),
    ('Voucher RM10',         'AMOUNT',  10.00,  'ITEM',  2, FALSE),
    ('Order 10%',            'PERCENT', 10.00,  'ORDER', 3, FALSE),
    ('Comp 100%',            'PERCENT', 100.00, 'BOTH',  4, FALSE),
    ('Marketing Dept 100%',  'PERCENT', 100.00, 'ORDER', 5, TRUE);
