-- Manager logins, seasonal dates and one till per outlet:
--  * outlet managers log in to the dashboard with email and password, like head office, instead
--    of their PIN;
--  * a seasonal flavour is seasonal for a limited time, set on the dashboard; after its last day it
--    is an ordinary item again and no longer earns the seasonal-flavour free can;
--  * an outlet has one till iPad (the customer display is a separate pairing).

-- Dashboard logins for managers. BCrypt, like head office's (see V17). Both or neither.
ALTER TABLE staff ADD COLUMN email VARCHAR(254);
ALTER TABLE staff ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE staff
    ADD CONSTRAINT staff_email_uq UNIQUE (email),
    ADD CONSTRAINT staff_email_lower_ck CHECK (email = lower(email)),
    ADD CONSTRAINT staff_dashboard_login_ck CHECK ((email IS NULL) = (password_hash IS NULL));

-- The days an item is a seasonal flavour, both inclusive (in the outlet's time zone). Null: from
-- now / no end set.
ALTER TABLE item ADD COLUMN seasonal_from DATE;
ALTER TABLE item ADD COLUMN seasonal_until DATE;
ALTER TABLE item
    ADD CONSTRAINT item_seasonal_dates_ck CHECK (seasonal_from IS NULL OR seasonal_until IS NULL
                                                 OR seasonal_from <= seasonal_until);

-- One till per outlet. Earlier extra iPads (from testing) are reset, keeping the latest set up.
UPDATE till_device d
SET reset_at = now(), reset_by = 'One till per outlet (V20)'
WHERE d.reset_at IS NULL
  AND EXISTS (SELECT 1 FROM till_device later
              WHERE later.outlet_id = d.outlet_id AND later.reset_at IS NULL AND later.id > d.id);
DROP INDEX till_device_outlet_idx;
CREATE UNIQUE INDEX till_device_one_per_outlet_uq ON till_device (outlet_id) WHERE reset_at IS NULL;
