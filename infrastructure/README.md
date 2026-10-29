# Infrastructure

The AWS CDK (TypeScript) stack `Counterline` for a temporary learning deployment of Counterline POS. It is reproducible and designed for temporary, owner-run deployments: deploy for testing or demonstrations, then destroy. The repository provides no continuously hosted environment: deployments are external, owner-managed and created on demand, and these docs cannot assert live external state. It is written and run by the owner and is not production-ready. What it defines and how the pieces connect is in [docs/architecture.md](../docs/architecture.md#aws-deployment-temporary).

Placeholders below: `<profile>` is your AWS CLI profile, `<owner-ip>` your public IPv4 address, `<tag>` the image tag. Never commit real values, account details or credentials.

## Prerequisites

- Node.js and `npm ci` in this directory.
- AWS credentials for `<profile>` with permission to deploy, and the environment already prepared with `cdk bootstrap` (once per account and region).
- Docker, to build and push the application image.

## Everyday commands

```bash
npm run build        # type-check
npm test             # synthesis tests; no AWS access needed
npx cdk synth        # emit the CloudFormation template
npx cdk diff --profile <profile> --parameters AllowedIpv4Cidr=<owner-ip>/32
npx cdk deploy --profile <profile> --parameters AllowedIpv4Cidr=<owner-ip>/32
```

`AllowedIpv4Cidr` is the one address allowed to reach the load balancer, as a single `/32`. Read the `diff` before every `deploy`. If your address changes, deploy again with the new value.

## First deploy and first-run bootstrap

On a clean account the service cannot start until the immutable ECR image tag it references exists and the secret `counterline/application` holds the JSON keys the task reads (`pinPepper`, `jwtSecret`, plus the bootstrap values below). The stack creates the secret but does not set those keys. Use this order:

1. Temporarily set the ECS service `desiredCount` to `0` in `lib/infrastructure-stack.ts`, and deploy the stack.
2. Build the application image from the repository root and push it to the ECR repository `counterline-pos` with the exact tag the stack references (`ecs.ContainerImage.fromEcrRepository`). Tags are immutable, so a changed image needs a new tag, updated in the stack.
3. Populate `counterline/application` with generated values, never committed.
4. Configure first-run bootstrap: add the `POS_BOOTSTRAP_*` variables (contract in the README's first-run section) to the container definition, taking the credentials from `counterline/application` secret references rather than from the stack source, and set `POS_BOOTSTRAP_ENABLED` to `true`. The stack otherwise sets it to `false`. Set `desiredCount` back to `1` and deploy.
5. Verify access: the load balancer target is healthy and `/actuator/health` answers from `<owner-ip>`; dashboard sign-in as head office and as the manager; till set-up with the manager PIN; till sign-in.
6. Redeploy with `POS_BOOTSTRAP_ENABLED` set to `false` and every bootstrap secret reference removed from the container definition.
7. Update the current value of `counterline/application` so it contains only `pinPepper` and `jwtSecret`, then confirm a fresh task starts (for example by forcing a new deployment) and passes its health check.

Secrets Manager keeps the previous encrypted version of a secret until the secret itself is deleted, so the bootstrap credentials remain stored in the earlier version until the temporary stack is destroyed. Use this workflow only with synthetic, temporary credentials.

## Teardown and cost

Billable resources run while the stack exists, including when idle: the load balancer, the Fargate task, the database instance and storage, and the public IPv4 addresses. Deploy only for testing or demonstrations, and destroy the stack when an exercise is finished:

```bash
npx cdk destroy --profile <profile>
```

- The ECR repository has a retain policy: it and its images stay after `destroy`, with a small storage cost. For a complete teardown, delete it separately:

  ```bash
  aws ecr delete-repository --repository-name counterline-pos --force --profile <profile>
  ```

  The current stack creates the repository named `counterline-pos` itself and cannot automatically reuse a retained one, so redeploying this unchanged stack while the retained repository exists conflicts. Delete the retained repository before redeploying. Keeping it would first require changing the repository design in the stack, for example importing the existing repository.
- The database, its automated backups, the log group and the secrets are deleted with the stack, so teardown destroys the synthetic database and secrets. Deletion is irreversible: take any export you need first. CloudFormation deletes the secrets without a recovery window (it force-deletes them), but permanent deletion is asynchronous, so creating a secret with the same name straight away may need a short retry.
- Confirm in the AWS console that nothing is left running, and review billing the same day. Budget notifications, where configured, notify only; they do not cap or stop spending.
