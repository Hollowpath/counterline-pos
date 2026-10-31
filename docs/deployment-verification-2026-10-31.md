# Deployment verification, 2026-10-31

A dated record of one temporary AWS deployment of commit `9cc8d2e813e9` and the checks run against it. The environment was destroyed afterwards; nothing described here is running. This is evidence about that single run, not a claim about production readiness, availability or security. See [engineering-readiness.md](engineering-readiness.md).

## Scope

- **Source:** commit `9cc8d2e813e9`, image tag `9cc8d2e813e9`, region `ap-southeast-1`.
- **Method:** the first-deployment path in [aws-release-checklist.md](aws-release-checklist.md) (section 5a), run by hand by the owner: paused stack, image push, secret write, first-run bootstrap, then bootstrap off.
- **Conditions:** HTTP only, reachable only from the owner's single `/32` address, one Fargate task, synthetic data and disposable logins. No URL, address, account identifier or credential is recorded here.

## What was observed after deployment

Read-only checks with a separate read-only AWS profile, made while the stack was up:

- CloudFormation: `CREATE_COMPLETE` after the first deploy, `UPDATE_COMPLETE` after the final deploy.
- ECR: the image tagged `9cc8d2e813e9` was present.
- ECS: 1 of 1 tasks running; the load balancer target was `healthy`; `/actuator/health` returned HTTP 200 with `"status":"UP"`.
- CloudWatch: the application log group had log streams (contents were not read).
- After the bootstrap-off deploy, head-office sign-in still worked.

The step that trims the secret to `pinPepper` and `jwtSecret` is an owner action that the read-only profile cannot verify, so it is not asserted here.

## Browser acceptance run

A one-off Playwright script drove the live application in Chromium at 1280x800. It is not part of this repository. The final script had 21 steps and passed in strict mode (stop at the first failure) on its last two runs. Each step asserts what the user interface shows:

1. Health endpoint reports `UP`.
2. Head office signs in to the dashboard.
3. Head office adds a catalogue folder and an item.
4. A manager signs in to the dashboard.
5. A till is set up with the manager PIN.
6. The manager signs in at the till.
7. The register is opened.
8. A cash sale of the new item shows a "Sale complete" confirmation.
9. Head office adds a cashier.
10. Head office adds two discounts: 10% on the order, and RM1.00 that needs a manager PIN.
11. The cashier signs in at the till.
12. The cashier records a cash sale.
13. The cashier refunds an order with a manager PIN and the order shows as refunded.
14. The till's sales report opens.
15. The 10% discount takes the total from RM12.50 to RM11.25 and the sale completes.
16. The approval discount gives RM11.50; the till asks for a manager PIN when the cashier goes to pay, then the sale completes.
17. A member is added at the till and attached to the sale.
18. The cashier clocks in and out.
19. A customer display pairs with the till using its access code.
20. Deactivating the cashier removes their ability to sign in at the till.
21. Head office logs out and the dashboard session ends.

## Findings

- **Application behaviour confirmed.** A clocked-in staff member cannot be deactivated until their shift is closed. A discount that needs approval is applied at once, and the manager PIN is requested when the cashier goes to pay.
- **Checklist gap.** The ECR credential helper could not read a session created with `aws login`, and the push failed with `no basic auth credentials`. The checklist now describes the workaround.

## Teardown

The owner destroyed the stack and deleted the retained ECR repository. A read-only sweep afterwards found no stack, ECR repository, ECS cluster, Secrets Manager secret, load balancer, database, VPC or Elastic IP for Counterline, and the application address no longer answered. One empty CloudWatch log group created by a CDK helper function remained; deleting it is a manual step (checklist section 9). Billing was not recorded here.

## What this does not show

- No negative cases: wrong passwords or PINs, and blocked or unauthorised actions, were not tried.
- The script checked interface outcomes only. It did not read the database, receipts, or the staff audit table, and it did not compare report figures with the sales, refunds and discounts made.
- One browser, one outlet, cash payments only, desktop width only. No phone width, concurrency, throttling, or manager-made shift corrections.
- A single manual run from one address over plain HTTP. It says nothing about performance, availability, cost or security.
