# Security policy

Counterline POS is not a supported production service. The repository holds only synthetic data, no deployment is continuously hosted (see [AWS deployment status](docs/aws-deployment.md)), and there is no service-level or support commitment. Responsible reports are still welcome.

## Supported version

Only the current `main` branch is considered. There are no other maintained versions.

## Reporting a vulnerability

Please report suspected vulnerabilities privately:

1. Use GitHub's private vulnerability reporting: open the repository's **Security** tab and choose **Report a vulnerability**.
2. If that option is not available, open a minimal public issue that only asks for a private contact channel. Do not include vulnerability details, proof-of-concept code, credentials, or any personal data in the issue.

Please do not describe a vulnerability in a public issue, pull request, or discussion before it has been addressed.

A useful report names the affected component, how to reproduce the problem, and its likely impact. Do not access, modify, or retain data that is not yours, and do not run denial-of-service, spam, or automated high-volume tests against any deployment you do not own.

## Out of scope

- The sample accounts, the `pos`/`pos` database credentials in `docker-compose.yml`, and the `local` profile's PIN pepper and JWT key are public by design and for localhost use only (see the [README](README.md#run-locally)).
- Dependency advisories already assessed in [engineering-readiness.md](docs/engineering-readiness.md#accepted-dependency-advisories).

## What to expect

This is a personal project, so responses are made on a best-effort basis with no guaranteed timeline. Valid reports are fixed on `main` where practical.
