# CI/CD

## Pull request and main checks

`.github/workflows/ci.yml` requests only `contents: read`. On every push and pull request it runs:

- control-plane tests
- Python tests and the critical evaluation suite
- frontend typecheck, tests, and production build
- secret scan and the release-configuration contract
- production npm audit
- Python service-package audit, ignoring findings that apply only to the audit environment's `pip`
- Terraform init and validate
- image build and critical vulnerability scan for the frontend, control plane, runtime, and worker

A critical image finding fails the workflow. The accepted Tailwind `braces` finding is build-time tooling. The production frontend image is the standalone server, and the production npm audit is the dependency gate.

## Deployment

`.github/workflows/deploy.yml` runs only through a manual dispatch. The job is bound to the selected GitHub environment, so a reviewer rule on that environment can stop a production run. The workflow asks for a short-lived OIDC role. It does not contain an access key. Pull requests do not deploy.

The workflow cannot succeed until the environment defines `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `ECS_CLUSTER`, and `SMOKE_URL`, and until the AWS role exists. Those values are not configured. No deployment has run.

## Branch protection

Required checks and a protected main branch are repository settings. They are not changed from this workspace. The checks to require are the jobs in `ci.yml`.
