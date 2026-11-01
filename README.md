# Counterline POS

A multi-outlet retail point-of-sale system built with Spring Boot, PostgreSQL, React and TypeScript. One API serves three web apps: a tablet **POS** for the till, a customer-facing **display**, and a back-office **dashboard** for head office and managers.

All data is synthetic. Payments are recorded, not processed, and the project is a demo, not production-ready (see [Status and limits](#status-and-limits)).

## The three interfaces

| Interface | Used by | What it does |
| --- | --- | --- |
| **POS** (`/`) | Cashiers and managers at the till | PIN sign-in, catalogue grid and cart, split cash/cashless payments, place-now-pay-later orders, voids and refunds with manager approval, members, loyalty points and vouchers, register open/close, clock-in/out, receipts |
| **Customer display** (`/display.html`) | Customer, on a paired second screen | Shows the current sale, member sign-up/sign-in by phone number, and rotating posters while idle |
| **Dashboard** (`/dashboard.html`) | Head office and outlet managers | Outlets, staff, catalogue and prices, discounts and vouchers, customers, till devices, sales reports |

The full feature list is in [docs/features.md](docs/features.md).

## Engineering highlights

- **Multi-outlet model.** Each outlet has its own staff, till devices and cash registers. All outlets share one configured business time zone, which defines report days, receipts and promotion dates.
- **Authentication and authorization.** Stateless JWT with separate channels for till, dashboard, display and single-action approval, so a token for one cannot be used on another. Staff PINs are hashed with a server-side pepper, dashboard passwords with BCrypt, and failed attempts are throttled with counts stored in PostgreSQL. Tokens end when a person is deactivated or their PIN, role or outlet changes.
- **Sensitive actions need a manager.** Discount approvals, voids and refunds take a short-lived, single-use approval token tied to one operation; used tokens are recorded in the database.
- **Transaction handling.** Money is `BigDecimal` in the API and integer sen in the browser. Recording a payment is idempotent: a retried request with the same client request id returns the already-paid order instead of recording the payment a second time (there is no payment gateway). Optimistic locking guards orders, registers, staff and outlets, and pessimistic locks guard loyalty balances. Catalogue edits are version-checked and a stale edit gets a 409. Reports count events on the day they happened.
- **Database.** PostgreSQL 17 with Flyway-managed migrations and Hibernate set to validate only. Staff changes are written to an audit table in the same transaction.
- **Testing.** Backend integration tests run against a real PostgreSQL container with Testcontainers; frontend tests use Vitest and Testing Library; Playwright smoke tests drive the packaged app in a browser. What each layer does and does not prove is in [docs/testing.md](docs/testing.md).
- **CI.** GitHub Actions runs four jobs on every push to `main` and every pull request: backend `verify` (including the Testcontainers tests); frontend lint, tests and build; infrastructure (CDK) build and tests; and a packaged-image job that builds the Docker image, scans it with Trivy (fixable HIGH and CRITICAL vulnerabilities fail the job), starts it with Compose, waits for the health check and runs the Playwright smoke tests. Third-party actions are pinned to commit hashes.
- **Docker.** One multi-stage image runs the API and serves the three web apps from the same origin, as a non-root user with a health check. Docker Compose runs it locally.
- **AWS infrastructure as code.** A CDK (TypeScript) stack defines ECR, a two-AZ VPC, an owner-restricted load balancer, Fargate, an encrypted PostgreSQL instance on RDS, Secrets Manager and CloudWatch logs. It was deployed on demand and exercised end to end, then destroyed: see the [dated verification record](docs/deployment-verification-2026-10-31.md). Nothing is hosted continuously.

## Stack

| Layer | Technology |
| --- | --- |
| API | Java 21, Spring Boot 4.1, Spring Security (stateless JWT), Spring Data JPA, Flyway |
| Database | PostgreSQL 17 |
| Web apps | React 19, TypeScript, Vite 7, Tailwind CSS 4 (three entry pages from one codebase) |
| Tests | JUnit with Testcontainers; Vitest with Testing Library; Playwright |
| Packaging and delivery | Multi-stage Docker image, Docker Compose, GitHub Actions |
| Infrastructure | AWS CDK (TypeScript) |

```
Browser (POS / display / dashboard) ──HTTP /api/v1──▶ Spring Boot API ──JDBC──▶ PostgreSQL
       static files served by the same API process ◀──┘
```

More in [docs/architecture.md](docs/architecture.md).

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

| Where | Who | Credential |
| --- | --- | --- |
| POS, outlet `O1` | Staff / Manager | PIN `111111` / `999999` |
| POS, outlet `O2` | Staff / Manager | PIN `222222` / `888888` |
| Dashboard | Head office | `admin@counterline.test` / `counterline-demo` |
| Dashboard | Managers | `daniel@counterline.test` (`O1`), `meiling@counterline.test` (`O2`) / `counterline-demo` |

**Localhost only.** The sample accounts above, the `pos`/`pos` database credentials in `docker-compose.yml` and the `local` profile's PIN pepper and JWT key are public in this repository, so anyone can sign in with them or forge tokens. Use them only on your own machine with synthetic data: never run the `local` profile, or this Compose file, on a reachable host or with real data. Compose publishes its ports on `127.0.0.1` only. Outside the `local` profile, `POS_SECURITY_PIN_PEPPER` and `POS_SECURITY_JWT_SECRET` must be supplied; there are no defaults.

### Run in Docker

```bash
docker compose --profile app up --build
```

Builds the single image and starts it with its own database at `http://localhost:8090` (POS), `/display.html` and `/dashboard.html`. This database is separate from the one in step 1 above and starts empty of sales; the container runs with the `local` profile, so the sample catalogue and accounts above apply. Set up the device with an outlet and manager PIN on first load.

To build the image only: `docker build -t counterline-pos .`

Compose credentials are for localhost only.

### Tests

`cd backend && ./mvnw test` (needs Docker) and `cd frontend && npm test`. See [docs/testing.md](docs/testing.md).

## Status and limits

- A demo with synthetic data. There is no payment-gateway integration, tax or fiscal printing, offline till mode or multi-tenant isolation.
- The AWS stack is temporary and owner-run: HTTP only, one task, a single-AZ database, no alarms or autoscaling, and no automated deployment pipeline. CI never publishes an image or touches AWS.
- Deliberately deferred controls, each with the trigger that would bring it in, are listed in [docs/engineering-readiness.md](docs/engineering-readiness.md).

## Documentation

- [Features](docs/features.md): the full feature list
- [Architecture](docs/architecture.md): components, security model, design decisions, AWS topology
- [Testing](docs/testing.md): test layers, commands and what they do not cover
- [Configuration and first-run set-up](docs/configuration.md): environment variables and bootstrapping a fresh database
- [AWS deployment status and roadmap](docs/aws-deployment.md): what the stack defines, cost, and what is planned
- [AWS release checklist](docs/aws-release-checklist.md): on-demand deploy and teardown runbook
- [Deployment verification, 2026-10-31](docs/deployment-verification-2026-10-31.md): record of one temporary deployment and its checks
- [Engineering readiness](docs/engineering-readiness.md): scope, evidence policy, known gaps
- [infrastructure/README.md](infrastructure/README.md): operator guide for the CDK stack

## License

[MIT](LICENSE).
