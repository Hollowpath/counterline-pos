# Engineering readiness

What this system is scoped to do, what evidence exists for its quality, and which controls are deliberately deferred until a stated trigger occurs. Nothing here claims more than the repository shows.

## Scope

- Built and run by a single owner. No production traffic, no real customers, no service-level commitments.
- Intended workload: a small number of outlets, run locally or in one small cloud environment. A reproducible CDK stack for a temporary, owner-run, owner-restricted deployment on AWS exists, but the repository provides no continuously hosted environment; deployments are external, owner-managed and created on demand (see the [AWS deployment status](aws-deployment.md)); it is not production-ready. No capacity figure is established.
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
| CI                | GitHub Actions on pushes to `main`, pull requests and manual dispatch: backend Maven verify (Java 21, Testcontainers/PostgreSQL); frontend install, lint, test, build; infrastructure install, build, synthesis tests (no AWS access); packaged-image Trivy gate (fixed HIGH/CRITICAL fail), smoke test (Compose `app` profile, health endpoint) and Playwright end-to-end smoke tests (listed in [testing.md](testing.md)) | `.github/workflows/ci.yml`                |
| Dependency scanning | CI scans the packaged image with Trivy (OS packages and libraries). Dependabot version-update pull requests are configured for minor and patch updates to Maven, npm (frontend, infrastructure), GitHub Actions and Docker (the Dockerfile's base images and Compose's images); major platform versions stay pinned. Alerts and security updates are repository settings that are not recorded here. No source-dependency scan runs in CI                                                          | [testing.md](testing.md), `.github/workflows/ci.yml`, `.github/dependabot.yml` |
| Schema management | Versioned Flyway migrations, schema validated at start-up                                                                         | `backend/src/main/resources/db/migration` |
| AuthN/AuthZ       | Channel- and role-scoped JWTs, hashed PINs and passwords, throttle and PIN locks, single-use, operation-bound manager approval for discount, void and refund | [architecture.md](architecture.md)        |
| Container         | Multi-stage image, non-root runtime, health check, memory-aware JVM flags                                                         | `Dockerfile`                              |
| Config            | Environment-variable driven; no secret defaults outside `local`                                                                   | `application.yml`                         |
| Error shape       | Problem-details responses for application and security errors                                                                     | `common/`, `auth/`                        |
| AWS stack (temporary, on-demand) | Owner-managed, reproducible CDK stack, meant to be deployed only for testing or demonstrations and destroyed afterwards: ECR, two-AZ VPC without NAT, owner-restricted HTTP ALB, one public-IP Fargate task, encrypted isolated PostgreSQL, Secrets Manager, seven-day logs | `infrastructure/`, [architecture.md](architecture.md), [deployment-verification-2026-10-31.md](deployment-verification-2026-10-31.md) |

## Deferred controls and their triggers

Each item is intentionally not built. Build it when its trigger is met, not before.

| Control                                                                                           | Trigger                                                                                        | Today                                                                                                          |
| ------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Automated delivery (image publishing and deployment) | Now: the CDK code that defines an AWS environment exists | Not built. CI builds the image only to scan and smoke-test it on the runner; it never publishes an image or touches AWS; the owner pushes images and deploys by hand |
| Production availability | Before any use beyond the owner or any data worth keeping | Not built: one task, single-AZ database |
| Autoscaling | When load or availability needs it | Not built: fixed at one task |
| General public access (HTTPS, domain, edge protection) | Before any access beyond the owner | Not built: owner-only HTTP |
| HTTPS and HSTS | Before any access beyond the owner (needs a domain the owner controls) | Not built. The load balancer serves plain HTTP, restricted to one owner IPv4 `/32`. The application already sets a content security policy, `Referrer-Policy`, `nosniff` and frame-deny; `Strict-Transport-Security` is deliberately absent until HTTPS exists |
| Rate limiting beyond PIN throttle, bot protection                                                 | Before any persistent or publicly accessible environment                                       | PIN throttle only                                                                                              |
| Cost alerts and a billing-review routine (a budget alert only notifies; it does not cap spending) | Before the first billable cloud resource exists | Budget notifications and a manual billing-review routine are required before any deployment. They are external to this repository, which does not create or verify them, and they do not cap spending. The stack is meant to be deployed briefly and destroyed to avoid idle charges |
| Backups and a tested restore | Before any environment holds data worth keeping | The database keeps one day of automated backups and is deleted with the stack. No restore has been tested |
| Metrics, alarms, tracing | When deployed beyond a local machine | Application logs go to CloudWatch with seven-day retention. No alarms, custom metrics or tracing; health and info endpoints only |
| Container image vulnerability scanning (CI gate) beyond basic ECR scanning | When CI first builds a container image | Trigger met: CI scans the image it builds with Trivy and fails on fixed HIGH or CRITICAL vulnerabilities (OS and library; unfixed ones do not fail the job). Separately, ECR scans on push (basic scanning) when an image is pushed during a deployment; the two are independent controls | CI run 42568232541 (9 November 2026) failed only in this job, while installing Trivy inside the action: the log shows the installer reaching "found version" and then exiting with code 1 without a message. The installer downloads the archive and the release checksums file and hides download errors, so the exact failing request is not proven; at the time of the check the checksums file URL answered HTTP 504 while the archive downloaded normally, which is consistent with, but not proof of, that cause. CI now installs the same Trivy release in its own step (see testing.md). Unverified until the workflow runs on GitHub.
| SonarCloud code-quality gate                                                                      | When the codebase has more than one regular contributor, or a defect class escapes the tests, lint and build | Deferred; tests, lint, build and dependency updates give the most value for the current project  |
| End-to-end browser tests beyond the smoke tests                                               | When a UI regression escapes the component tests, or before a release candidate                | Playwright smoke tests (Chromium, listed in testing.md) run in CI against the packaged image; nothing broader                                                                                                        |
| Load / soak tests                                                                                 | Before claiming any capacity figure                                                            | None; no capacity is claimed                                                                                   |
| Audit trail review and data-retention policy                                                      | Before any real personal data is stored                                                        | Partly built: staff changes and timecard corrections record a stable actor (type and id) in an append-only log, never a secret ([architecture.md](architecture.md)). No other change (catalogue, outlets, prices) is recorded beyond application logs, and there is no review process or retention policy |
| Secrets rotation | When deployed with a managed secrets service | Trigger met: the stack defines Secrets Manager secrets. No rotation is configured |
| Accessibility audit                                                                               | Before the UI is offered to users outside the owner                                            | Not performed                                                                                                  |

## Accepted dependency advisories

**GHSA-g3hv-75wg-m249 (CVE-2026-118764), `minimatch` before 11.0.1.** A medium denial of service: someone who can supply a glob pattern can make matching use excessive CPU. GitHub raised two Dependabot alerts for it, one for each lockfile below, and both were dismissed as "Risk is tolerable to this project" on 2026-10-31 (17:57 UTC). Alert state belongs to the GitHub repository, not to the code, so it can be absent or reset. If the alerts reappear, do not dismiss them again on the strength of this note: first reassess them against the current advisory, the dependency versions now in the lockfiles, the code paths that reach `minimatch`, and the exposure of those paths, and dismiss only if the conclusion below still holds. No manifest, lockfile or code was changed.

- **Frontend (development only).** `minimatch` 3.1.5 (via `eslint`, `@eslint/config-array`, `@eslint/eslintrc`) and 10.2.6 (via `@typescript-eslint/typescript-estree`). ESLint's patterns are fixed strings in `frontend/eslint.config.js`. `typescript-estree` calls `minimatch` only for `allowDefaultProject`, which is not configured. Neither copy is in the image, and CI runs with read-only permissions and no secrets.
- **Infrastructure (labelled runtime by GitHub because `aws-cdk-lib` is a dependency).** `minimatch` 10.2.6 is bundled inside `aws-cdk-lib`, so it cannot be overridden. The tooling is run by the owner from a trusted checkout and is not shipped. The stack uses no assets, excludes or glob patterns.
- **Why it is not fixed.** On 2026-10-31 no released `eslint`, `typescript-estree`, `@eslint/*` or `aws-cdk-lib` allowed `minimatch` 11 or later, 10.x has no backport, and the 3.x users call `minimatch` as a function, which later versions do not export.
- **Correction (9 November 2026).** The advisory's affected range is `minimatch` 7.0.0 up to but not including 11.0.1 (OSV record; `npm audit` agrees). The 3.1.5 copy above (via `eslint`) is therefore **outside** the range and was never affected; the statement that "neither copy" is exposed was true, but listing 3.1.5 as part of the finding was stale. Only the two 10.2.6 copies are affected: `@typescript-eslint/typescript-estree` in the frontend and the copy bundled inside `aws-cdk-lib` in infrastructure. Per the advisory, exploitation also needs the matcher to run with `optimizationLevel` 2 or higher, in addition to an attacker-supplied pattern.
- **Reassess when** `eslint`, `typescript-eslint` or `aws-cdk-lib` releases a version that depends on `minimatch` 11 or later (Dependabot's minor and patch pull requests will propose it); when any of these tools starts taking glob patterns from untrusted input, for example `allowDefaultProject` or assets with `exclude` patterns; or when a new advisory affects these paths.

### Dependency reassessment (9 November 2026)

- **Status of the acceptance.** The dismissals of 2026-10-31 are recorded history. This reassessment does **not** extend, renew or widen them: any future alert is reassessed from scratch. GitHub's alert state was not inspected. No manifest, lockfile, code or advisory setting was changed, and no test was rerun.
- **Who checked what.** Codex reported that the npm findings reduce to this one advisory and that 178 resolved Maven artifacts returned no OSV advisories. Claude Code reproduced both on 9 November 2026 with read-only commands.
- **npm (Claude Code).** `frontend`: `npm audit` reports 7 moderate findings, all one chain from `typescript-eslint` 8.72.1 (per the lockfile) through `@typescript-eslint/typescript-estree` 8.72.1 to `minimatch` 10.2.6; `npm audit --omit=dev` reports 0. `infrastructure`: 2 moderate findings, `aws-cdk-lib` 2.275.0 with its bundled `minimatch` 10.2.6 (production-labelled; none in `frontend`'s runtime). Evidence comes from the lockfiles; the local `frontend/node_modules` holds `typescript-eslint` 8.71.1, which `npm ls` reports as out of sync with the lockfile.
- **Attacker-controlled pattern (Claude Code).** The patterns reaching `minimatch` are fixed by the maintainer: `frontend/eslint.config.js` globs, no `allowDefaultProject`, and no `exclude`, asset or glob option in `infrastructure` source (checked by search). Someone who could change those files could already run code in CI or on the owner's machine. This is an assessment, not an exercise of the advisory.
- **Upstream on 9 November 2026 (Claude Code, `npm view`).** `minimatch` 11.0.5 is the latest and the fix starts at 11.0.1 with no 10.x backport. `@typescript-eslint/typescript-estree` 8.72.1 (the latest, and the locked version) depends on `^10.2.2`, `eslint` 10.14.0 on `^10.2.6`, and `aws-cdk-lib` 2.275.0 (the latest, and the locked version) bundles 10.2.6, so no release removes it. The `eslint` 9.39.5 line locked here uses `minimatch` 3.1.5, which is outside the affected range.
- **Maven (Claude Code).** `backend` resolves 178 artifacts (107 compile, 11 runtime, 60 test) from `./mvnw dependency:list`, including Spring Boot 4.1.2, `spring-webmvc` 7.0.10, `tomcat-embed-core` 11.0.27, `jackson-databind` 3.1.7 and 2.21.7 and PostgreSQL JDBC 42.7.14. Querying the public OSV.dev API with those names and versions returned **0 advisories**. That is a point-in-time result, not a claim of advisory-free software.
- **Limits.** OSV and `npm audit` snapshots only; Maven plugin classpaths, the container image (covered separately by Trivy in CI), reachability and other databases were not checked; the OSV-Scanner binary was not used.
- **Recheck when** the triggers listed above occur, when Spring Boot or any resolved Maven version changes, or on a new advisory for the resolved set.

## CSRF protection disabled: CodeQL alert #1 (assessed 9 November 2026)

**Alert.** `java/spring-disabled-csrf-protection` (high), open, at `SecurityConfig.java` lines 92 to 93 (`.csrf(AbstractHttpConfigurer::disable)`). Nothing was dismissed or changed in GitHub; the alert state is the owner's to decide after reading this record.

**What CSRF needs.** A forged cross-site request only works if the browser attaches the victim's credential by itself: a cookie, a server-side session, HTTP Basic or another browser-managed credential. "The application is stateless" is not the justification here. The justification is that no such credential can authenticate a request in this application, and that is checked rather than assumed.

**Authentication channels inspected (source, 9 November 2026).** One security filter chain. Requests authenticate only through `oauth2ResourceServer().jwt()` with the default bearer-token resolution, which reads the `Authorization: Bearer` header and has the query-parameter and form-body methods switched off. No `httpBasic`, `formLogin`, `rememberMe`, logout filter or session handling is configured; the session policy is stateless; a search of the main source for cookies and HTTP sessions found none. The unauthenticated endpoints (`permitAll`: PIN login, terminal setup, PIN lock, outlet reads, display connect, dashboard login) receive no ambient credential and return a token in the response body.

**Evidence.** `AuthenticationChannelsIntegrationTest` (real security filters, PostgreSQL, synthetic staff, head-office account and member; tokens and passwords never printed) covers one till mutation (adding a member) and one dashboard mutation (deactivating a member):
- a valid till or dashboard token in cookies alone, in nine cookies (names a framework or convention might read, including `Authorization`, `JSESSIONID` and `SESSION`), is refused (401) and changes nothing;
- HTTP Basic, including real head-office credentials and a token used as the Basic username or password, is refused;
- a token in the query string (as a parameter on the POST and on a read) or in a form-encoded body is refused;
- the same requests with a bearer header succeed (201 and 200);
- till login, dashboard login, terminal setup and an authenticated mutation each issue no cookie, no `Set-Cookie` header and no server-side session.

Result on 9 November 2026 (Claude Code, local): the five new tests passed together with the existing `AuthApiIntegrationTest` and `LogoutAndAdminSessionIntegrationTest` (27 tests, none failing). As a check that the tests can fail, the query-string and form-body token methods were temporarily enabled in `SecurityConfig` and the new tests failed; the change was reverted and no application code differs. No application behavior, dependency or setting was changed to address the alert.

**Conclusion.** The alert's premise, a request authenticated by a browser-attached credential, does not hold for the current code: no cookie, Basic, form or session channel authenticates a request, and an attacker's page cannot supply the bearer header. The alert remains open in GitHub until the owner decides what to do with it.

**Limits.** MockMvc, not a browser or a deployed stack: it does not cover a reverse proxy, an ALB, CORS configuration (not assessed here), or the Swagger UI paths. The tests show today's behavior only and do not prevent a future change. The attacker-page scenario was reasoned about, not exercised in a browser. CodeQL has not been rerun on this change.

**Renewed CSRF review is required before merging any change that** (1) sets an authentication cookie or starts a server-side session, including "remember me" and a backend-for-frontend design; (2) adds HTTP Basic or Digest, form login, client certificates or another browser-managed credential; (3) adds an alternative token source, such as reading the token from a cookie, the query string, a form body or a custom resolver, or enables `allowUriQueryParameter` or `allowFormEncodedBodyParameter`; (4) changes the session creation policy; (5) adds an OAuth or single-sign-on login redirect flow; or (6) needs `AuthenticationChannelsIntegrationTest` assertions to change. Re-enabling CSRF protection, with tokens, is the response if any of these is accepted.

## Review cadence

Revisit this file whenever a trigger is met, when the AWS roadmap changes, or when a gap is closed. Update the table; do not delete history of what was claimed.
