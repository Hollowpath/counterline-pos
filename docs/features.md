# Features

What the application does today, by interface. For the design behind it see [architecture.md](architecture.md).

**POS (till)**

- Staff sign in with a PIN; roles are `STAFF` and `MANAGER`. A device is set up once for an outlet with a manager PIN. Repeated failed PINs are throttled.
- Catalogue grid, cart, item options and discounts, custom amounts.
- Checkout with cash and cashless payments (split payments supported), payment references, idempotent payment retries.
- Place an order and pay later; void unpaid orders and fully refund paid orders. These need a manager, either signed in or approving at the till ("Special Access").
- Members, promotions, loyalty points and vouchers.
- Cash register open/close with a declared opening float (RM0.00 unless said otherwise, never carried over from an earlier session), pay in / pay out that cannot take more than the register's expected cash, staff clock-in/out, printed-style receipts and sales reports.

**Customer display**

- A paired second screen showing the current sale and member sign-up/sign-in by phone number. While no sale is open it rotates posters; a promotion's poster appears only while the server reports that promotion running today in the configured business time zone (otherwise only neutral posters that promise no reward).

**Dashboard**

- Head-office and manager sign-in with email and password.
- Outlets (head office): add, edit name and address, deactivate and reactivate. The outlet code is set once and never changes; outlets are never deleted, and deactivation is refused while a register is open.
- Staff (head office): cashiers and managers per outlet; add, edit name, role and outlet, deactivate and reactivate, reset a PIN. Managers can have a dashboard e-mail and password. PINs and passwords are never shown or logged, staff are never deleted, and a deactivated person cannot sign in. Each change is recorded against the head-office account that made it.
- Sales overview per outlet; catalogue (folders and tabs: add, rename, move, reorder, delete when empty; items: add, price, move, reorder, remove; item option groups and options; sold items and options are taken off sale rather than deleted, so past sales stay intact), discount and voucher management (head office); customers, member adjustments, till devices and PIN locks.

**Platform**

- Multiple outlets, each with its own staff and registers, all sharing one configured business time zone (`POS_OUTLET_TIME_ZONE`, default `Asia/Kuala_Lumpur`); there is no per-outlet time zone.
- REST API under `/api/v1`, OpenAPI/Swagger UI (local profile only), health endpoint at `/actuator/health`.
- Flyway-managed schema (versioned migrations in `backend/src/main/resources/db/migration`).
