# API Boundary

Base path: `/api/v1`. Errors:

```json
{
  "code": "TOOL_POLICY_DENIED",
  "message": "Tool execution denied by policy.",
  "traceId": "…",
  "runId": "…",
  "timestamp": "2026-10-04T00:00:00Z"
}
```

Clients never receive stack traces, SQL, or secret values.

Authentication: `POST /api/v1/auth/login` sets an HttpOnly cookie. Browser mutations must send an allowed `Origin`. Service calls to `/internal/**` use `X-Internal-Token` and are not a user session.

Workspace: `X-Workspace-Id` header. If it is omitted, the caller's sole membership is used. If they have several, the header is required.

## Operator and shared read

| Method | Path | Roles |
|---|---|---|
| POST | `/api/v1/auth/login` | public |
| POST | `/api/v1/auth/logout` | any member |
| GET | `/api/v1/auth/me` | any member |
| POST | `/api/v1/runs` | operator, admin |
| GET | `/api/v1/runs` | operator (own), developer, reviewer, admin |
| GET | `/api/v1/runs/{id}` | same visibility rule |
| GET | `/api/v1/runs/{id}/events` | SSE, same visibility |
| POST | `/api/v1/runs/{id}/cancel` | owning operator, admin |
| GET | `/api/v1/approvals` | reviewer, admin |
| POST | `/api/v1/approvals/{id}/approve` | reviewer or admin; admin-only if the decision required admin |
| POST | `/api/v1/approvals/{id}/reject` | same |

## Configuration

| Method | Path | Roles |
|---|---|---|
| GET, POST | `/api/v1/agents` | developer, admin |
| POST | `/api/v1/agents/{id}/versions` | developer, admin |
| POST | `/api/v1/agents/{id}/status` | developer, admin |
| GET | `/api/v1/tools` | developer, admin |
| GET, POST | `/api/v1/knowledge-bases` | developer, admin |
| POST | `/api/v1/knowledge-bases/{id}/documents` | developer, admin |
| GET | `/api/v1/knowledge-bases/{id}/documents` | developer, admin, and operator read of active titles |

## Admin

| Method | Path | Roles |
|---|---|---|
| GET, POST | `/api/v1/admin/users` | admin |
| POST | `/api/v1/admin/memberships` | admin |
| GET, PATCH | `/api/v1/admin/settings` | admin |
| GET | `/api/v1/audit` | admin, developer (read) |
| GET | `/api/v1/metrics/summary` | any member, scoped |
| GET | `/api/v1/evaluations` | developer, admin |
| POST | `/api/v1/admin/failure-simulations` | admin, non-production only |
| GET | `/api/v1/admin/jobs` | admin |

## Health

`GET /liveness`, `GET /readiness`, `GET /health` on every service. Readiness is OK when that service can reach the dependencies it needs to be correct. For the control plane, Postgres is required. Redis degraded does not fail readiness.

## Internal

| Method | Path | Caller |
|---|---|---|
| POST | `/internal/runs/{id}/tool-result` | worker |
| GET | `/internal/runs/{id}` | worker, runtime if needed |

## Runtime

`POST /v1/plan` on the agent runtime. Called only by the control plane with the internal token. Body is the snapshot and the question. Response is answer, citations, usage, and an optional proposal. The runtime does not receive a database role that can insert tickets.

## Realtime events

SSE event names: `RUN_STARTED`, `RETRIEVAL_STARTED`, `RETRIEVAL_COMPLETED`, `MODEL_STARTED`, `MODEL_COMPLETED`, `TOOL_PROPOSED`, `POLICY_DECIDED`, `APPROVAL_REQUIRED`, `APPROVAL_APPROVED`, `APPROVAL_REJECTED`, `TOOL_STARTED`, `TOOL_COMPLETED`, `RUN_COMPLETED`, `RUN_FAILED`, `RUN_CANCELLED`, `RUN_TIMED_OUT`.

Each event has a monotonic id. Reconnecting clients send `Last-Event-ID`. The server replays from Postgres.
