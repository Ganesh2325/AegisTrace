# Navigation and authorization

The shell hides links a role cannot use. The control plane still rejects the API call. A hidden link is not a security control.

Roles come from `memberships.role` and are returned by `GET /api/v1/auth/me`. The workspace name on that payload is the `workspaces.name` row for the membership. The environment is `AEGIS_ENVIRONMENT` (`dev` in local Compose). The UI labels `dev` as LOCAL. It does not label a local process as production.

There is one seeded workspace, `Support`. The shell shows that name and does not switch workspaces. The API still chooses the workspace: the single membership, or `X-Workspace-Id` when the caller has more than one. The frontend does not send a workspace switch.

## Capabilities

These names mirror existing `Rbac.require` role lists. They are not a second policy engine.

| Capability | Backend | Roles |
| --- | --- | --- |
| `overview.read` | `GET /api/v1/metrics/summary` | Operator, Reviewer, Developer, Admin |
| `runs.read` | `GET /api/v1/runs` and `/runs/{id}` | Operator, Reviewer, Developer, Admin |
| `runs.create` | `POST /api/v1/runs` | Operator, Admin |
| `approvals.read` | `GET /api/v1/approvals` | Reviewer, Admin |
| `agents.read` | `GET /api/v1/agents` and agent detail/version reads | Operator, Reviewer, Developer, Admin |
| `agents.configure` | Create agent, create version, activate version, change agent status, list tools | Developer, Admin |
| `knowledge.read` | `GET /api/v1/knowledge-bases` | Operator, Developer, Admin |
| `evaluation.read` | `GET /api/v1/evaluations` | Developer, Admin |
| `observability.read` | `GET /api/v1/metrics/summary` | Operator, Reviewer, Developer, Admin |
| `audit.read` | `GET /api/v1/audit` | Developer, Admin |
| `admin.console` | Admin page. User and settings APIs are Admin only. `POST /api/v1/admin/policy-check` is Developer and Admin | Developer, Admin |

`runs.cancel` stays Operator and Admin on the API. The shell does not add a separate nav item for it.

Agent status changes, version creation, and version activation stay Developer and Admin. The Agents page is visible to every role because the list API allows every role. Configure actions are hidden unless `agents.configure` is present; the API still returns 403.

## Navigation

Defined once in `frontend/lib/access.ts`.

| Group | Item | Route | Capability |
| --- | --- | --- | --- |
| Workspace | Overview | `/` | `overview.read` |
| Workspace | Support run | `/runs/new` | `runs.create` |
| Workspace | Approvals | `/approvals` | `approvals.read` |
| Build | Agents | `/agents` | `agents.read` |
| Build | Knowledge | `/knowledge` | `knowledge.read` |
| Intelligence | Evaluation | `/evaluations` | `evaluation.read` |
| Intelligence | Observability | `/observability` | `observability.read` |
| Governance | Audit | `/audit` | `audit.read` |
| System | Administration | `/admin` | `admin.console` |

Empty groups are omitted. `/runs/{id}` highlights Support run when that item is visible, because there is no runs-list route.

Operator sees Overview, Support run, Agents, Knowledge, Observability.

Reviewer sees Overview, Approvals, Agents, Observability.

Developer sees Overview, Agents, Knowledge, Evaluation, Observability, Audit, Administration.

Admin sees every item above.

Why this exists stays at `/why` and is linked from the user menu, not the main groups.

## Direct URLs

`decideRoute` checks the path before the page component renders, so a forbidden page does not call its API.

| Path | Required capability |
| --- | --- |
| `/` | `overview.read` |
| `/runs/new` | `runs.create` |
| `/runs/{id}` | `runs.read` |
| `/approvals` | `approvals.read` |
| `/agents` | `agents.read` |
| `/knowledge` | `knowledge.read` |
| `/evaluations` | `evaluation.read` |
| `/observability` | `observability.read` |
| `/audit` | `audit.read` |
| `/admin` | `admin.console` |

A forbidden URL shows “Access restricted”, the capability name, and a link back to Overview. An unknown path stays a not-found page.

Developer can open Administration because the policy dry run on that page is authorized. The user list and settings calls remain Admin-only and still return 403.

## Shell

Session is read once in the shell and kept for client navigations. A later `/me` refresh replaces it. Logout clears that cache, hides the console, and returns to `/login`.

The top bar has Jump to (`Ctrl` or `Cmd`+`K`), the environment label, a notifications control that says “No notifications”, and the user menu (name, role, workspace, logout). There is no notification feed and no approval badge, because no count API is wired into the shell.

The command palette lists only visible navigation items plus Why this exists. Arrow keys move, Enter opens, Escape closes.

Desktop keeps a persistent sidebar, with an optional collapsed icon rail stored in `localStorage` under `aegis.nav.collapsed`. Below the `md` breakpoint the sidebar is a drawer: backdrop click, Escape, and route change close it.

Breadcrumbs appear on `/runs/{id}` only. When the viewer can start a run, the parent link is Support run. Otherwise the crumb is “Run”.

## Planned, not routed

Runs list, Tools and policies, Security, and Settings are later phases. They are not in the menu.
