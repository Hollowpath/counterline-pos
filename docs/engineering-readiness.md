# Engineering readiness

What this system is scoped to do, what evidence exists for its quality, and which controls are deliberately deferred until a stated trigger occurs. Nothing here claims more than the repository shows.

## Scope

- Built and run by a single owner. No production traffic, no real customers, no service-level commitments.
- Intended workload: a small number of outlets, run locally or in one small cloud environment. A reproducible CDK stack for a temporary, owner-restricted learning deployment on AWS exists, but the repository provides no continuously hosted environment; deployments are external, owner-managed and created on demand (see the [README](../README.md#aws-deployment-status-and-roadmap)); it is not production-ready. No capacity figure is established.
- Single region, single database, single container image. No multi-region, disaster-recovery or high-availability design.
- Out of scope today: real payment-gateway integration (payments are recorded, not processed), tax filing, fiscal printer integration, offline till operation, and multi-tenant isolation.

## Data

- All data in the repository is **synthetic**: the sample catalogue, outlets, staff, members and test fixtures. Sample accounts exist only under the `local` profile.
- Do not load real customer, staff or payment data. Members carry phone numbers, which are personal data; the UI masks them in places (`frontend/src/lib/privacy.ts`), but the system has no data-retention, export or deletion process.
- Secrets never live in the repository. Outside the `local` profile the PIN pepper and JWT key must be supplied by the environment and have no defaults. Local Compose credentials are for localhost only.

## Evidence policy

- Documentation states only what was inspected in the code, configuration or a run the owner can repeat. If something was not checked, it is not claimed.
- Test results are not published in documentation; run the commands in [testing.md](testing.md). Any claim such as "tests pass" belongs with the command, date and commit it came from.
- Planned work is labelled planned. Features not built are not described as present.
- Known gaps are listed here rather than hidden.

## What exists today

| Area              | Present                                                                                                                           | Evidence                                  |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| Automated tests   | Backend integration and unit tests; frontend component and unit tests                                                             | [testing.md](testing.md)                  |
| CI                | GitHub Actions on pushes to `main`, pull requests and manual dispatch: backend Maven verify (Java 21, Testcontainers/PostgreSQL); frontend install, lint, test, build; infrastructure install, build, synthesis tests (no AWS access); packaged-image Trivy gate (fixed HIGH/CRITICAL fail) and smoke test (Compose `app` profile, health endpoint) | `.github/workflows/ci.yml`                |
| Dependency alerts | GitHub Dependabot alerts enabled (reported by GitHub; no scan step in CI)                                                          | Repository security settings on GitHub    |
| Schema management | Versioned Flyway migrations, schema validated at start-up                                                                         | `backend/src/main/resources/db/migration` |
| AuthN/AuthZ       | Channel- and role-scoped JWTs, hashed PINs and passwords, throttle and PIN locks, single-use manager approval for void and refund | [architecture.md](architecture.md)        |
| Container         | Multi-stage image, non-root runtime, health check, memory-aware JVM flags                                                         | `Dockerfile`                              |
| Config            | Environment-variable driven; no secret defaults outside `local`                                                                   | `application.yml`                         |
| Error shape       | Problem-details responses for application and security errors                                                                     | `common/`, `auth/`                        |
| AWS stack (temporary, on-demand) | Owner-managed, reproducible CDK stack, deployed only for testing or demonstrations and destroyed afterwards: ECR, two-AZ VPC without NAT, owner-restricted HTTP ALB, one public-IP Fargate task, encrypted isolated PostgreSQL, Secrets Manager, seven-day logs | `infrastructure/`, [architecture.md](architecture.md) |

## Deferred controls and their triggers

Each item is intentionally not built. Build it when its trigger is met, not before.

| Control                                                                                           | Trigger                                                                                        | Today                                                                                                          |
| ------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Automated delivery (image publishing and deployment) | Now: an AWS environment and its CDK code exist | Not built. CI builds the image only for a local smoke test; it never publishes an image or touches AWS; the owner pushes images and deploys by hand |
| Production availability | Before any use beyond the owner or any data worth keeping | Not built: one task, single-AZ database |
| Autoscaling | When load or availability needs it | Not built: fixed at one task |
| General public access (HTTPS, domain, edge protection) | Before any access beyond the owner | Not built: owner-only HTTP |
| Consume checkout discount approval tokens once (void and refund approvals already are)            | Before real sales or revenue depend on discount approvals, or when approval misuse is observed | Short-lived (5 minutes by default) but reusable until expiry                                                   |
| Restrict or disable Swagger UI / OpenAPI outside local development | Before any access beyond the owner | Served without authentication, including on the AWS deployment (reachable only from the owner address) |
| HTTPS and secure headers at the edge | Before any access beyond the owner (needs a domain the owner controls) | Not built. The load balancer serves plain HTTP, restricted to one owner IPv4 `/32`. Where secure headers are set is not yet decided |
| Rate limiting beyond PIN throttle, bot protection                                                 | Before any persistent or publicly accessible environment                                       | PIN throttle only                                                                                              |
| Cost alerts and a billing-review routine (a budget alert only notifies; it does not cap spending) | Before the first billable cloud resource exists | Trigger met: billable resources are defined. The stack is meant to be deployed briefly and destroyed to avoid idle charges. The owner has configured AWS budget notifications outside the repository; they notify but do not cap or automatically stop spending, and billing review remains manual |
| Backups and a tested restore | Before any environment holds data worth keeping | The database keeps one day of automated backups and is deleted with the stack. No restore has been tested |
| Metrics, alarms, tracing | When deployed beyond a local machine | Application logs go to CloudWatch with seven-day retention. No alarms, custom metrics or tracing; health and info endpoints only |
| Container image vulnerability scanning (CI gate) beyond basic ECR scanning | When CI first builds a container image | Trigger met: CI scans the image it builds with Trivy and fails on fixed HIGH or CRITICAL vulnerabilities (OS and library; unfixed ones do not fail the job). Separately, ECR scans on push (basic scanning) when an image is pushed during a deployment; the two are independent controls |
| SonarCloud code-quality gate                                                                      | When the codebase has more than one regular contributor, or a defect class escapes the tests, lint and build | Deferred; tests, lint, build and dependency alerts give the most value for the current project  |
| End-to-end browser tests                                                                          | When a UI regression escapes the component tests, or before a release candidate                | None                                                                                                           |
| Load / soak tests                                                                                 | Before claiming any capacity figure                                                            | None; no capacity is claimed                                                                                   |
| Audit trail review and data-retention policy                                                      | Before any real personal data is stored                                                        | Not designed                                                                                                   |
| Secrets rotation | When deployed with a managed secrets service | Trigger met: Secrets Manager holds the secrets. No rotation is configured |
| Accessibility audit                                                                               | Before the UI is offered to users outside the owner                                            | Not performed                                                                                                  |

## Review cadence

Revisit this file whenever a trigger is met, when the AWS roadmap changes, or when a gap is closed. Update the table; do not delete history of what was claimed.
