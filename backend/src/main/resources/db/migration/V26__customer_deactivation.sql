-- Members are deactivated, never removed: the row, its phone, email, reference ID, points and orders all
-- stay, and the same person cannot be registered a second time under a new identity.
ALTER TABLE customer RENAME COLUMN deleted_at TO deactivated_at;

-- A number typed as "+60 011…" used to be stored with the trunk zero ("+600112345678"), a different identity from
-- "+60112345678". "+600" is no country code, so it is always that mistake: it becomes the proper number, unless
-- that number already belongs to another member (then both stay as they are and the rules below decide).
UPDATE customer c SET phone = '+60' || substr(c.phone, 5)
WHERE c.phone LIKE '+600%'
  AND NOT EXISTS (SELECT 1 FROM customer o WHERE o.phone = '+60' || substr(c.phone, 5));

-- Before phone, email and reference ID become unique across every member (inactive ones included), a
-- deleted member that shares one with another member (it was registered again after deletion) gives it
-- up: the member still in use keeps it, and among deleted ones the earliest does.
UPDATE customer c SET phone = NULL
WHERE c.phone IS NOT NULL AND EXISTS (
    SELECT 1 FROM customer o WHERE o.phone = c.phone AND o.id <> c.id
      AND ((o.deactivated_at IS NULL AND c.deactivated_at IS NOT NULL)
           OR ((o.deactivated_at IS NULL) = (c.deactivated_at IS NULL) AND o.id < c.id)));
UPDATE customer c SET email = NULL
WHERE c.email IS NOT NULL AND EXISTS (
    SELECT 1 FROM customer o WHERE o.email = c.email AND o.id <> c.id
      AND ((o.deactivated_at IS NULL AND c.deactivated_at IS NOT NULL)
           OR ((o.deactivated_at IS NULL) = (c.deactivated_at IS NULL) AND o.id < c.id)));
UPDATE customer c SET reference_id = NULL
WHERE c.reference_id IS NOT NULL AND EXISTS (
    SELECT 1 FROM customer o WHERE o.reference_id = c.reference_id AND o.id <> c.id
      AND ((o.deactivated_at IS NULL AND c.deactivated_at IS NOT NULL)
           OR ((o.deactivated_at IS NULL) = (c.deactivated_at IS NULL) AND o.id < c.id)));

DROP INDEX customer_phone_uq;
DROP INDEX customer_email_uq;
DROP INDEX customer_reference_uq;
CREATE UNIQUE INDEX customer_phone_uq ON customer (phone);
CREATE UNIQUE INDEX customer_email_uq ON customer (email);
CREATE UNIQUE INDEX customer_reference_uq ON customer (reference_id);
