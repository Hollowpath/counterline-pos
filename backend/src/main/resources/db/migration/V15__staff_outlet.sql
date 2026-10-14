-- Each staff member works at one outlet and can log in only there.
-- Staff recorded so far worked at the one outlet there was.

ALTER TABLE staff ADD COLUMN outlet_id BIGINT REFERENCES outlet (id);
UPDATE staff SET outlet_id = (SELECT id FROM outlet WHERE code = 'O1');
ALTER TABLE staff ALTER COLUMN outlet_id SET NOT NULL;
