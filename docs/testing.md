# Testing

What test layers exist, how to run them, and what they do not cover. Counts of tests and files are not recorded here, and neither is pass/fail status, because both change often.

## Layers

### Backend (`backend/src/test`)

JUnit 5 with Spring Boot test support, in two kinds:

- **Integration tests** (most of them, named `*IntegrationTest` or `*Tests`) start the Spring application against a real PostgreSQL 17 container through Testcontainers (`TestcontainersConfiguration`). Flyway runs the real migrations, so schema and queries are exercised for real. Areas: auth and roles, catalogue, checkout, order lifecycle, options and discounts, promotions and loyalty, vouchers and adjustments, customers, register and timecards, reports, multi-outlet, customer display, dashboard, and static web app serving.
- **Unit tests** for isolated rules: `DiscountCalculatorTest`, `SaleOrderTest`, `LoginAttemptLimiterTest`, `PhoneNumbersTest`.

Test data comes from SQL fixtures in `backend/src/test/resources` (`catalog-fixture.sql`, `options-fixture.sql`, `promotions-fixture.sql`) and a stub web app page under `static/`. Test configuration (`application-test.yml`) uses throwaway secrets.

### Frontend (`frontend/src`)

Vitest in a jsdom environment with Testing Library; setup in `src/test/setup.ts`. Test files are colocated with the code (`*.test.ts` / `*.test.tsx`):

- Component and workflow tests for POS screens (selling, payment, past orders, customers, register, reports, receipts, timecards), the customer display and the dashboard. Workflow tests click through whole sales, hence the 15 s timeout in `vite.config.ts`.
- Unit tests for helpers in `lib/` (money, dates, privacy masking, countdown) and the cart logic.

The API is not running in these tests; the frontend tests do not prove that the two sides agree.

### Static checks

- `npm run lint` (ESLint with TypeScript and React hooks rules).
- `npm run build` runs `tsc -b` before Vite, so type errors fail the build.

## Commands

```bash
# Backend: needs Docker running (Testcontainers pulls postgres:17-alpine)
cd backend
./mvnw test
./mvnw test -Dtest=CheckoutApiIntegrationTest   # one class

# Backend on Windows PowerShell (quote -D arguments)
.\mvnw.cmd test
.\mvnw.cmd test "-Dtest=CheckoutApiIntegrationTest"

# Frontend
cd frontend
npm ci
npm test            # vitest run
npm run test:watch
npm run lint
npm run build
```

The Docker image build skips tests (`-DskipTests`); run them separately before building an image.

## Continuous integration

`.github/workflows/ci.yml` (GitHub Actions, workflow `CI`) runs on pushes to `main`, on pull requests and on manual dispatch. It has two independent jobs on `ubuntu-latest`, with read-only repository permissions and no secrets:

- **Backend:** Java 21 (Temurin) with the Maven cache, then `sh ./mvnw -B verify` in `backend`. This runs the full backend suite, with PostgreSQL provided by Testcontainers through the runner's Docker, and packages the Spring Boot jar.
- **Frontend:** Node.js 22 with the npm cache, then `npm ci`, `npm run lint`, `npm test` and `npm run build` in `frontend`, as separate steps.

A newer run on the same ref cancels an older one still in progress. GitHub Dependabot alerts are enabled for the repository; they are reported by GitHub, not by this workflow.

## Infrastructure (`infrastructure/test`)

Jest tests synthesise the CDK stack locally with `Template.fromStack` and assert on the generated CloudFormation. They need no AWS account or credentials and do not deploy anything. They cover the ECR repository (immutability, scan on push, lifecycle rule, retention), the VPC (two AZs, no NAT gateway), the security-group rules (no `0.0.0.0/0` ingress; the owner address parameter on port 80; 8080 and 5432 only between the tiers), the database (encryption, no public access, single AZ, short backup retention), the secrets and log retention, and the Fargate service, load balancer and health check.

```bash
cd infrastructure
npm ci
npm test
```

Synthesis tests show what the template asks for, not what AWS does with it. They say nothing about whether anything is deployed: the repository provides no continuously hosted environment, and deployments are external, owner-managed and created on demand. After a deploy the owner checks the result by hand; these checks are not automated and their results are not recorded here: the load balancer target is healthy, `/actuator/health` answers through the load balancer from the owner's address, the application logs reach CloudWatch, sign-in works after first-run bootstrap, and the database is not reachable from outside the VPC.

## Limitations

- **CI covers the layers above only.** The workflow does not build or test the Docker image, measure coverage, scan dependencies or images, or deploy anything. Whether a given run passed is shown by GitHub Actions, not recorded here.
- **Docker is a hard requirement** for the backend tests. They fail without a running Docker daemon.
- **No end-to-end tests.** No test drives a real browser against the running API and database.
- **No contract tests** between frontend and backend types or between the dashboard and the API.
- **No coverage measurement** is configured, so coverage is unknown.
- **No performance, load or security testing** (image scanning, penetration testing). Dependency alerts come from GitHub Dependabot only; no scan runs in CI. Concurrency behaviour (locking, idempotent payments) is covered only to the extent the integration tests exercise it.
- **No tests of the packaged image** or of the Compose `app` profile. CI does not run the infrastructure tests.
- **Local state.** Running the API (not the tests) against the shared Compose database runs migrations on it.
