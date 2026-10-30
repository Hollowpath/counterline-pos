-- Rolling expiry: positive points expire 365 days after the member's latest completed, unrefunded purchase; a debt
-- never expires. Nothing is stored for it: the date follows from the member's sales (a refund of the latest purchase
-- moves it back to the one before). Expiry is recorded as an EXPIRE entry that takes the whole positive balance to
-- zero, once, when the account is next touched or looked at.

-- Finding a member's latest purchase that still counts.
CREATE INDEX sale_order_customer_purchase_idx ON sale_order (customer_id, closed_at DESC)
    WHERE status = 'CLOSED' AND refunded_at IS NULL;

-- EXPIRE entries take points away (never give them), belong to no sale and carry no staff reason.
ALTER TABLE loyalty_entry DROP CONSTRAINT loyalty_entry_type_ck;
ALTER TABLE loyalty_entry DROP CONSTRAINT loyalty_entry_sign_ck;
ALTER TABLE loyalty_entry DROP CONSTRAINT loyalty_entry_adjust_ck;
ALTER TABLE loyalty_entry
    ADD CONSTRAINT loyalty_entry_type_ck CHECK (type IN ('EARN', 'REDEEM', 'REVERSE_EARN', 'REVERSE_REDEEM', 'ADJUST', 'EXPIRE')),
    ADD CONSTRAINT loyalty_entry_sign_ck CHECK (
        (type IN ('EARN', 'REVERSE_REDEEM') AND points > 0)
        OR (type IN ('REDEEM', 'REVERSE_EARN', 'EXPIRE') AND points < 0)
        OR (type = 'ADJUST' AND points <> 0)),
    -- A sale's entries belong to it; an adjustment or an expiry has no sale, and only an adjustment says why and who.
    ADD CONSTRAINT loyalty_entry_adjust_ck CHECK (
        (type IN ('ADJUST', 'EXPIRE')) = (order_id IS NULL)
        AND (type = 'ADJUST') = (reason IS NOT NULL)
        AND (type = 'ADJUST') = (adjusted_by IS NOT NULL));
