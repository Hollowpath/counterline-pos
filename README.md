# Counterline POS

A multi-outlet point-of-sale platform for counter retail. Three web apps share one Spring Boot
API and one PostgreSQL database:

- the **POS**: an iPad till for selling, payments, orders, the register, reports and timecards;
- the **customer display**: a second screen that faces the customer, with member login and sign-up;
- the **dashboard**: back-office management for head office and outlet managers, on any device.

## Capabilities

- **Selling**: a quick-grid catalogue of tabs, folders and items; item options ("choose N");
  notes; one line or order discount per sale; custom amounts; and Place Order to pay later.
- **Payments**: card or QR with the terminal's reference, cash with change, or one cashless
  method plus cash. Checkout is idempotent and needs an open register.
- **Orders**: Past Orders with search, Edit Order for unpaid orders, and void or refund with
  manager approval at the cashier's terminal.
- **Customers and loyalty**: members with points, promotions (Buy 3 Free 1, birthday, seasonal
  and spend-threshold rewards), member redemptions, physical vouchers, points adjustments and
  owed free items.
- **Register and staff**: open and close the register, Pay In/Out, Clock In/Out, and PIN login
  with throttling and lockout.
- **Reports**: sales summary, hourly sales, payment methods, item, category and discount sales,
  options, loyalty points and timecards, all printable as 80 mm slips.
- **Multiple outlets**: one till per outlet, staff tied to their outlet, and every sale, register
  and report scoped to it.
- **Customer display**: an idle screen with posters, then the live cart, member login and sign-up.
- **Dashboard**: sales overview charts, live sales per outlet, reports, items and prices,
  discounts, vouchers, customers, and till and PIN-lock management.
- **Privacy**: phone numbers and emails are masked everywhere except the field being typed.

## Roadmap

- CI/CD pipeline (GitHub Actions).
- AWS deployment: Amazon ECR and ECS Fargate behind an Application Load Balancer, with RDS
  PostgreSQL, Secrets Manager and CloudWatch, defined with AWS CDK.

## Stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, springdoc-openapi |
| Database | PostgreSQL 17 |
| Frontend | React 19, TypeScript, Vite |
| Tests | JUnit 5, Testcontainers (real PostgreSQL), MockMvc · Vitest, React Testing Library |
| Packaging | One Docker image: the API serving the three built web apps |
| Deployment (planned) | Amazon ECR → ECS Fargate behind an ALB, RDS PostgreSQL, AWS CDK, GitHub Actions |

## Repository layout

```
backend/    Spring Boot REST API (feature packages: catalog, common, ...)
frontend/   React + TypeScript: the POS, customer display and dashboard apps
Dockerfile           the deployable image: the API serving the three built apps
docker-compose.yml   local PostgreSQL, and the whole app in its image (profile "app")
```

## Running locally

Prerequisites: JDK 21, Maven 3.9+, Node 22+, and Docker Desktop **running** (the database
runs in a container).

Use three terminals, all starting from the repository root. The commands work in
PowerShell and Bash. Keep the quotes around the `-D…` argument: PowerShell otherwise splits it
at the dots and the API starts without the `local` profile.

```bash
# Terminal 1 — PostgreSQL on :5432 (data is kept in a Docker volume between runs)
docker compose up -d

# Terminal 2 — API on :8080. The "local" profile adds the demo catalogue, demo staff and
# throwaway secrets. Ready when the log says "Started PosApiApplication".
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local"

# Terminal 3 — web app on :5173 (it forwards /api calls to :8080)
cd frontend
npm install        # first time only
npm run dev
```

Then open **http://localhost:5173** (best at tablet size or larger, like the iPad POS). The
customer display is a second app on **http://localhost:5173/display.html** (open it in another
window, as the second iPad), and the back-office dashboard a third, on
**http://localhost:5173/dashboard.html**.

- API docs (Swagger UI): http://localhost:8080/swagger-ui/index.html
- Health: http://localhost:8080/actuator/health

**Or run everything in Docker** (only Docker Desktop needed), as it will be deployed: one image
holds the API and the three apps.

```bash
docker compose --profile app up --build    # first build takes a few minutes
```

Then open **http://localhost:8090** (POS), **/display.html** and **/dashboard.html** on the same
port. It has a database of its own (a fresh shop, as a deployed environment would be: set the
iPad up with a manager PIN, `999999`), the `local` profile's demo data and throwaway secrets,
and port 8090 so it can run beside an API you started yourself.

Demo staff (seeded only under the `local` profile). Staff log in only at their own outlet:

| PIN | Name | Role | Outlet |
|---|---|---|---|
| `111111` | Aisyah Rahman | Staff | Counterline Bukit Bintang |
| `999999` | Daniel Lim | Manager | Counterline Bukit Bintang |
| `222222` | Farid Hassan | Staff | Counterline Cheras |
| `888888` | Mei Ling Tan | Manager | Counterline Cheras |

Dashboard logins (local profile only), all with the password `counterline-demo`:
`admin@counterline.test` (head office, every outlet), `daniel@counterline.test` (manager,
Bukit Bintang) and `meiling@counterline.test` (manager, Cheras).

**Stopping:** `Ctrl+C` in terminals 2 and 3, then `docker compose stop`.
**Starting over with an empty database:** `docker compose down -v` (deletes the data volume),
then `docker compose up -d` and restart the API, which recreates the schema and demo data.

### A five-minute tour

1. **Set up the iPad** (first run only): choose *Counterline Bukit Bintang* and confirm with its
   manager's PIN `999999`. Then **log in** with `111111` (staff). The welcome message shows the cashier's name, and the menu shows the
   outlet.
2. **Open the register:** ☰ → *Manage Register* (the whole screen red while closed, green while
   open, with who opened it) → **Open Register**. Payments are refused while it is closed. The
   printer button shows the session's figures, ready to print.
3. **Sell:** the cart says *No Sale* until you tap **New Sale** or the first item. Open *White
   Stand Pouch* or *Paper Can*, tap a few items (tapping again adds to the
   quantity), then open *General Gift Box* → *General Gift Box (Paper Can)* and choose exactly 5
   flavours. Tap a cart line to add a note or a discount (one discount per sale). The **%** tab
   holds order discounts, and **123** adds a custom amount.
4. **Customer:** *+ Add customer* on the cart, then search or create one.
5. **Pay:** a card or QR payment needs the terminal reference. Pay part by card and the rest in
   cash (the change is shown). *Sale complete* closes itself after 5 seconds and logs the cashier
   off, ready for the next one. Or use **Place Order** to keep the sale unpaid (it is never
   blocked; the rules apply when it is paid): *Past Orders → Open* → **Edit Order** brings it
   back into the cart to change or pay.
6. **Past Orders:** search by order number, receipt code or payment reference, open a paid order
   and **Issue Refund**. As a cashier this asks for the manager PIN `999999`.
7. **Manage Register** (Pay In/Out, listed under the button as they are saved, and close, after
   a confirmation) and **Reports** (Sales Summary, hours,
   payment methods, items, categories, discounts, options, loyalty points, timecards; **Print**
   gives an 80 mm slip) are in the menu (☰). The clock button in
   the menu footer does **Clock In/Out** (with a big live clock at the top): enter a PIN, see
   whether you are clocked in, then **slide** to Clock In (red turning green) or Clock Out (green
   turning red); a slide, not a tap, so nobody does it by mistake.
8. **Customer display:** ☰ → *Customer Display* → **Set up a customer display** shows an access
   code. Open `/display.html` in another window and enter it. The display shows posters until a
   sale is open on the till, then the cart as you ring it up (free items with their price struck
   through). On the display, tap **Login** (phone number, with its country) or **Register
   Member**: the member goes straight onto the sale, and a member added on the till shows there
   too. After payment it says "Thank you" for 10 seconds, then the posters return.
9. **Dashboard** (`/dashboard.html`, on a phone, tablet or computer): log in as head office
   (`admin@counterline.test` / `counterline-demo`). **Overview** charts the sales (net sales by
   day, busiest hours, outlets, top items, folders, payment methods) for today, 7 or 30 days or
   this month, against the period before. **Live Sales** shows every outlet (refreshing every 10 s), any
   outlet's **Reports**, edit **Items & Prices** and **Discounts** (changes reach every till at
   once; past sales keep their prices), issue printed **Vouchers**, and manage **Customers**
   (each with their points history, a points adjustment, and free items owed). A seasonal flavour
   is ticked on *Items & Prices* with its first and last day. **iPads** lists the tills (one per
   outlet) and resets one so it can be set up again (the till itself can't change its outlet). Or
   log in as an outlet manager (`daniel@counterline.test` or `meiling@counterline.test`) to see
   only that outlet.

The demo catalogue (`db/demo/R__demo_catalog.sql`) is a snack-retail range: WSP stand pouches,
PC paper cans, CSP crispy roll pouches, gift boxes, promotional FOC items and free samples.

### Configuration for deployed environments

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection |
| `POS_SECURITY_PIN_PEPPER` | Secret key for PIN hashing (Secrets Manager) |
| `POS_SECURITY_JWT_SECRET` | Token signing key, 32+ characters (Secrets Manager) |
| `POS_OUTLET_TIME_ZONE` | The outlets' local time zone for business dates (default `Asia/Kuala_Lumpur`) |
| `POS_OUTLET_PHONE_COUNTRY_CODE` | Country code added to local phone numbers (default `60`) |

The two secrets have no defaults, so the API refuses to start without them.

The image (`docker build -t counterline-pos .`) is built in three stages: Node builds the three
web apps, Maven builds the API with them as its static files (tests run in CI, not here), and
the runtime stage is a JRE on Alpine running as a non-root user, with Spring Boot's layers
copied separately so a code change ships a small layer. It listens on 8080, sizes its heap
from the container's memory limit, and reports health on `/actuator/health`. Vite's hashed
assets are cached for a year; the HTML pages are never cached, so a new release is picked up
on the next load. The build context is an allowlist: only the `backend/` and `frontend/`
sources go in, never `.git`, build output or any `.env` file.

## Tests

```bash
cd backend && mvn test        # needs Docker running (Testcontainers starts PostgreSQL)
cd frontend && npm test
```

## Design notes

- **Schema is owned by Flyway** (`backend/src/main/resources/db/migration`). Hibernate only
  validates it. Demo data lives in a separate location (`db/demo`) that only the `local`
  profile loads, so it never reaches a deployed database.
- **Errors use RFC 9457 problem details** (`application/problem+json`) for every endpoint.
- **Money**: `NUMERIC(10,2)` in PostgreSQL and `BigDecimal` in Java. The frontend converts
  prices to integer sen once, so cart totals never pick up floating-point drift.
- **Authentication**: staff log in with a 6-digit PIN only. The PIN must identify the staff
  member, so it is stored as an HMAC-SHA256 keyed with a server-side secret rather than a
  salted bcrypt hash. That way it can be looked up and kept unique, yet a leaked table can't be
  brute-forced without the key. Wrong PINs are throttled, because a 6-digit PIN has only a
  million values: per outlet and per PIN pad (the till's PIN login and manager approvals, Clock
  In/Out, iPad set-up), so one outlet's lock never stops another's. The 7th to 9th wrong PIN in a
  row say how many tries are left; the 10th locks that pad for 5 minutes (HTTP 429, with the
  seconds left, which the PIN pad counts down with its keys blocked). The outlet's manager or head
  office can unlock it at once on the dashboard (*iPads → Locked PIN pads*); the pad asks
  `/api/v1/auth/pin-lock` every few seconds while locked (and on opening, so a reload doesn't get
  round it), and frees its keys as soon as it is unlocked. The counts live in PostgreSQL
  (`login_throttle`), so every API task sees the same count and an unlock reaches them all. The
  dashboard login and the display's pairing code are throttled per client address.
- **Outlets**: an outlet has one till iPad (a partial unique index), set up once and confirmed
  with one of that outlet's managers' PINs; another iPad can't be set up there until it is reset. The server registers it (`till_device`) and gives it a random key, which the
  iPad keeps and sends with every staff login; only the key's SHA-256 is stored. The till has no
  way to change its outlet: head office (or the outlet's manager) resets the iPad from the
  dashboard, its key stops working (login answers 410), and it shows the set-up screen again.
  Each staff member works at one outlet and can log in, clock in and approve only there. The token carries the outlet, so a terminal can only record and see its own
  outlet's sales, register, Past Orders, reports and clock-ins; another outlet's order is simply
  not found. The catalogue and members (with their points) are shared by the chain, and
  order numbers come from one sequence so they stay unique. There is one open register per
  outlet (a partial unique index on `outlet_id`).
- **Customer display**: a second app (`display.html`, built from the same codebase) paired with
  one till by a 6-digit access code. It gets its own token with the role `DISPLAY`, which opens
  only the display API (`/api/v1/display/**`); staff tokens can't call that API and display
  tokens can't call anything else. The till publishes its cart and the display polls for it
  every second (an unchanged poll is an empty 204). The shared state is a database row, not
  server memory or a WebSocket, so it works with several API tasks behind the load balancer
  without a message broker. The till publishes `IDLE` while no sale is open (posters), `CART`
  once New Sale is tapped or an item added, and `THANK_YOU` when paid; the display keeps
  "Thank you" up for 10 seconds even though the till has moved on. Members can log in or sign up
  only while a sale is open, and the till (polling its display) adds them to the sale by itself;
  the display greets whoever is on the sale, however they were added. The screen faces the shop,
  so it shows first names only, never points, and never says whose number or email is already
  registered; Member Login is throttled per display. Disconnecting on the till revokes the
  display's token.
- **Dashboard**: a third app (`dashboard.html`). Head office logs in with email and password
  (BCrypt, salted and slow, since passwords never need looking up by value, unlike PINs), and
  gets a token with the role `ADMIN` and no outlet: every outlet, plus the catalogue and the
  discounts. An outlet manager logs in the same way (email and password on their staff record)
  and gets a token for their own outlet. The
  rules are in one place (`SecurityConfig`): `/dashboard/catalog` and `/dashboard/discounts`
  are admin-only, the rest of `/dashboard` is admin or manager, and an outlet a manager can't
  see is simply not found. Live Sales polls one endpoint that sums each outlet's day from the
  same report queries the till uses. Overview (`GET /dashboard/overview`) answers every chart
  in one request with plain SQL over the outlets the user can see; the charts are drawn with SVG
  and CSS, no chart library. On a phone the sidebar becomes a top bar and editable tables become
  cards. The POS's Reports and Customers screens are reused as they
  are. Customer management moved here; the till keeps finding or adding a customer from the
  cart.
- **Vouchers**: physical, used once, expiring. Head office issues a batch (value,
  expiry, quantity) and prints the codes (10 characters, no 0/O or 1/I). At the till the
  cashier keys in a code on the % tab. Each batch has its own discount with the scope
  `VOUCHER`, never offered on a list, so the voucher is the sale's one discount and reports show
  it with the other discounts. The voucher row is locked while a sale uses it, so two tills
  can't spend the same voucher. A voided or refunded sale gives its voucher back (a refund
  undoes the whole sale; the staff hand the paper voucher back with the money). The quote checks a voucher without failing its own
  transaction, so the till can say why before Pay is pressed. A sale with any voucher or
  any discount (from the % tab or on a line) gives none of the free items earned by the items bought
  (Buy 3 Free 1, the seasonal flavour's, the birthday can), nor are they required: with one in the
  sale, Pay is blocked and the cart says which to remove (Place Order still goes ahead). The Member FOC items (points are still
  earned, and redeemed at 7) and the FOC Other Items (bags, tote, Registration and Google Review
  rewards) work as usual. A discount can also *count as a voucher* (the till's **RM10 Voucher**,
  given on a line, for a paper voucher keyed in by hand; a tick in the dashboard's Discounts).
- **Member adjustments**: head office or a manager can add or take away points with a reason,
  recorded in the points ledger as `ADJUST` with who did it (the balance can't go below zero).
  When the staff forgot a free item, it is recorded as owed: on the member's next sale those
  items are free whatever that sale's own rules allow (for example a birthday can outside the
  birth month), the till shows what is owed, and paying the sale uses it up.
- **Sessions**: a successful login returns a signed JWT (12 h, Spring Security resource server)
  sent as a Bearer header. It is stateless across API tasks and uses no cookies, so there's no
  CSRF risk. Tradeoff: a token stays valid until it expires. `/auth/me` re-checks that the
  staff member is still active.
- **Authorisation**: roles are `STAFF` and `MANAGER`, enforced on the server. Which actions
  need `MANAGER` is decided feature by feature.
- **Checkout is one transaction** (`POST /api/v1/orders`): the order, its lines and its
  payments are written together or not at all. The client sends only item ids, quantities
  and payments. The server prices each line from the catalogue and snapshots name and price
  onto the line, so later catalogue edits never change past sales. Payments must exactly
  equal the total. A zero-total sale (free items) needs no payment.
- **Payment rules**: a sale is paid by one cashless method (card or QR),
  in cash, or by one cashless method plus cash for the rest. A cashless payment needs the
  terminal's reference. For cash, the amount handed over is recorded and the change is shown.
  Payments are only taken while the register is open. The check reads the open session
  `FOR SHARE`, so a register close waits for a payment in progress rather than racing it.
- **Checkout is idempotent**: the client generates a `clientRequestId` per payment attempt,
  and a unique constraint backs it. A retry or double-tap returns the original order
  (200 instead of 201) rather than recording the sale twice, even when both requests race.
- **Order lifecycle**: `OPEN` (placed, unpaid), then pay to `CLOSED`, which can later be
  refunded in full. Alternatively `OPEN` is voided to `VOIDED`. Void applies to unpaid orders
  and refund to paid ones. Both need a reason and the `MANAGER` role,
  enforced with `@PreAuthorize`. Database check constraints keep void and refund details
  consistent with the status.
- **Manager approval at a cashier's terminal** ("Special Access Required"): a manager enters
  their PIN. `POST /auth/special-access` returns a 5-minute manager token used for that single
  action, and the action is recorded as the manager's. The cashier's session is untouched, and
  a wrong PIN is 403 rather than 401, so the terminal isn't logged out.
- **Concurrent changes**: `sale_order.version` (JPA optimistic locking) means two terminals
  paying or voiding the same order at once can't both win; the loser gets 409. Business rules
  re-check the current status, so a stale screen can't, for example, void a paid order.
- **Past Orders search**: one status tab at a time, newest first, paginated with a stable
  `PageResponse` shape. The `(status, created_at)` index serves the tab listing. Receipt-code
  prefix search uses a `varchar_pattern_ops` index so `LIKE 'prefix%'` works under any
  collation. A search term also matches part of a payment reference ("0330" finds the card
  payment "4432-0330"), backed by a trigram index on the reference. Item summaries for a page
  come from a single extra query, not one per order.
- **Privacy**: phone numbers and emails are shown with most of them hidden (`+60 12•••6789`,
  `nu•••@example.com`) everywhere on the till and the dashboard; only a field being typed in shows
  them in full. Editing a customer shows the current ones hidden, with **Change** to type a new one.
  Search by phone or email still works on the full number.
- **Customers**: phone numbers are stored in E.164 (`+60112345678`) and emails in lower case,
  so a customer is found and kept unique however the details were typed. Uniqueness uses
  partial unique indexes over non-deleted rows. Deletion is soft, so past orders keep their
  customer. Name, phone and email search is case-insensitive "contains", backed by `pg_trgm`
  GIN indexes, with LIKE wildcards in the input escaped. Edits send the version the cashier
  loaded, and a stale version is answered with 409, so one terminal can't overwrite
  another's edit.
- **Customer analytics** (order count, total, average, last order, top 5 items) are computed
  from the customer's paid, non-refunded orders with two aggregate queries. They live in the
  `orders` package because orders depend on customers, not the other way round.
- **Item options and discounts**: an option group says "CHOOSE N"
  and needs exactly N picks (options may repeat, and may carry a price). A
  sale takes **one discount**, either on a line or on the whole order (the % tab); the
  `SaleOrder` aggregate enforces this. A discount is calculated on the undiscounted amount
  (percentages rounded half-up to the sen) and never exceeds it. One `DiscountCalculator` implements the rule
  on the server; the browser mirrors it in integer sen, so the till shows exactly what the
  server charges, and a unit test checks both against the same worked example. Every
  applied discount and chosen option is snapshotted on the order, and database check
  constraints tie `subtotal − discounts = total` on lines and orders. The department comps
  (Operation, BD and Marketing Department Special Approved, 100%) need a manager: a cashier's
  Pay asks for a manager PIN ("Special Access Required"), and the short-lived
  manager token travels with the sale. The server verifies it, the sale stays the cashier's,
  and the approving manager is recorded on the discount.
- **Promotions and loyalty**: a promotional item (e.g. "B3 F1 BC
  Caramel") is valued at its normal price and deducted in full by its promotion, snapshotted
  per line like a discount, so reports show it under Discounts & Comps. Every promotional item
  needs a member on the sale, except the free recycle bags. `PromotionRules` checks the rest
  once the whole sale is known:
  - Buy 3 Free 1: one free baby can per 3 qualifying items bought, with no limit (a gift box
    counts as one).
  - Birthday Reward: in the member's birth month, a birthday can can replace the baby can (they
    share the same allowance), up to 2 per receipt, on every receipt that month. No code is
    needed, and it is the only birthday gift.
  - Seasonal flavour: one more free baby can for every 3 items bought with a seasonal flavour
    among them, so never more than the seasonal flavours bought (3 Unicorn: 1; 5 others and 1
    Unicorn: 1; 6 Unicorn: 2; 2 Unicorn alone: none). Seasonal flavours are ordinary items (paper
    cans) ticked as seasonal on the dashboard between a first and last day; after it they are
    ordinary items again.
  - Spend thresholds: one free recycle bag per sale (S from RM50, M from RM80, member or not);
    one tote bag per RM150 for a member.
  - Free items earned are not optional: a member's sale can't be paid until every Buy 3 Free 1
    (or birthday) can, seasonal-flavour can and tote bag it has earned is in it. The free bags
    and once-only rewards stay optional.
  - **Place Order is never blocked**: the rules (and a comp's manager approval) apply when the
    order is paid, through Edit Order.
  - Member Redemption: 7 points per item. Points are **not carried forward** once they reach
    7: a member's sale that would leave 7 or more must redeem them on the spot (6 points and
    an RM80 sale make 14, so 2 Member FOC items). Paying a placed order checks again against
    the member's current points.
  - Registration Reward: once per member, within 24 hours of signing up. Google Review reward:
    once per member at each outlet (each outlet has its own reviews). Neither needs a code.
    Limited-time promotions run between their dates.
  - The staff discount can't be given on a member's sale (a discount flag, set on the dashboard).

  The cart lists what is charged first (the items that count towards Buy 3 at the top), then
  other items such as bags, then free items, so the staff can count at a glance; the customer
  display shows the same order.

  As soon as a member is added, the cart shows their points as
  [current] + [earned from this cart] = [total after this purchase], a birthday cake in their
  birth month, and a new member's free gift while it is still theirs
  (`GET /customers/{id}/perks`). It reports every problem at once. `POST /orders/quote` runs the same checks without saving,
  so the till can say why before Pay is pressed. Members earn 1 point per full RM10 paid and
  can spend what the same sale earns. Points live in `loyalty_account` plus a `loyalty_entry`
  ledger, not on the customer row, so a sale never conflicts with a staff edit of the customer.
  The account row is locked while a sale settles, so two terminals can't spend the same points.
  A full refund reverses the sale's points.
- **Custom amounts** are order lines without a catalogue item (`item_id` NULL), named
  "Custom Amount".
- **Reports** are plain SQL (`NamedParameterJdbcTemplate`: `GROUP BY`, `FILTER`,
  `AT TIME ZONE`) over half-open time ranges. A report date range covers whole business days
  in the outlet's time zone. A sale counts on the day it was paid (`closed_at`) and a refund
  on the day it was refunded, so net = gross − refunds for any period. Lines snapshot their
  category, so Category Sales doesn't change when items are re-categorised. Partial indexes
  on `closed_at` and `refunded_at` serve the daily queries. The reports are laid out for
  80 mm printed slips: the Sales Summary adds Total Collected, Order Statuses (paid, refunded, open,
  voided) and Dining Options (every kiosk sale is Retail with 1 pax; the per-pax average is cut
  to the sen, as printed). Category Sales lists categories and items by quantity, each item
  split by unit price ("Unit Price", "Unit Value" for a free promotional item counted at its
  value, "Sample"). Loyalty Points comes from the points ledger (issued, redeemed by promotion,
  and what refunds reversed).
- **Manage Register**: at most one open register, enforced by a unique index over a constant
  restricted to open sessions. Two terminals opening at once leave exactly one open (the
  other gets 409). Closing sends the session version shown, and is refused while placed
  orders are still unpaid. The session shows its sales (the same SQL as the reports) plus
  Pay In/Out movements.
- **Clock In/Out**: the PIN alone records nothing; it shows whether the staff member is clocked
  in, and they tap Clock In or Clock Out. Choosing what they already are is refused, and the
  staff row is locked (`SELECT … FOR UPDATE`) while recording, so a double tap clocks in once.
  It has its own PIN pad throttle, and a wrong PIN is 422, not 401, so the terminal stays logged in.
- **Edit Order**: an unpaid (placed) order is loaded back into the cart at today's prices
  (`GET /orders/{id}/cart`) and saved with `PUT /orders/{id}`, placed again or paid. The order is
  locked meanwhile and keeps its number and receipt code; its lines are replaced in place.
- **Members** need a first name, last name and phone number. The phone field shows the country
  it detects (flag and code); numbers without "+" are Malaysian. The date of birth looks like any
  other field; tapping it drops down three wheels (day, month, year) turned like the dials of a
  combination lock, as on a phone, on the till and on the customer display: quicker with a
  finger than typing a date or paging a calendar.
- **Order numbers** (`R355`) come from a PostgreSQL sequence, which is safe across terminals.
  Receipt codes are the outlet-local date plus random letters, and are unique.
- **Grid placement is data**: each category folder stores its tab and grid cell, with a unique
  constraint so two folders can't occupy the same cell.
