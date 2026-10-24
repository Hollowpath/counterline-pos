# Engineering readiness

What this system is scoped to do, what evidence exists for its quality, and which controls are deliberately deferred until a stated trigger occurs. Nothing here claims more than the repository shows.

## Scope

- Built and run by a single owner. No production traffic, no real customers, no service-level commitments.
- Intended workload: a small number of outlets, run locally or in one small cloud environment once the AWS roadmap in the [README](../README.md#roadmap-aws-planned-not-built) is delivered. No capacity figure is established.
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
| Schema management | Versioned Flyway migrations, schema validated at start-up                                                                         | `backend/src/main/resources/db/migration` |
| AuthN/AuthZ       | Channel- and role-scoped JWTs, hashed PINs and passwords, throttle and PIN locks, single-use manager approval for void and refund | [architecture.md](architecture.md)        |
| Container         | Multi-stage image, non-root runtime, health check, memory-aware JVM flags                                                         | `Dockerfile`                              |
| Config            | Environment-variable driven; no secret defaults outside `local`                                                                   | `application.yml`                         |
| Error shape       | Problem-details responses for application and security errors                                                                     | `common/`, `auth/`                        |

## Deferred controls and their triggers

Each item is intentionally not built. Build it when its trigger is met, not before.

| Control                                                                                | Trigger                                                                                        | Today                                                                 |
| -------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| CI on every push (build, backend and frontend tests, lint)                             | Before the first shared branch or the first deployment pipeline                                | None; tests are run by hand                                           |
| Automated deploy pipeline                                                              | When an AWS environment exists and the owner has written its infrastructure                    | Planned; owner-run                                                    |
| Consume checkout discount approval tokens once (void and refund approvals already are) | Before real sales or revenue depend on discount approvals, or when approval misuse is observed | Short-lived (5 minutes by default) but reusable until expiry          |
| Restrict or disable Swagger UI / OpenAPI outside local development                     | Before any environment is reachable from outside the owner's machine                           | Within the running application they are served without authentication |
| HTTPS and secure headers at the edge                                                   | Before any internet-reachable environment                                                      | Not applicable locally; to be provided by the load balancer           |
| Rate limiting beyond PIN throttle, bot protection                                      | Before any internet-reachable environment                                                      | PIN throttle only                                                     |
| Backups and a tested restore                                                           | Before any environment holds data worth keeping                                                | None                                                                  |
| Centralised logs, metrics, alarms, tracing                                             | When deployed beyond a local machine                                                           | Health and info endpoints only                                        |
| Dependency and image vulnerability scanning                                            | When CI exists                                                                                 | None                                                                  |
| End-to-end browser tests                                                               | When a UI regression escapes the component tests, or before a release candidate                | None                                                                  |
| Load / soak tests                                                                      | Before claiming any capacity figure                                                            | None; no capacity is claimed                                          |
| Audit trail review and data-retention policy                                           | Before any real personal data is stored                                                        | Not designed                                                          |
| Secrets rotation                                                                       | When deployed with a managed secrets service                                                   | Not applicable locally                                                |
| Accessibility audit                                                                    | Before the UI is offered to users outside the owner                                            | Not performed                                                         |

## Review cadence

Revisit this file whenever a trigger is met, when the AWS roadmap changes, or when a gap is closed. Update the table; do not delete history of what was claimed.
