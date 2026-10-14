-- Demo outlets for local development (loaded only with the "local" profile): two kiosks. O1 is
-- the outlet the migrations created, so sales recorded before
-- outlets existed stay with it. Repeatable and idempotent.

INSERT INTO outlet (code, name, address) VALUES
    ('O1', 'Counterline Bukit Bintang', 'Kiosk, Level 3, Bukit Bintang, Kuala Lumpur'),
    ('O2', 'Counterline Cheras',        'Kiosk, Level 3, Cheras, Kuala Lumpur')
ON CONFLICT (code) DO UPDATE
    SET name = EXCLUDED.name, address = EXCLUDED.address, active = TRUE;
