-- An attempt under way is settled by decrementing its count, then tidying: a row with nothing counted may exist for a
-- moment (the tidy-up deletes it, but nothing depends on that), so the count check no longer demands one.
ALTER TABLE login_throttle DROP CONSTRAINT login_throttle_counts_ck;
ALTER TABLE login_throttle ADD CONSTRAINT login_throttle_counts_ck CHECK (failures >= 0 AND pending >= 0);

-- When an attempt was last admitted on the key. A count of attempts under way is only reset by the window (and only swept)
-- once nothing has been admitted for a window: an attempt still under way is never forgotten when the failures are.
ALTER TABLE login_throttle ADD COLUMN pending_at TIMESTAMPTZ;
