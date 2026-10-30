-- Head office's edits of staff and outlets are optimistic: a request that read a row before another
-- request changed it fails with a conflict instead of writing its stale copy back (which could
-- reactivate someone, restore a PIN, or make revoked sessions valid again).
ALTER TABLE staff ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE outlet ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Outlet names are unique ignoring case (the application has always checked it that way); the database
-- now enforces it, so two concurrent requests cannot both take "Cafe" and "cafe". Two outlets that were
-- created at the same moment may already share a name that differs only in case: the later one gets its code
-- added (e.g. "Cafe (K2)"), so the index can be built. Nothing else about it changes.
UPDATE outlet o
SET name = left(o.name, 80 - length(' (' || o.code || ')')) || ' (' || o.code || ')'
WHERE EXISTS (SELECT 1 FROM outlet earlier WHERE lower(earlier.name) = lower(o.name) AND earlier.id < o.id);

CREATE UNIQUE INDEX outlet_name_lower_uq ON outlet (lower(name));
