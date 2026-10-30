-- The cash a register is opened with, declared when it is opened and never changed after (a
-- correction is a pay-in or pay-out). Sessions that already exist were opened without one: zero.
ALTER TABLE register_session
    ADD COLUMN opening_float NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT register_session_opening_float_ck CHECK (opening_float >= 0);
