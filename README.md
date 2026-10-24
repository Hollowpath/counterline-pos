# Counterline POS

A multi-outlet retail point-of-sale system: a tablet POS for the till, a customer-facing display, and a back-office dashboard, served by one Spring Boot API backed by PostgreSQL.

## What it does today

**POS (till)**

- Staff sign in with a PIN; roles are `STAFF` and `MANAGER`. A device is set up once for an outlet with a manager PIN. Repeated failed PINs are throttled.
- Catalogue grid, cart, item options and discounts, custom amounts.
- Checkout with cash and cashless payments (split payments supported), payment references, idempotent payment retries.
- Place an order and pay later; void unpaid orders and fully refund paid orders. These need a manager, either signed in or approving at the till ("Special Access").
- Members, promotions, loyalty points and vouchers.
- Cash register open/close, staff clock-in/out, printed-style receipts and sales reports.

**Customer display**

- A paired second screen showing the current sale and member sign-up/sign-in by phone number.

**Dashboard**

- Head-office and manager sign-in with email and password.
- Sales overview per outlet; catalogue, discount and voucher management (head office); customers, member adjustments, till devices and PIN locks.

**Platform**

- Multiple outlets, each with its own staff, registers and time zone setting (default `Asia/Kuala_Lumpur`).
- REST API under `/api/v1`, OpenAPI/Swagger UI, health endpoint at `/actuator/health`.
- Flyway-managed schema (`V1`–`V22`).

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

## Roadmap: AWS (planned, not built)

Nothing in this repository deploys to AWS yet; there is no infrastructure code and no CI/CD workflow. The code is prepared for it: a single container image, a health check, configuration from environment variables, and comments assuming a load balancer in front of a container service. Everything below is **planned**. AWS work is written and run by the owner. The list is not an implementation order; sequencing is decided incrementally, and each piece is added only when it is needed:

- Region `ap-southeast-1`, one small environment.
- Infrastructure as code with AWS CDK; GitHub Actions for build, test and image publishing, later deployment using GitHub OIDC rather than stored AWS keys.
- The container image in Amazon ECR, run on Amazon ECS with Fargate behind an Application Load Balancer.
- Amazon RDS for PostgreSQL, with database credentials and the application's keys held in AWS Secrets Manager rather than in configuration.
- Amazon CloudWatch for logs and a small set of alarms.

Cost is a design constraint: the plan avoids services that bill while idle unless they are required.

The roadmap will be updated as pieces are delivered. See [docs/engineering-readiness.md](docs/engineering-readiness.md) for what is deliberately not yet in place.

## Documentation

- [AGENTS.md](AGENTS.md): working agreements and roles
- [docs/architecture.md](docs/architecture.md)
- [docs/testing.md](docs/testing.md)
- [docs/engineering-readiness.md](docs/engineering-readiness.md)
