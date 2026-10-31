# On-demand AWS release checklist

A checklist for a temporary, owner-run deployment of the CDK stack, from a clean checkout to teardown. It is a runbook, not a record: it holds no results, and what is deployed at any moment is external state this repository cannot show. The explanations live elsewhere: [infrastructure/README.md](../infrastructure/README.md) (first-deploy order, bootstrap, teardown details), [README.md](../README.md#aws-deployment-status-and-roadmap) (status and limits) and [architecture.md](architecture.md#aws-deployment-temporary) (what the stack defines).

Commands are for Git Bash. Placeholders in angle brackets are yours to fill; never commit real values, account details, URLs, addresses or credentials.

| Placeholder | Meaning |
| --- | --- |
| `<profile>` | your AWS CLI profile |
| `<region>` | the AWS region you deploy to |
| `<owner-ip>` | your public IPv4 address; the stack accepts one `/32` |
| `<registry-host>` | the registry host part of the ECR repository URI (stack output `RepositoryUri`, before the `/`) |
| `<repository-uri>` | the full `RepositoryUri` stack output (ends in `counterline-pos`) |
| `<tag>` | the immutable image tag (the Git commit, below) |
| `<application-url>` | the `ApplicationUrl` stack output |

> **Shell variables do not persist between terminals.** `export` lines apply only to the terminal they are typed in. A new terminal, or a new IDE or editor session, starts without them: set them again before continuing.

Legend: **[LOCAL]** touches nothing outside your machine. **[AWS]** changes AWS and is done by the owner. **[AWS read-only]** only reads. **[CLEANUP]** removes resources.

## 1. Prerequisites [LOCAL]

- [ ] JDK 21, Node.js, Docker (running), AWS CLI, and Git (see the main README).
- [ ] `cdk bootstrap` has been run once for the account and region (infrastructure/README.md, Prerequisites).
- [ ] AWS budget notifications and a billing-review routine are set up in the account. They are external to this repository, which neither creates nor checks them; they notify only and do not cap spending.
- [ ] A clean working tree on the commit you intend to ship, so the tag below names exactly what was built:

```bash
git status --porcelain      # expect no output
git log -1 --oneline
```

## 2. Build and test [LOCAL]

```bash
(cd backend && ./mvnw -B verify)                     # needs Docker
(cd frontend && npm ci && npm run lint && npm test && npm run build)
(cd infrastructure && npm ci && npm run build && npm test)
```

## 3. Image build and local smoke test [LOCAL]

Set the tag from the commit and build the image:

```bash
export TAG=$(git rev-parse --short=12 HEAD)
echo "$TAG"
docker build -t counterline-pos:"$TAG" .
```

Smoke test the exact image just built. Compose runs `counterline-pos:local`, so tag the image as that and start Compose without rebuilding. The app uses its own database in an isolated, persistent Compose volume (not the one used for development), the `local` profile and port 8090:

```bash
docker tag counterline-pos:"$TAG" counterline-pos:local
docker compose --profile app up --no-build -d
curl -s http://localhost:8090/actuator/health        # expect "status":"UP"
# open http://localhost:8090/dashboard.html and sign in with a local sample account (README)
docker compose --profile app down
```

The volume persists across runs. Do not add `-v` to `down`: it would also remove the development database volume.

## 4. Registry access [LOCAL setup]

Docker's ECR credential helper is the primary way to sign in to the registry: it fetches a short-lived token on demand and keeps the large ECR token out of the OS credential store, which can reject it on Windows.

1. Install the Amazon ECR credential helper so `docker-credential-ecr-login` is on your `PATH`.
2. Back up `~/.docker/config.json`, then add a `credHelpers` entry for your registry host. Leave any existing `credsStore` as it is:

```json
{ "credHelpers": { "<registry-host>": "ecr-login" } }
```

3. In every terminal that runs `docker push`, point the helper at your profile (the helper reads these; shell variables are per terminal):

```bash
export AWS_PROFILE=<profile> AWS_REGION=<region> AWS_SDK_LOAD_CONFIG=true
```

If the profile was signed in with `aws login` (a browser sign-in), the helper may not be able to read that session, and the push fails with `no basic auth credentials`. Export the session to the terminal that runs `docker push`, then push again. These are temporary credentials; unset them afterwards and set `AWS_PROFILE` again for later commands:

```bash
unset AWS_PROFILE
eval "$(aws configure export-credentials --profile <profile> --format env)"
export AWS_REGION=<region>
# ... docker push ...
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
```

Fallback only, if the helper cannot be used: `aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin <registry-host>`. On Windows the credential store may reject that token (`The stub received bad data`).

## 5. Deploy [AWS]

The image tag is set in `infrastructure/lib/infrastructure-stack.ts` (`ContainerImage.fromEcrRepository`), and tags are immutable: a changed image needs a new commit and a new tag. Choose the section that matches the account.

### 5a. First deployment on a clean account

The repository does not exist until the stack creates it, and the service cannot start without an image and a populated secret, so deploy in this order (details: infrastructure/README.md, "First deploy and first-run bootstrap"):

1. In the stack source set the image tag to `<tag>` and the ECS service `desiredCount` to `0`. Then:

```bash
cd infrastructure
npm run build && npm test
npx cdk diff   --profile <profile> --parameters AllowedIpv4Cidr=<owner-ip>/32
npx cdk deploy --profile <profile> --parameters AllowedIpv4Cidr=<owner-ip>/32
```

2. Read `RepositoryUri` from the stack outputs (the deploy prints it) and push the image built in step 3:

```bash
export AWS_PROFILE=<profile> AWS_REGION=<region> AWS_SDK_LOAD_CONFIG=true TAG=<tag>
docker tag counterline-pos:"$TAG" <repository-uri>:"$TAG"
docker push <repository-uri>:"$TAG"
```

3. Populate the secret `counterline/application` with generated values (`pinPepper`, `jwtSecret` and the `POS_BOOTSTRAP_*` values the README's first-run section requires). Write the JSON to a file **outside the repository**, never commit it, use only synthetic temporary values, and delete the file afterwards:

```bash
aws secretsmanager put-secret-value --secret-id counterline/application \
  --secret-string file://<secret-json-outside-repo> --profile <profile> --region <region>
```

4. Add the `POS_BOOTSTRAP_*` container variables from secret references and set `POS_BOOTSTRAP_ENABLED` to `true`, set `desiredCount` back to `1`, and deploy again with the same `cdk diff` / `cdk deploy` commands.
5. Run the smoke checks (step 6), then finish the bootstrap clean-up from infrastructure/README.md steps 6 and 7 (bootstrap off, secret trimmed to `pinPepper` and `jwtSecret`, fresh task healthy).

### 5b. Normal release to an existing stack

The repository and secret already exist and `desiredCount` stays `1`. Push first, then point the stack at the new tag:

```bash
export AWS_PROFILE=<profile> AWS_REGION=<region> AWS_SDK_LOAD_CONFIG=true TAG=<tag>
docker tag counterline-pos:"$TAG" <repository-uri>:"$TAG"
docker push <repository-uri>:"$TAG"

# set the image tag in infrastructure/lib/infrastructure-stack.ts to <tag>, then:
cd infrastructure
npm run build && npm test
npx cdk diff   --profile <profile> --parameters AllowedIpv4Cidr=<owner-ip>/32
npx cdk deploy --profile <profile> --parameters AllowedIpv4Cidr=<owner-ip>/32
```

Read the diff before every deploy. Bootstrap stays off in a normal release.

## 6. Smoke checks [AWS; can change application data]

From `<owner-ip>`, the only address the load balancer accepts. The health request only reads; signing in, setting up a till and testing at the till can change application data, so use only synthetic values:

```bash
curl -s <application-url>/actuator/health            # expect "status":"UP"
```

Then, in a browser: `<application-url>/dashboard.html` signs in as head office and as the manager; set up a till with the manager PIN and sign in at the till. Also confirm the load balancer target is healthy and the application logs reach CloudWatch (docs/testing.md lists the manual checks). Record nothing here; observe it.

## 7. Roll back to the previous image [AWS]

Tags are immutable and the repository keeps the five most recent images, so the previous tag is available unless it has aged out.

1. In `infrastructure/lib/infrastructure-stack.ts`, set the image tag back to the previous `<tag>`.
2. Run `npx cdk diff` and `npx cdk deploy` as in step 5b, with `--parameters AllowedIpv4Cidr=<owner-ip>/32`. The service's circuit breaker also rolls back a deployment whose tasks fail their health check.
3. Repeat the smoke checks. A database migration that already ran is not undone by an older image: check the new schema is compatible before rolling back.

## 8. Teardown [CLEANUP]

Billable resources run while the stack exists (load balancer, Fargate task, database, public IPv4 addresses), so destroy it when the exercise is finished. This permanently deletes the synthetic database and secrets.

```bash
cd infrastructure
npx cdk destroy --profile <profile>
```

## 9. Retained resources [CLEANUP]

The ECR repository is retained by the stack. For a complete teardown, delete it separately; otherwise redeploying the unchanged stack conflicts with the retained repository:

```bash
aws ecr delete-repository --repository-name counterline-pos --force --profile <profile> --region <region>
```

Skip this only if you are deliberately keeping the images and have first changed the stack's repository design.

## 10. Final sweep [AWS read-only]

Confirm nothing billable remains, then review billing the same day (budget notifications only notify; they do not stop spending). Read-only calls, each with `--profile <profile> --region <region>`:

```bash
aws cloudformation list-stacks --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE ROLLBACK_COMPLETE DELETE_FAILED
aws elbv2 describe-load-balancers
aws ecs list-clusters
aws rds describe-db-instances
aws rds describe-db-snapshots --snapshot-type manual
aws ec2 describe-addresses
aws ec2 describe-nat-gateways
aws secretsmanager list-secrets
aws logs describe-log-groups
aws ecr describe-repositories
```

Anything still listed from this stack is to be cleaned up deliberately; the AWS console's billing pages are the authority on cost.
