# AWS deployment status and roadmap

**Runtime state.** The repository provides no continuously hosted environment. Deployments are external, owner-managed and created on demand; the repository describes what can be deployed, and these docs cannot assert live external state.

**What exists.** A GitHub Actions workflow (`.github/workflows/ci.yml`) builds and tests the backend and frontend. The owner-managed AWS CDK stack in `infrastructure/` (TypeScript) is reproducible and was designed for temporary, owner-run deployments. It defines:

- a private Amazon ECR repository for the application image (immutable tags, scan on push, the five most recent images kept, retained if the stack is deleted);
- a two-AZ VPC with public and isolated database subnets and no NAT gateway;
- an internet-facing Application Load Balancer serving HTTP only, reachable only from the owner's IPv4 address (a single `/32`, supplied as a stack parameter);
- one Fargate task with a public IP, receiving traffic from the load balancer on port 8080;
- an encrypted, single-AZ Amazon RDS for PostgreSQL 17 instance in the isolated subnets, not publicly accessible;
- AWS Secrets Manager secrets for the database credentials and the application's keys;
- CloudWatch application logs with seven-day retention.

**What this is not.** This is a temporary, owner-run deployment, not production-ready: HTTP only, one task, a single-AZ database with one day of backups, no alarms, no autoscaling, and resources that are deleted with the stack (except the ECR repository). There is no automated deployment pipeline: CI builds the image only for a local smoke test and never publishes it or touches AWS, and the owner pushes images and deploys the stack by hand. The operator steps are in [infrastructure/README.md](../infrastructure/README.md), and an end-to-end on-demand checklist (build, push, deploy, smoke checks, rollback, teardown, final sweep) is in [aws-release-checklist.md](aws-release-checklist.md).

**Deploy briefly, then destroy.** The load balancer, Fargate task, database and public IPv4 addresses are billed while they exist, even when idle, so deploy only for testing or demonstrations and tear the stack down afterwards with `cdk destroy`. Before any deployment, set up AWS budget notifications and a billing-review routine in the account: they are external to this repository, which neither creates nor checks them, and they notify only. Teardown deletes the synthetic database and the secrets. The ECR repository is retained by the stack and must be deleted separately for a complete teardown (see [infrastructure/README.md](../infrastructure/README.md#teardown-and-cost)).

**Planned, not built.** Region `ap-southeast-1`, one small environment. Each item is added only when it is needed:

- HTTPS on a domain the owner controls, before any access beyond the owner.
- Automated delivery from GitHub Actions (image publishing, then deployment using GitHub OIDC rather than stored AWS keys).
- A small set of CloudWatch alarms.
- Production availability and backups for the database.

Cost is a design constraint: the plan avoids services that bill while idle unless they are required. See [engineering-readiness.md](engineering-readiness.md) for what is deliberately not yet in place.
