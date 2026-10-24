# Testing

What test layers exist, how to run them, and what they do not cover. The counts below come from the files in the repository; pass/fail status is not recorded here because results change with every run.

## Layers

### Backend (`backend/src/test`)

JUnit 5 with Spring Boot test support. 19 test classes in two kinds:

- **Integration tests** (most of them, named `*IntegrationTest` or `*Tests`) start the Spring application against a real PostgreSQL 17 container through Testcontainers (`TestcontainersConfiguration`). Flyway runs the real migrations, so schema and queries are exercised for real. Areas: auth and roles, catalogue, checkout, order lifecycle, options and discounts, promotions and loyalty, vouchers and adjustments, customers, register and timecards, reports, multi-outlet, customer display, dashboard, and static web app serving.
- **Unit tests** for isolated rules: `DiscountCalculatorTest`, `SaleOrderTest`, `LoginAttemptLimiterTest`, `PhoneNumbersTest`.

Test data comes from SQL fixtures in `backend/src/test/resources` (`catalog-fixture.sql`, `options-fixture.sql`, `promotions-fixture.sql`) and a stub web app page under `static/`. Test configuration (`application-test.yml`) uses throwaway secrets.

### Frontend (`frontend/src`)

Vitest in a jsdom environment with Testing Library; setup in `src/test/setup.ts`. 23 test files, colocated with the code (`*.test.ts` / `*.test.tsx`):

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

## Limitations

- **No CI.** There is no workflow file in the repository, so nothing runs the tests automatically.
- **Docker is a hard requirement** for the backend tests. They fail without a running Docker daemon.
- **No end-to-end tests.** No test drives a real browser against the running API and database.
- **No contract tests** between frontend and backend types or between the dashboard and the API.
- **No coverage measurement** is configured, so coverage is unknown.
- **No performance, load or security testing** (dependency scanning, penetration testing). Concurrency behaviour (locking, idempotent payments) is covered only to the extent the integration tests exercise it.
- **No tests of the packaged image** or of the Compose `app` profile.
- **Local state.** Running the API (not the tests) against the shared Compose database runs migrations on it.
