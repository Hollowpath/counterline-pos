-- PIN locks and voucher discounts:
--  * wrong PINs lock the PIN pad of one outlet (its till login, or its Clock In/Out), not every
--    till on the same network; the 10th wrong PIN in a row locks it, the 7th to 9th warn how many
--    tries are left, and the outlet's manager (or head office) can unlock it from the dashboard.
--    Kept here rather than in each API task's memory, so every task sees the same count and an
--    unlock reaches them all;
--  * a discount can count as a voucher (the till's "RM10 Voucher", for a paper voucher keyed in by
--    hand): like a dashboard voucher, its sale gives no free promotional items but the free recycle
--    bags and the Member FOC items bought with points.

CREATE TABLE login_throttle (
    -- What is throttled: "till:1" (outlet 1's PIN login), "clock:1", "setup:1", or a client address
    -- for the dashboard and the customer display.
    throttle_key   VARCHAR(120) PRIMARY KEY,
    -- Wrong attempts in a row; a success clears the row.
    failures       INT          NOT NULL,
    last_failed_at TIMESTAMPTZ  NOT NULL,
    -- Set by the attempt that used up the last try.
    locked_until   TIMESTAMPTZ,
    CONSTRAINT login_throttle_failures_ck CHECK (failures > 0)
);

ALTER TABLE discount ADD COLUMN counts_as_voucher BOOLEAN NOT NULL DEFAULT FALSE;
