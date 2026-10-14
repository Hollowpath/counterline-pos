-- Cash is accepted alongside cashless methods.
ALTER TABLE payment_method ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'CASHLESS';
ALTER TABLE payment_method
    ADD CONSTRAINT payment_method_kind_ck CHECK (kind IN ('CASH', 'CASHLESS'));

-- The reports list Cash first.
INSERT INTO payment_method (name, sort_order, kind) VALUES ('Cash', 0, 'CASH');

-- What the customer handed over for a cash payment; the change given is cash_tendered - amount.
ALTER TABLE payment ADD COLUMN cash_tendered NUMERIC(12, 2);
ALTER TABLE payment
    ADD CONSTRAINT payment_cash_tendered_ck CHECK (cash_tendered IS NULL OR cash_tendered >= amount);

-- Past Orders search by payment reference, e.g. the last digits of the card ("4432-0330" by "0330").
CREATE INDEX payment_reference_trgm_idx ON payment USING GIN (lower(reference) gin_trgm_ops);
