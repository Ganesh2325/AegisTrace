# Route inventory

Only routes that exist. Planned destinations are listed at the end and are not linked.

| Route | Page | Purpose | Required capability | Navigation | Backend authorization | Status |
| --- | --- | --- | --- | --- | --- | --- |
| `/login` | Sign in | Development sign-in | Public | None | `POST /api/v1/auth/login` | EXISTS |
| `/` | Operations | Command center | `overview.read` | Overview, all roles | `GET /api/v1/operations/overview`: Operator, Reviewer, Developer, Admin | EXISTS |
| `/runs/new` | Support run | Start a run and watch execution | `runs.create` | Support run: Operator, Admin | `POST /api/v1/runs`: Operator, Admin. `GET /api/v1/runs/{id}/execution` and SSE: all four roles, with visibility rules | EXISTS |
| `/runs/{id}` | Run detail | One run, timeline, cancel | `runs.read` | Highlights Support run when that item is visible | `GET` run, timeline, events: all four roles. `POST` cancel: Operator, Admin | EXISTS |
| `/approvals` | Approval queue | Pending decisions | `approvals.read` | Reviewer, Admin | `GET` and decide: Reviewer, Admin | EXISTS |
| `/agents` | Agents | Agent list and status | `agents.read` | All four roles | `GET /api/v1/agents`: all four roles. Status change: Developer, Admin | EXISTS |
| `/knowledge` | Knowledge | Knowledge Center, upload, retrieval inspector | `knowledge.read` | Operator, Developer, Admin | Reads: Operator, Developer, Admin. Writes: Developer, Admin | EXISTS |
| `/knowledge/documents/{id}` | Document | Extracted text and chunks | `knowledge.read` | Nested under Knowledge | `GET /api/v1/documents/{id}` | EXISTS |
| `/evaluations` | Evaluation | Heuristic-v1 rows | `evaluation.read` | Developer, Admin | `GET /api/v1/evaluations`: Developer, Admin | EXISTS |
| `/observability` | Observability | Summary JSON and external trace links | `observability.read` | All four roles | `GET /api/v1/metrics/summary`: all four roles | EXISTS |
| `/audit` | Audit | Append-only events | `audit.read` | Developer, Admin | `GET /api/v1/audit`: Developer, Admin | EXISTS |
| `/admin` | Administration | Membership, settings, policy dry run | `admin.console` | Developer, Admin | Users, settings, jobs: Admin. Policy dry run: Developer, Admin | EXISTS |
| `/why` | Why this exists | Product note | None | User menu, every signed-in role | No API | EXISTS |

Unknown console paths render the not-found state inside the shell. A known path without the capability renders the forbidden state and does not mount the page.

## Not implemented

| Name | Why it is absent |
| --- | --- |
| Runs list | No `/runs` page. Run history is not a separate screen yet. |
| Tools and policies | Tool list and policy dry run exist as APIs. There is no tools page. The dry run remains on Administration. |
| Security | No security-center page. |
| Settings | Workspace settings are read on the Admin API. There is no settings route. |
| Workspace switcher | The shell shows the current workspace name. It does not change `X-Workspace-Id`. |
