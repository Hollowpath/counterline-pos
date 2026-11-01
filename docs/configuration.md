# Configuration and first-run set-up

Environment variables and the one-time bootstrap of a fresh, non-local database. The local sample accounts are in the [README](../README.md#run-locally).

| Variable                                             | Default                   | Purpose                                       |
| ---------------------------------------------------- | ------------------------- | --------------------------------------------- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`               | local Compose database    | Database connection                           |
| `POS_SECURITY_PIN_PEPPER`, `POS_SECURITY_JWT_SECRET` | none (local profile only) | PIN hashing pepper and JWT signing key        |
| `POS_OUTLET_TIME_ZONE`                               | `Asia/Kuala_Lumpur`       | The one business time zone shared by all outlets: report days, receipts, promotion dates, display posters, timecard corrections |
| `POS_OUTLET_PHONE_COUNTRY_CODE`                      | `60`                      | Default country code for member phone numbers |
| `POS_BOOTSTRAP_ENABLED`                              | `false`                   | One-time first-run set-up (below)             |

## First-run set-up of a fresh database (outside the `local` profile)

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

If the credentials are held in AWS Secrets Manager, removing them from the current secret value does not erase the previous encrypted version, which Secrets Manager keeps until the secret is deleted. Use this workflow only with synthetic, temporary credentials; the AWS steps are in [infrastructure/README.md](../infrastructure/README.md).
