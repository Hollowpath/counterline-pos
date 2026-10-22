# Counterline POS engineering readiness

## Purpose and scope

This document defines evidence-based gates for changing and operating Counterline POS. It describes the repository as inspected on 19 October 2026. It is not a certification, compliance statement or legal opinion.

Counterline currently runs locally. The repository contains a React/TypeScript POS, customer display and dashboard; one Spring Boot API; PostgreSQL/Flyway persistence; Docker packaging; and automated unit/integration tests. It does **not** contain an AWS CDK application, CloudFormation template, deployment environment or GitHub Actions workflow. AWS services named in the README are roadmap items only.

When legal, privacy, employment, payment or contractual applicability is uncertain, obtain qualified advice before using real data or offering the system to another party.

## Outcomes

Every readiness decision uses one outcome:

1. **Satisfied** — inspectable evidence meets the item for the stated stage.
2. **Not applicable** — the item does not apply at the current stage, for the factual reason recorded.
3. **Deferred** — work is postponed; the limitation, risk, owner and trigger are recorded.
4. **Blocked** — an applicable gate cannot be crossed until the named evidence exists.

“Not found in the repository” is not proof that an external control is absent; it is evidence that its state is unknown.

## Read-only audit baseline

| Area | Outcome | Repository evidence and present limitation |
|---|---|---|
| Product surfaces | Satisfied | `frontend/` builds three entry points: till POS, customer display and back-office dashboard. |
| Local runtime | Satisfied | `docker-compose.yml` provides local PostgreSQL and an optional whole-app profile; `Dockerfile` packages the three web apps into the API image. |
| Cloud infrastructure | Not applicable | No CDK or CloudFormation exists and no deployment was inspected. Reassess before any cloud resource is created. |
| Schema ownership | Satisfied | Flyway migrations V1–V22 own schema evolution; Hibernate uses `ddl-auto: validate`. |
| Authentication | Satisfied, local baseline only | Till staff use outlet-bound device keys and HMAC-protected PIN lookup; dashboard users use BCrypt passwords; signed JWTs include issuer, role, channel and outlet claims. This does not establish production identity readiness. |
| Authorization | Satisfied, local baseline only | `SecurityConfig`, method authorization, outlet scoping and channel-specific tokens separate till, display, dashboard and one-action manager approval access. Integration tests cover major role/outlet paths. |
| Sensitive actions | Satisfied, local baseline only | Refund/void require manager authority; approval JWT IDs are recorded once; checkout has request idempotency; optimistic/pessimistic locking protects concurrent business actions. |
| Personal data | Deferred | Customer identity/contact/address/birth date/gender/notes/marketing choices, staff data, timecards and transaction history are persisted. Masking and soft deletion exist, but retention, export, rights handling and legal-hold rules do not. **Owner:** product/privacy owner. **Trigger:** before real personal data. |
| External integrations | Not applicable | No payment gateway, bank, accounting, payroll, email, SMS, push, analytics, error-reporting, OAuth or upload service was found. Payment records are internal method/reference records, not evidence of payment processing integration. |
| Automated tests | Satisfied as present evidence | JUnit/MockMvc/Testcontainers and Vitest/Testing Library suites exist. No test was run during this documentation task, so current pass status is not claimed. |
| CI/CD | Deferred | No workflow exists. Some Docker/README wording anticipates CI, but no repository evidence supports an active pipeline. **Owner:** repository owner. **Trigger:** before protected automated releases or claiming CI checks. |
| Repository controls | Deferred | The remote is GitHub, but branch rules, reviews, security scanning and alert settings cannot be established from files. **Owner:** repository administrator. **Trigger:** before collaboration or public release. |
| Operational controls | Deferred | No deployed logging, tracing, alerting, backup, recovery, incident or rollback system exists. **Owner:** future service owner. **Trigger:** before persistent cloud data or external availability. |

## Data and trust boundaries

### Stored data

- Customers: identifiers, names, company, birth date, gender, email, phone, marketing choices, address, notes, loyalty ledger, owed items and order analytics.
- Workforce: staff identity, role, outlet, PIN HMAC, manager dashboard credentials and timecard events.
- Commerce: catalogue, prices, discounts, promotions, vouchers, orders, notes, payment method/reference, refunds, register movements and reports.
- Security state: till-device keys, display sessions, login throttles, used approval-token IDs and account status.

### Current boundaries

1. Browser storage holds the till device key in `localStorage` and the bearer session in `sessionStorage`.
2. The three same-origin web apps call the Spring API; no separate CORS policy is configured.
3. The API enforces JWT channel, role and outlet boundaries and reads/writes PostgreSQL.
4. The customer display has a restricted token and API but is physically public-facing.
5. Docker Compose credentials and local-profile secrets are intentionally local-only.

Before external use, threat modelling must address malicious staff, stolen/shared PINs, exposed browser storage, XSS, lost tills, manager/cashier collusion, fraudulent refunds or points adjustments, replay/duplicate requests, display-code guessing, direct API access and denial of service.

## Stage gates

| Gate | Current outcome | Evidence required to cross |
|---|---|---|
| Before real users or real personal data | Blocked | Approved data inventory and purposes; minimization; notice/choice; retention/deletion/export/correction procedures; access model; threat model; incident contacts; secure non-demo secrets; tested backup/restore; and a documented support path. |
| Before cloud infrastructure or persistent cloud data | Blocked | Reviewed architecture/IaC; isolated environments; account/IAM/MFA design; encryption and secret management; private network boundaries; audit logging; backups and restore exercise; budgets/anomaly alerts; teardown ownership; and a data-location/vendor decision. |
| Before external usability | Blocked | TLS and domain ownership; production configuration; security headers; capacity/throttle limits; monitoring/alerts; runbooks; rollback; browser/E2E/accessibility evidence; privacy and terms decisions; vulnerability handling; and incident response. |
| Before making the repository public | Blocked | Secret and full-history scan; review of demo/reference material; asset/code/font/icon licence inventory; repository licence decision; product-name/trademark review; truthful README claims; security policy; contribution expectations; and verified GitHub protections or documented compensating controls. |
| Before calling the system complete | Blocked | Defined acceptance criteria; full-suite and real-browser evidence; unresolved-risk register; recovery and incident exercises; operational ownership; maintenance/version policy; accessibility assessment; and documentation aligned with actual behaviour. |
| Before commercial or third-party use | Blocked | Qualified legal/privacy/payment/employment advice as applicable; contracts and support boundaries; controller/processor/vendor allocation; subprocessor and transfer review; service levels; insurance/tax decisions where relevant; vulnerability and end-of-life commitments; and production security/recovery evidence. |

## Readiness register

### Repository, dependencies and supply chain

| Item | Outcome | Limitation, owner and trigger/evidence |
|---|---|---|
| Ignore local secrets/build output | Satisfied | Root and component ignore rules cover common secrets, dependency/build output and local settings; `/start/` is listed in the tracked `.gitignore`. |
| Secret and history hygiene | Blocked before publication | Current files alone cannot prove old commits are clean. **Owner:** repository owner. Run an approved full-history secret/private-material scan and rotate anything exposed before publication. |
| Locking and provenance | Satisfied, partial | npm lockfile and Maven wrapper exist; container bases are named. Image digests, SBOMs and release attestations are absent. |
| Install-script review | Deferred | npm lifecycle behaviour and transitive Maven/npm risks have not been documented. **Owner:** maintainer. **Trigger:** before dependency updates or CI installs. |
| Dependency vulnerability process | Deferred | No automated update, dependency review or vulnerability-response policy was found. **Owner:** maintainer. **Trigger:** before external use. |
| Licence and asset governance | Blocked before publication/commercial use | No root licence or complete third-party asset inventory was found. Review code, Inter, country flags, inline icons, images/templates and generated contributions. |
| Branch protection | Deferred | External GitHub settings are unknown. **Owner:** repository administrator. **Trigger:** before collaborators/publication; use rulesets/reviews/status checks or document compensating controls. |

### Data protection and governance

| Item | Outcome | Limitation, owner and trigger/evidence |
|---|---|---|
| Data inventory/classification | Deferred | Fields are inspectable but no approved classification, purpose or owner register exists. **Trigger:** before real data. |
| Minimization | Deferred | Birth date, gender, addresses, notes and marketing choices require field-by-field necessity review. **Owner:** product/privacy owner. |
| Retention/deletion/legal hold | Blocked before real data | Customer soft deletion preserves rows indefinitely; no retention schedule, purge mechanism or hold procedure exists. |
| Access, correction and export | Blocked before real data | Staff can edit customer data, but no authenticated individual-request/export process, verification standard or response log exists. |
| Privacy notice and consent | Blocked before real data | Marketing booleans exist; no notice/version/evidence model or withdrawal procedure was found. |
| Controller/processor/vendors/transfers | Not applicable now | No external environment or vendor flow exists. Decide roles, subprocessors and cross-border locations before cloud or third-party processing. |
| Breach notification/evidence | Blocked before real data | No incident classification, notification decision log, evidence-preservation plan or contact roster exists. Obtain qualified advice for applicable jurisdictions. |

### Architecture, release and recovery

| Item | Outcome | Limitation, owner and trigger/evidence |
|---|---|---|
| Schema migrations | Satisfied | Ordered Flyway migrations and integration tests provide inspectable baseline; production rollback/forward-fix policy remains future work. |
| Environment separation | Not applicable now | Only local profiles/containers exist. Block cloud work until dev/test/prod accounts, data and secrets are separated. |
| Release identification/provenance | Deferred | Artifact version is snapshot/0.0.0 and no immutable release metadata or provenance exists. **Trigger:** before external deployment. |
| Rollback/configuration drift | Not applicable now | No deployment exists. Require versioned IaC, immutable images, config inventory and rehearsed rollback before external use. |
| Backup/restore/recovery | Blocked before persistent non-demo data | Docker volumes persist locally but are not a backup. Define objectives, encrypted backups, restore ownership and a witnessed restore exercise. |

### Security and abuse resistance

| Item | Outcome | Limitation, owner and trigger/evidence |
|---|---|---|
| Threat model/trust boundaries | Deferred | Boundaries are inferable, but no reviewed threat model or residual-risk decisions exist. **Trigger:** before cloud design or real data. |
| Identity provisioning and departures | Blocked before real workforce use | Active flags exist, but no approved joiner/mover/leaver, recovery or emergency-access procedure exists. |
| MFA and account recovery | Deferred | No MFA or recovery flow exists. **Owner:** security/product owner. **Trigger:** before remote dashboard access; decide risk-based requirements. |
| Browser/session security | Deferred | JWTs last 12 hours; sessions/device keys use browser storage; no global revocation mechanism was found. Assess XSS, lost-device and logout/revocation requirements before external use. |
| OAuth | Not applicable | No OAuth/OIDC integration exists. Reassess before adding an identity provider. |
| API authorization | Satisfied, local baseline | Channel/role/outlet rules and tests exist. Add a complete endpoint-role matrix and adversarial direct-API tests before external use. |
| CORS/caching/security headers | Deferred | Same-origin packaging avoids normal cross-origin need; HTML/assets have cache intent. CSP, HSTS and other deployment headers are not established. **Trigger:** before external hosting. |
| Uploads/untrusted files | Not applicable | No upload path or parser was found. Any upload feature triggers format allowlists, size/quota limits, storage isolation, malware/content handling and download headers. |
| Business-logic abuse | Satisfied, partial | Idempotency, locking, register gates, outlet scoping, manager approval and one-use approval tokens exist. Fraud limits, collusion review, audit reporting and separation-of-duty decisions remain deferred until real operations. |
| Payment scope | Not applicable now | The app records cash/card/QR method and terminal references but does not store card data or integrate a processor. Reassess PCI and contractual scope before any payment-account integration. |

### Observability, incidents and capacity

| Item | Outcome | Limitation, owner and trigger/evidence |
|---|---|---|
| Error sanitization | Satisfied, partial | RFC 9457 handlers return controlled domain messages; a production review must test framework errors and logs for personal/secret leakage. |
| Logging/tracing/audit | Deferred | Application logs cover selected security actions; no central store, trace IDs, retention, immutable audit trail or access policy exists. **Trigger:** before external use. |
| Alarms/runbooks/replay | Not applicable now | No service operates externally. Required before deployment: actionable alerts, owner/on-call path, runbooks and safe replay/reconciliation for failed actions. |
| Capacity/throttling/quotas | Deferred | Authentication throttles exist; general API, search, report and display polling limits are not evidenced. **Trigger:** before exposure or multi-outlet load testing. |
| Denial-of-service boundaries | Deferred | Define request/body limits, pagination maxima, timeouts, connection pools and upstream rate limits before external use. |

### Future cloud and cost controls

All rows are **Not applicable now** because no cloud architecture exists. They become **Blocked** at the cloud gate until evidenced:

- separate AWS accounts/environments, root-user protection, MFA, least-privilege roles and break-glass procedure;
- organization/account audit trail, configuration history, security findings and protected log retention;
- reviewed network and data boundaries, encryption keys, Secrets Manager lifecycle and RDS access;
- IaC review, deployment identity, immutable release provenance, drift detection and deletion protection;
- budgets, cost allocation, anomaly alerts, quota review and named billing contacts;
- retained-resource inventory and teardown procedure for databases, snapshots, logs, addresses and images.

Cloud security remains shared: AWS protects its cloud infrastructure while the owner remains responsible for application, identity, configuration and data controls.

### Verification, accessibility and public claims

| Item | Outcome | Limitation, owner and trigger/evidence |
|---|---|---|
| Unit/integration tests | Satisfied as repository evidence | Test sources exist across core flows; no pass claim is made for this audit. |
| Real-browser/E2E | Deferred | No browser automation suite was found. **Trigger:** before external usability; cover all three apps, printing, storage, reconnects and destructive flows. |
| Accessibility | Deferred | Semantic/accessibility features exist, but no automated/manual WCAG assessment is recorded. **Trigger:** before accessibility claims or external usability. |
| IP provenance/product name | Blocked before publication/commercial branding | Complete provenance and trademark/name clearance are not evidenced. |
| Security/compliance/recovery/availability claims | Blocked unless evidenced | Public statements must match checks run in the current release and the actual deployed environment. Roadmap controls must remain labelled planned. |
| Terms/privacy/contracts | Not applicable for local-only synthetic use | Becomes blocked before real, external, paid or supported use; obtain qualified advice. |
| Ownership/support/vulnerabilities/EOL | Deferred | No maintainer, disclosure channel, support boundary, update cadence or end-of-life process is documented. **Trigger:** before external users or collaborators. |

## Change triggers

| Change | Required reassessment |
|---|---|
| Real personal or workforce data | Privacy purpose/notice, minimization, access/correction/export, retention/deletion, incident notification, backups and access logging. |
| Another organization uses Counterline | Contracts, controller/processor roles, tenant isolation, support, data location, subprocessors and offboarding. |
| Public registration or multiple tenants | Tenant keys and authorization, anti-enumeration, abuse prevention, quotas, export/deletion and cross-tenant tests. |
| New countries or AWS Regions | Data transfer/location, notices, retention, tax/employment/payment rules and qualified advice. |
| Analytics, tracking or error vendor | Data map, consent/notice, SDK supply chain, redaction, retention, transfer and vendor contract. |
| Email, SMS or push | Consent/preference evidence, provider/security review, sender abuse, delivery logs, cost limits and opt-out. |
| Payments, accounting, payroll or banking | Regulatory/contract scope, segregation, reconciliation, fraud, immutable audit, secrets and provider responsibilities. |
| Automated decisions, OCR or generative AI | Data use/provenance, model/vendor terms, human review, error/appeal paths, security and cost controls. |
| New upload format | Parser threat model, type/size limits, isolation, scanning, quotas, retention and safe download. |
| Custom domain | DNS ownership, TLS, HSTS/CSP, cookie/session assumptions, monitoring and expiry procedures. |
| Public source publication | History/secret scan, licences, provenance, private-data review, security policy and repository rules. |
| Paid or contractually supported service | Terms, privacy, SLA/support/on-call, vulnerability handling, recovery objectives, billing and end-of-life. |

## Instructions for Codex and Claude Code

1. Read the applicable sections above before changing an affected area.
2. Implement only the authorized scope and preserve deliberate deferrals.
3. Do not incidentally add packages, cloud services, permissions, personal-data fields, telemetry, public endpoints or legal text.
4. Never state that a check passed unless it completed successfully in the current working tree. Distinguish focused checks from the full suite.
5. Report changed files, dependency and migration changes, compatibility effects, security/privacy effects, assumptions and deferred risks.
6. Do not turn a deferred or not-applicable item into “satisfied” without inspectable evidence and an identified reviewer/owner.
7. Never commit, push, deploy, modify external settings, rewrite history or perform cloud write operations without explicit authorization.

## Change-acceptance record

Complete this for material changes:

- **Purpose and authorized scope:**
- **Users and workflow affected:**
- **Trust boundaries changed:**
- **Misuse, fraud and failure considered:**
- **Data added, removed, retained or disclosed:**
- **Authentication/authorization effect:**
- **Dependencies, vendors, permissions and cost effect:**
- **Schema/API/browser compatibility:**
- **Release, rollback and migration plan:**
- **Checks run and evidence captured:**
- **Unresolved risks and explicit deferrals (owner/trigger):**
- **Acceptance decision, reviewer and date:**

## Primary references

Use these as verification frameworks, not as automatic compliance claims:

- [OWASP Application Security Verification Standard 5.0](https://owasp.org/projects/asvs) for web-application security requirements.
- [NIST SP 800-218 Secure Software Development Framework](https://csrc.nist.gov/pubs/sp/800/218/final) for secure-development practices and evidence.
- [W3C Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) for accessibility verification and truthful conformance claims.
- [Malaysia Personal Data Protection principles](https://www.pdp.gov.my/ppdpv1/en/principles-of-personal-data-protection/) and [data-subject rights](https://www.pdp.gov.my/ppdpv1/en/data-subject-rights/) as primary context where Malaysian law applies; confirm applicability and current obligations with qualified advice.
- [Personal Data Protection (Amendment) Act 2024](https://www.pdp.gov.my/ppdpv1/en/akta/personal-data-protection-amendment-act-2024/) for current statutory context; do not infer conclusions without advice.
- [PCI Security Standards Council merchant guidance](https://www.pcisecuritystandards.org/merchants/) before introducing payment-account data or a processing integration.
- [GitHub repository rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository) and [repository security features](https://docs.github.com/en/code-security/getting-started/quickstart-for-securing-your-repository) when repository settings are authorized.
- [AWS shared-responsibility guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/vulnerability-management/distribute-ownership.html) and [AWS Cost Anomaly Detection](https://docs.aws.amazon.com/cost-management/latest/userguide/getting-started-ad.html) when cloud work begins.
