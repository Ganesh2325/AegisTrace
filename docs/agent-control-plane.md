# Agent Control Plane

The control plane is the configuration and governance layer for agents. A support run executes against an immutable agent version snapshot. Activating a later version does not rewrite historical runs.

## Agent model

Table `agents`. Workspace-scoped. Fields used by the product: `id`, `name`, `description`, `status` (`ACTIVE` or `INACTIVE`), `workspace_id`, `created_by`, `created_at`. There is no `updated_at` column. The API reports `updatedAt` as the latest `agent_versions.created_at`, or the agent `created_at` when no versions exist.

`ACTIVE` / `INACTIVE` means the agent record is enabled. It is not an operational health signal.

## Agent Version model

Table `agent_versions`. Insert-only rows. Columns: version number, provider, model, temperature, max tokens, timeout, max tool calls, cost budget, prompt version, knowledge base, environment, `current_version`, JSON `snapshot`, created by / at.

Version state in the database is a boolean: `current_version`. The unique partial index `agent_versions_one_current_idx` allows at most one current version per agent. Zero current versions is allowed. The product does not invent `DRAFT` or `RETIRED` labels. The UI shows **Current** or **Not current**.

Creating a version inserts `current_version = false`. Activation is a separate call.

## Prompt versions

Table `prompt_versions`. Insert-only. Creating an agent version either reuses an existing `promptVersionId` or inserts a new prompt version (`PROMPT_VERSION_CREATED`). GET responses include prompt version id and number. They do not include `systemPrompt`. Runtime still reads the prompt from the run snapshot copied at start.

## Model configuration

Persisted on the version row and inside `snapshot`: `provider`, `model`, `temperature`, `maxTokens`, `timeoutMs`, `tokenBudget`, `costBudgetUsd`. Secrets are not stored on the version. The product does not hard-code a single model name. Multi-provider routing is not implemented.

## Tools

`agent_version_tools` joins registered `tools`. Seeded tools: `search_knowledge` (READ_ONLY) and `create_support_ticket` (WRITE, approval required). Version GET includes name, classification, risk, `approvalRequired`, and `enabled: true` for assigned tools. There is no per-version disabled flag in the schema.

## Policies

There is no separate policy-assignment table on an agent version. Policy association is the registered tool metadata (`classification`, `approval_required`). The policy engine remains authoritative at run time. Frontend configuration cannot override it.

## Knowledge references

`knowledge_base_id` and `knowledge_base_version_id` are stored on the agent version and copied into the snapshot as `knowledgeBaseId`, `knowledgeBaseVersionId`, and `knowledgeVersion`. The API reports `knowledgeName` and a numeric `knowledgeVersion` with `knowledgeVersionStatus: PINNED`. See `docs/knowledge-rag.md`.

## Limits

Enforced from the snapshot at run time: `maxToolCalls`, `timeoutMs`, `tokenBudget`, `costBudgetUsd`. There is no request-budget column. The UI only exposes limits the runtime already reads.

## Environment

`agent_versions.environment` stores the value written at create time (seed uses `AEGIS_ENVIRONMENT`). The control plane displays that stored value. It does not synthesize DEV / STAGING / PRODUCTION configs.

## Activation

`POST /api/v1/agents/{id}/versions/{versionId}/activate` (Developer, Admin). The transaction locks the agent row (`FOR UPDATE`), clears `current_version` on that agent, then sets the chosen version current. Audit action: `AGENT_VERSION_ACTIVATED`. Existing runs are not updated.

## Deactivation

Agent record status can be set `INACTIVE`. There is no API to clear the current version without activating another. Activating version B replaces version A as current.

## Immutability

There is no PATCH for agent versions or prompt versions. Used versions stay historically addressable through `agent_runs.agent_version_id` and `agent_runs.snapshot`.

## Snapshot behavior

`RunService.create` copies `agent_versions.snapshot` onto `agent_runs.snapshot` and stores `agent_version_id` and `prompt_version_id`. Future runs use whichever version is `current_version` on an `ACTIVE` agent. Old runs keep their copied snapshot even if the agent later moves to another version.

GET run payloads expose `agentVersionId` (and execution also exposes `agentVersionNumber`). Missing historical fields are not reconstructed from the live agent.

## Authorization

| Action | Roles |
| --- | --- |
| List / get agent, list / get versions | Operator, Reviewer, Developer, Admin |
| Create agent, create version, activate, change status, list tools | Developer, Admin |
| Agent-scoped audit trail | Developer, Admin (`audit.read`) |

Frontend capability `agents.configure` hides mutate actions. Backend `Rbac.require` is the security control.

## Workspace isolation

Every query includes `workspace_id`. A miss returns `NOT_FOUND` rather than leaking existence. `X-Workspace-Id` for a workspace the caller does not belong to is `FORBIDDEN`.

## Audit events

| Action | When |
| --- | --- |
| `AGENT_CREATED` | Agent insert |
| `PROMPT_VERSION_CREATED` | New prompt row (not when reusing) |
| `AGENT_VERSION_CREATED` | Version insert (`current: false`) |
| `AGENT_VERSION_ACTIVATED` | Explicit activation |
| `AGENT_STATUS_CHANGED` | Agent ACTIVE / INACTIVE |

The Agents page lists the latest 20 matching events. The Audit Center remains a later phase.

## Concurrency

Activation serializes on `SELECT … FOR UPDATE` of the agent row. The unique current-version index rejects two current rows. Concurrent activations complete one after the other; the last committed activation is current.

## APIs

| Method | Path |
| --- | --- |
| GET | `/api/v1/agents` |
| GET | `/api/v1/agents/{id}` |
| POST | `/api/v1/agents` |
| GET | `/api/v1/agents/{id}/versions` |
| GET | `/api/v1/agents/{id}/versions/{versionId}` |
| POST | `/api/v1/agents/{id}/versions` |
| POST | `/api/v1/agents/{id}/versions/{versionId}/activate` |
| POST | `/api/v1/agents/{id}/status` |
| GET | `/api/v1/agents/{id}/audit` |
| GET | `/api/v1/tools` |

List stays a JSON array so Support Run agent selection keeps working. Extra projection fields (`toolCount`, `knowledgeName`, `runCount`) are additive.

## Future integration

Approval UX, the audit center, and evaluation grouping can consume the tool flags and version identifiers already stored on versions and runs. Those surfaces are not implemented here.

## Deferred

- Version diff UI (data exists on rows; no dedicated diff API)
- Knowledge version identifiers
- Version RETIRED state
- Per-environment configuration matrix
- Prompt body display in the console
