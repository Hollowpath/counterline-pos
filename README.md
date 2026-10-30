# Counterline POS

A multi-outlet retail point-of-sale system: a tablet POS for the till, a customer-facing display, and a back-office dashboard, served by one Spring Boot API backed by PostgreSQL.

## What it does today

**POS (till)**

- Staff sign in with a PIN; roles are `STAFF` and `MANAGER`. A device is set up once for an outlet with a manager PIN. Repeated failed PINs are throttled.
- Catalogue grid, cart, item options and discounts, custom amounts.
- Checkout with cash and cashless payments (split payments supported), payment references, idempotent payment retries.
- Place an order and pay later; void unpaid orders and fully refund paid orders. These need a manager, either signed in or approving at the till ("Special Access").
- Members, promotions, loyalty points and vouchers.
- Cash register open/close with a declared opening float (RM0.00 unless said otherwise, never carried over from an earlier session), pay in / pay out that cannot take more than the register's expected cash, staff clock-in/out, printed-style receipts and sales reports.

**Customer display**

- A paired second screen showing the current sale and member sign-up/sign-in by phone number. While no sale is open it rotates posters; a promotion's poster appears only while the server reports that promotion running today at the outlet (otherwise only neutral posters that promise no reward).

**Dashboard**

- Head-office and manager sign-in with email and password.
- Outlets (head office): add, edit name and address, deactivate and reactivate. The outlet code is set once and never changes; outlets are never deleted, and deactivation is refused while a register is open.
- Staff (head office): cashiers and managers per outlet; add, edit name, role and outlet, deactivate and reactivate, reset a PIN. Managers can have a dashboard e-mail and password. PINs and passwords are never shown or logged, staff are never deleted, and a deactivated person cannot sign in.
- Sales overview per outlet; catalogue (folders and tabs: add, rename, move, reorder, delete when empty; items: add, price, move, reorder, remove; item option groups and options; sold items and options are taken off sale rather than deleted, so past sales stay intact), discount and voucher management (head office); customers, member adjustments, till devices and PIN locks.

**Platform**

- Multiple outlets, each with its own staff, registers and time zone setting (default `Asia/Kuala_Lumpur`).
- REST API under `/api/v1`, OpenAPI/Swagger UI (local profile only), health endpoint at `/actuator/health`.
- Flyway-managed schema (`V1`–`V24`).

## Stack

| Layer     | Technology                                                                         |
| --------- | ---------------------------------------------------------------------------------- |
| API       | Java 21, Spring Boot 4.1, Spring Security (stateless JWT), Spring Data JPA, Flyway |
| Database  | PostgreSQL 17                                                                      |
| Web apps  | React 19, TypeScript, Vite 7, Tailwind CSS 4 (three entry pages from one codebase) |
| Tests     | JUnit with Testcontainers; Vitest with Testing Library                             |
| Packaging | Single multi-stage Docker image; Docker Compose for local use                      |

See [docs/architecture.md](docs/architecture.md).

## Run locally

Prerequisites: JDK 21, Node.js (the image builds with Node 22), Docker.

```bash
# 1. Database
docker compose up -d

# 2. API on :8080 (the "local" profile loads the sample catalogue, sample staff and throwaway secrets)
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. Web apps on :5173 (proxies /api to :8080)
cd frontend
npm ci
npm run dev
```

On Windows PowerShell, run step 2 as follows (the quotes stop PowerShell splitting the `-D` argument):

```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Open `http://localhost:5173/` (POS), `/display.html` (customer display) and `/dashboard.html` (dashboard).

Local-only sample accounts (seeded by the `local` profile, outlet codes `O1` and `O2`):

| Where            | Who             | Credential                                                                               |
| ---------------- | --------------- | ---------------------------------------------------------------------------------------- |
| POS, outlet `O1` | Staff / Manager | PIN `111111` / `999999`                                                                  |
| POS, outlet `O2` | Staff / Manager | PIN `222222` / `888888`                                                                  |
| Dashboard        | Head office     | `admin@counterline.test` / `counterline-demo`                                            |
| Dashboard        | Managers        | `daniel@counterline.test` (`O1`), `meiling@counterline.test` (`O2`) / `counterline-demo` |

These exist only under the `local` profile. Outside it, `POS_SECURITY_PIN_PEPPER` and `POS_SECURITY_JWT_SECRET` must be supplied; there are no defaults.

## Run in Docker

```bash
docker compose --profile app up --build
```

Builds the single image and starts it with its own database at `http://localhost:8090` (POS), `/display.html` and `/dashboard.html`. This database is separate from the one in step 1 above and starts empty of sales; the container runs with the `local` profile, so the sample catalogue and accounts above apply. Set up the device with an outlet and manager PIN on first load.

To build the image only: `docker build -t counterline-pos .`

Compose credentials are for localhost only.

## Tests

See [docs/testing.md](docs/testing.md). In short: `cd backend && ./mvnw test` (needs Docker) and `cd frontend && npm test`.

## Configuration

| Variable                                             | Default                   | Purpose                                       |
| ---------------------------------------------------- | ------------------------- | --------------------------------------------- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`               | local Compose database    | Database connection                           |
| `POS_SECURITY_PIN_PEPPER`, `POS_SECURITY_JWT_SECRET` | none (local profile only) | PIN hashing pepper and JWT signing key        |
| `POS_OUTLET_TIME_ZONE`                               | `Asia/Kuala_Lumpur`       | Reporting time zone                           |
| `POS_OUTLET_PHONE_COUNTRY_CODE`                      | `60`                      | Default country code for member phone numbers |
| `POS_BOOTSTRAP_ENABLED`                              | `false`                   | One-time first-run set-up (below)             |

### First-run set-up of a fresh database (outside the `local` profile)

A new database has the single outlet `O1` (migration `V14`) and the catalogue tables, but no staff or head-office account. With `POS_BOOTSTRAP_ENABLED=true` the application creates, once at start-up, one head-office account for the dashboard and one manager for the existing outlet. The manager can set up a till, sign in at the till with a PIN, and sign in to the dashboard with an e-mail and password. It is for non-local environments only: the application refuses to start if bootstrap is enabled while the `local` profile is active (that profile seeds its own sample accounts).

When enabled, all of these are required. There are no defaults, and the application does not start if one is missing or invalid:

| Variable                          | Rule                                                     |
| --------------------------------- | -------------------------------------------------------- |
| `POS_BOOTSTRAP_OUTLET_CODE`       | Code of an existing, active outlet (for example `O1`)    |
| `POS_BOOTSTRAP_HEAD_OFFICE_NAME`  | Display name, up to 100 characters                       |
| `POS_BOOTSTRAP_HEAD_OFFICE_EMAIL` | Valid e-mail; stored lower case                          |
| `POS_BOOTSTRAP_HEAD_OFFICE_PASSWORD` | At least 12 characters and at most 72 bytes in UTF-8  |
| `POS_BOOTSTRAP_MANAGER_NAME`      | Display name, up to 120 characters                       |
| `POS_BOOTSTRAP_MANAGER_PIN`       | Exactly six digits                                       |
| `POS_BOOTSTRAP_MANAGER_EMAIL`     | Valid e-mail, different from the head-office e-mail      |
| `POS_BOOTSTRAP_MANAGER_PASSWORD`  | At least 12 characters and at most 72 bytes in UTF-8     |

Supply the credential values from the environment's secret store, never from the repository. The application never logs them.

Behaviour on re-runs: an account that already matches (same e-mail, and for the manager the same PIN, outlet and role) is left as it is, and its stored credentials are never overwritten. The application refuses to start if the outlet does not exist or is inactive, or if an e-mail or PIN is already held by a different account (including a deactivated one).

Operational sequence:

1. Deploy with `POS_BOOTSTRAP_ENABLED=true` and the variables above set, and start the application once.
2. Check access: sign in to the dashboard as head office and as the manager, set up a till with the manager PIN, then sign in at the till.
3. Redeploy with `POS_BOOTSTRAP_ENABLED` unset (or `false`) and every `POS_BOOTSTRAP_*` credential variable removed. The initializer creates only this initial head-office account and manager; further outlets and staff are managed in the dashboard (Outlets, Staff). A fresh non-local database also has an empty catalogue: head office builds it in the dashboard under Items & Prices, by adding a folder (naming its till-grid tab) and then items in it. Nothing is seeded outside the `local` profile.

If the credentials are held in AWS Secrets Manager, removing them from the current secret value does not erase the previous encrypted version, which Secrets Manager keeps until the secret is deleted. Use this workflow only with synthetic, temporary credentials; the AWS steps are in [infrastructure/README.md](infrastructure/README.md).

## AWS deployment status and roadmap

**Runtime state.** The repository provides no continuously hosted environment. Deployments are external, owner-managed and created on demand; the repository describes what can be deployed, and these docs cannot assert live external state.

**What exists.** A GitHub Actions workflow (`.github/workflows/ci.yml`) builds and tests the backend and frontend. The owner-managed AWS CDK stack in `infrastructure/` (TypeScript) is reproducible and was designed for temporary, owner-run deployments. It defines:

- a private Amazon ECR repository for the application image (immutable tags, scan on push, the five most recent images kept, retained if the stack is deleted);
- a two-AZ VPC with public and isolated database subnets and no NAT gateway;
- an internet-facing Application Load Balancer serving HTTP only, reachable only from the owner's IPv4 address (a single `/32`, supplied as a stack parameter);
- one Fargate task with a public IP, receiving traffic from the load balancer on port 8080;
- an encrypted, single-AZ Amazon RDS for PostgreSQL 17 instance in the isolated subnets, not publicly accessible;
- AWS Secrets Manager secrets for the database credentials and the application's keys;
- CloudWatch application logs with seven-day retention.

**What this is not.** This is a temporary learning deployment, not production-ready: HTTP only, one task, a single-AZ database with one day of backups, no alarms, no autoscaling, and resources that are deleted with the stack (except the ECR repository). There is no automated deployment pipeline: CI builds the image only for a local smoke test and never publishes it or touches AWS, and the owner pushes images and deploys the stack by hand. The operator steps are in [infrastructure/README.md](infrastructure/README.md), and an end-to-end on-demand checklist (build, push, deploy, smoke checks, rollback, teardown, final sweep) is in [docs/aws-release-checklist.md](docs/aws-release-checklist.md).

**Deploy briefly, then destroy.** The load balancer, Fargate task, database and public IPv4 addresses are billed while they exist, even when idle, so deploy only for testing or demonstrations and tear the stack down afterwards with `cdk destroy`. Teardown deletes the synthetic database and the secrets. The ECR repository is retained by the stack and must be deleted separately for a complete teardown (see [infrastructure/README.md](infrastructure/README.md#teardown-and-cost)).

**Planned, not built.** Region `ap-southeast-1`, one small environment. Each item is added only when it is needed:

- HTTPS on a domain the owner controls, before any access beyond the owner.
- Automated delivery from GitHub Actions (image publishing, then deployment using GitHub OIDC rather than stored AWS keys).
- A small set of CloudWatch alarms.
- Production availability and backups for the database.

Cost is a design constraint: the plan avoids services that bill while idle unless they are required. See [docs/engineering-readiness.md](docs/engineering-readiness.md) for what is deliberately not yet in place.

## Documentation

- [AGENTS.md](AGENTS.md): working agreements and roles
- [docs/architecture.md](docs/architecture.md)
- [docs/testing.md](docs/testing.md)
- [docs/engineering-readiness.md](docs/engineering-readiness.md)
- [docs/aws-release-checklist.md](docs/aws-release-checklist.md): on-demand AWS deployment checklist
- [infrastructure/README.md](infrastructure/README.md): operator guide for the AWS CDK stack
