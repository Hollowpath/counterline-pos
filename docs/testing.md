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

### End-to-end smoke tests (`frontend/e2e`)

Playwright tests (`@playwright/test`, Chromium only, line reporter; configuration in `frontend/playwright.config.ts`) drive a real browser against an already-running application. They start nothing themselves: the base URL is `http://localhost:8090` unless `E2E_BASE_URL` says otherwise, which is where the Compose `app` profile (the packaged image, `local` profile) listens. The files are named `*.e2e.ts`, so Vitest does not discover them. They use only the `local` profile's synthetic sample accounts and catalogue (README, "Run locally") and introduce no separate fixtures or seed data; the till test does record one synthetic sale.

- **Display posters (`posters.e2e.ts`):** the display app is opened with its own API answered by Playwright route interception and moved with the page clock (the machine clock is never changed): named keys show only their accurate posters, unknown keys are ignored, and a missing, failed, already-expired or expired answer leaves only neutral posters.
- **Other files:** the security headers and Content-Security-Policy (`csp.e2e.ts`), and, in `till.e2e.ts`, the register's opening float and pay-out limit and the phone-width cart.
- **Dashboard:** head office signs in, the sales overview appears, and Items & Prices shows a seeded folder and item with its price.
- **Till:** the iPad is set up for the sample outlet with the manager PIN, the cashier signs in, the register is opened if it is closed, a seeded item is added and paid for in cash, and the "Sale complete" dialog confirms the sale was recorded. An outlet has one till, so a till left over from an earlier run against the same database is reset first through the dashboard, as head office would.

```bash
docker compose --profile app up --no-build -d     # after building counterline-pos:local; wait for /actuator/health
cd frontend
npm ci
npx playwright install chromium                   # once
npm run test:e2e
docker compose --profile app down                 # without -v: that would also remove the development database volume
```

Each run records one more sale in the Compose `app` database. These tests prove that the packaged application serves all three web apps (POS, customer display and dashboard) and completes one sign-in and one sale; they are not a regression suite.

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

`.github/workflows/ci.yml` (GitHub Actions, workflow `CI`) runs on pushes to `main`, on pull requests and on manual dispatch. It has four independent jobs on `ubuntu-24.04`, with read-only repository permissions and no secrets:

- **Backend:** Java 21 (Temurin) with the Maven cache, then `sh ./mvnw -B verify` in `backend`. This runs the full backend suite, with PostgreSQL provided by Testcontainers through the runner's Docker, and packages the Spring Boot jar.
- **Frontend:** Node.js 22 with the npm cache, then `npm ci`, `npm run lint`, `npm test` and `npm run build` in `frontend`, as separate steps.
- **Infrastructure:** Node.js 22 with the npm cache, then `npm ci`, `npm run build` (type-check) and `npm test` in `infrastructure`. These are the synthesis tests described below. The job needs no AWS credentials, contacts no AWS service, and runs no `cdk diff`, `synth` or `deploy`.
- **Packaged image:** builds the root `Dockerfile` as `counterline-pos:local`, scans that image with Trivy (`aquasecurity/trivy-action`, pinned to a commit SHA; table output in the job log only) for OS-package and application-library vulnerabilities, and fails the job for any HIGH or CRITICAL vulnerability that has a fix available (vulnerabilities with no fix available are excluded by the configured `ignore-unfixed` policy; there is no project-specific ignore file and no CVE is suppressed). Only if the scan passes does it start the Compose `app` profile with `--no-build` (so that exact image runs, with its own database and the `local` profile), and polls `http://localhost:8090/actuator/health` for `"status":"UP"` for up to three minutes. Container status and application logs are printed only if that fails, and the containers and their volumes are always removed on the runner. Once the app is healthy, the same job runs the end-to-end smoke tests against it (below): Node.js 22 with the npm cache, `npm ci` in `frontend`, Chromium installed with `npx playwright install --with-deps chromium`, then `npm run test:e2e`. It does not rerun the backend or frontend unit tests, push an image, or use AWS or credentials, and it uploads no test artifacts.

A newer run on the same ref cancels an older one still in progress. Dependency version updates are proposed by Dependabot, configured in `.github/dependabot.yml` for Maven (`backend`), npm (`frontend` and `infrastructure`), GitHub Actions and the Dockerfile's base images and Compose's images. Only minor and patch updates are proposed: Java 21, Node 22 and PostgreSQL 17 stay pinned. Dependabot opens pull requests that run this workflow and is not part of it.

## Infrastructure (`infrastructure/test`)

Tests (the Node.js test runner, `node:test`, run through `tsx`) synthesise the CDK stack locally with `Template.fromStack` and assert on the generated CloudFormation. They need no AWS account or credentials and do not deploy anything. They cover the ECR repository (immutability, scan on push, lifecycle rule, retention), the VPC (two AZs, no NAT gateway), the security-group rules (no `0.0.0.0/0` ingress; the owner address parameter on port 80; 8080 and 5432 only between the tiers), the database (encryption, no public access, single AZ, short backup retention), the secrets and log retention, and the Fargate service, load balancer and health check.

```bash
cd infrastructure
npm ci
npm test
```

Synthesis tests show what the template asks for, not what AWS does with it. They say nothing about whether anything is deployed: the repository provides no continuously hosted environment, and deployments are external, owner-managed and created on demand. After a deploy the owner checks the result by hand; these checks are not automated and their results are not recorded here, apart from one dated run in [deployment-verification-2026-10-31.md](deployment-verification-2026-10-31.md): the load balancer target is healthy, `/actuator/health` answers through the load balancer from the owner's address, the application logs reach CloudWatch, sign-in works after first-run bootstrap, and the database is not reachable from outside the VPC.

## Limitations

- **CI is a set of checks, not a release gate for AWS.** It builds and tests the backend, frontend and CDK synthesis, scans the packaged image with Trivy (fixed HIGH/CRITICAL only; a point-in-time check, so a new advisory can fail a later run of unchanged code), starts that image and runs the Playwright smoke tests against it. It never deploys, and nothing automated verifies a deployed AWS environment or its runtime behaviour; the synthesis tests show what the template asks for, not what AWS does with it. Whether a given run passed is shown by GitHub Actions, not recorded here.
- **Browser coverage is smoke tests only** (above): no other screen, role, payment method or failure path is driven through a browser, there is no cross-browser run, and the only phone-width coverage is the cart test in `till.e2e.ts`.
- **No contract tests** between frontend and backend types or between the dashboard and the API.
- **No coverage measurement** is configured, so coverage is unknown.
- **No source-dependency scan in CI.** The Trivy scan covers the built image, not the lock files as such. Dependabot opens version-update pull requests (`.github/dependabot.yml`), but whether Dependabot alerts and security updates are enabled is a repository setting that is not recorded here.
- **No performance, load or penetration testing.** Concurrency behaviour (locking, idempotent payments) is covered only to the extent the integration tests exercise it.
- **Docker is a hard requirement** for the backend tests. They fail without a running Docker daemon.
- **Local state.** Running the API (not the tests) against the shared Compose database runs migrations on it.
