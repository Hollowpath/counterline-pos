-- E-wallets are paid through DuitNow QR at the outlet, so a separate E-Wallet method is not
-- offered. It is deactivated rather than deleted: past payments may reference it.
UPDATE payment_method SET active = FALSE WHERE name = 'E-Wallet';
