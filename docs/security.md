# Security

This document describes the controls enforced by the local AegisTrace architecture. It is not a production certification or a claim that every future endpoint is safe. Server-side checks are authoritative; hidden navigation is only a usability control.

## Authentication

IMPLEMENTED. Login checks a stored password hash with BCrypt. The session is an HttpOnly, SameSite=Lax cookie named `aegis_session`. `Secure` is off for local HTTP and on when `AEGIS_COOKIE_SECURE=true`. The cookie lifetime is eight hours. Logout clears it. The frontend does not store the session token in `localStorage`.

Login failures return the same credential message for an unknown account, a disabled account, and a wrong password. Ten failures for an email within ten minutes return HTTP 429. A request without a session returns HTTP 401. Session identifiers are not written to audit metadata.

ENVIRONMENT-DEPENDENT. Local development seeds four accounts when `AEGIS_SEED_ENABLED=true`. The production profile disables seeding and failure simulation, and refuses default secrets when seeding would otherwise run.

## Authorization and workspace isolation

IMPLEMENTED. `Rbac.require` loads the role from the authenticated user's membership. A role supplied by the browser, a header, or a request body is not accepted. An unknown role fails closed in both object visibility and run-list SQL.

`X-Workspace-Id` is accepted only when it matches one of the caller's memberships. A foreign workspace returns HTTP 403. A valid identifier from another workspace returns HTTP 404 for run visibility, so the response does not distinguish a hidden object from a missing one inside the caller's workspace boundary.

Operator, Reviewer, Developer, and Admin capabilities remain those documented in `docs/navigation-and-authorization.md`. Direct API calls are covered by the same role lists as the UI.

## Object authorization

IMPLEMENTED. Run, approval, audit, trace, evaluation, and document lookups include the caller's workspace and then apply role visibility. A UUID by itself is not authorization.

Operators see only their own runs. Reviewers see runs that have an approval. Developers and admins see runs in their workspace. Proposal arguments are omitted for operators.

## Approval and tool execution

IMPLEMENTED. The persisted proposal is the execution input. Approval does not accept a replacement tool name, argument set, workspace, or run. Pending, rejected, expired, and cancelled proposals are not executable. Same-decision approval replay is idempotent; the opposite decision conflicts. Ticket insertion uses a unique idempotency key. The worker callback ignores a terminal run.

Self-approval protection and the policy allowlist remain in the existing approval and policy code. Development failure simulation is disabled when the environment is production.

## Evaluation and prompt injection

IMPLEMENTED. Creating or starting evaluations requires Developer or Admin. Execution records the selected agent and knowledge versions. Historical result rows are not overwritten after completion. Duplicate start requests return the original execution. Cancellation locks the execution and result rows before child work is cancelled.

Retrieved text is untrusted data. It cannot approve a tool, change a role, select a workspace, or bypass the policy engine. The model is not a security authority.

## Uploads and object storage

IMPLEMENTED. Uploads accept only bounded text, Markdown, and PDF content. PDF bytes must begin with the PDF signature. Text containing a null byte is rejected. The object key is `documents/{workspace}/{knowledge-base}/{sha256}` and never the caller-supplied filename. A filename used as a fallback title keeps only its final path segment, strips control characters, and is limited to 200 characters. Explicit titles are also control-character stripped and length limited; React renders them as text.

The Garage bucket is private to the configured access key. The application reads objects with those credentials. There is no public object URL and no caller-supplied storage key.

LIMITATION. An interrupted database transaction can leave an unreferenced object under the checksum key. It is not publicly readable.

## SSRF, injection, XSS, CSRF, CORS, and headers

IMPLEMENTED. Runtime, Jaeger, Prometheus, and object-storage URLs come from server configuration. Tool arguments do not contain a URL that the worker fetches. SQL uses bound parameters. Visibility and window fragments are selected from fixed server-side strings. There is no shell execution of user input. The UI does not use `dangerouslySetInnerHTML`.

Cookie-authenticated mutations require an allowlisted `Origin`. A missing or unexpected origin returns HTTP 403. Spring CSRF tokens are not used because the application is stateless and the origin gate is the request-integrity control. CORS does not use `Access-Control-Allow-Origin: *` with credentials. The browser uses the Next.js same-origin `/backend` proxy.

API responses set `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, a restrictive `Permissions-Policy`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'`, and `Cache-Control: no-store`. Strict Transport Security is added only when the session cookie is marked Secure.

The Next.js application sets the same baseline headers. Its content security policy allows `'self'` plus `'unsafe-inline'` for scripts and styles because the current Next.js production runtime emits inline bootstrap and style content. `object-src` is `none`, and framing is denied.

## Secrets, telemetry, and audit

IMPLEMENTED. Real credentials are not committed. Compose and examples use local `change-me` values. Public errors do not include stack traces, password hashes, or session tokens. Audit events are insert-only through the application. Ordinary roles cannot edit or delete them.

Prometheus labels remain low-cardinality. Trace attributes continue to exclude prompts, secrets, document bodies, and tool arguments.

ENVIRONMENT-DEPENDENT. `/actuator/prometheus` is unauthenticated so the local Prometheus scraper can read it. Its output is operational metrics, not product content. API documentation is available only when the environment is not production. Binding these development ports to the host is a local Compose choice.

DEFERRED. Network policies that remove host access to Prometheus, Jaeger, PostgreSQL, Redis, and object storage. A separate database role with only the privileges required by each service. Production secret management and image signing. These belong to the cloud and production deployment work, not this local control set.

## Dependencies and containers

IMPLEMENTED. The direct PostCSS package and PostCSS selector parser are pinned to patched releases. `npm audit --omit=dev` reports zero vulnerabilities. A `pip-audit` of the runtime and worker dependency set (FastAPI, Uvicorn, httpx, psycopg, pypdf, boto3, and OpenTelemetry) reported no vulnerabilities in those packages. The only finding was the audit environment's own `pip` 25.0.1, which the services do not import.

LIMITATION. A full `npm audit` still reports five high findings in development-only Tailwind 3 dependencies, all rooted in `braces` 3.0.3 (`GHSA-vfj7-8cjw-p6xm`, stack exhaustion in nested patterns). No patched `braces` release exists. That code is not on the production request path. Upgrading to Tailwind 4.3.3 would be a major styling migration and is not applied. Accepted risk: build-time only.

Application images run as non-root users and drop all Linux capabilities with `no-new-privileges`. Third-party PostgreSQL, Redis, Garage, Jaeger, Prometheus, Grafana, and collector images keep their upstream users because changing them is a deployment concern.

## Rate and resource limits

IMPLEMENTED. Login attempts, support-run creation, document uploads (20 per user per minute), and retrieval (120 per user per minute) are bounded. Uploads are limited to 10 MB. Retrieval queries, result counts, trace spans, event histories, worker concurrency, and the run executor queue retain their existing limits. Excess requests fail with HTTP 429 or the existing validation and queue errors. They are not reported as success.

LIMITATION. The new upload and retrieval limits are in-memory for each control-plane process. A shared limiter for multiple instances is deferred to cloud deployment.
