# AegisTrace Product Brief

## Problem

Teams that connect a language model to internal tools get a chatbot that can take action. They do not get a system that can explain, constrain, pause, or prove what the agent did. When the action is a write — opening a support ticket, changing an account, sending a message — an unconstrained tool call is an authorization bug, not a feature.

AegisTrace is an internal support-agent control platform. It lets an operator ask a policy question, receive an answer grounded in synthetic support documents, and, when a ticket would help, pause for a human reviewer before anything is written.

## Who it is for

One workspace. Four roles, enforced on the server:

| Role | Job |
|---|---|
| Operator | Ask the support agent. See only their own runs. |
| Reviewer | Approve or reject proposed ticket creation. |
| Developer | Configure agents, prompts, tools, and knowledge. Inspect every run and trace. |
| Admin | Users, roles, workspace settings, policies, security configuration. |

## The only MVP workflow

An operator asks: "Why was my application rejected, and what should I do before reapplying?"

1. The control plane opens an `AgentRun` and snapshots the active agent version.
2. The runtime retrieves support documents. Retrieval is data, not authority.
3. The runtime writes an evidence-backed answer with citations, or abstains.
4. A planner may propose `create_support_ticket`.
5. The policy engine, which is ordinary deterministic code, returns `REQUIRE_APPROVAL` for that write.
6. The run pauses. A reviewer approves or rejects.
7. Rejection creates no ticket. The run completes with the grounded answer.
8. Approval resumes the same run. A worker creates one ticket, keyed by `run id + proposal id`.
9. A duplicate delivery returns the original ticket.
10. The timeline, audit trail, evaluation, and metrics remain queryable.

## Why RAG is required

The answer has to be checkable against the support corpus. A fluent reply that is not tied to a chunk is not a support answer. If the corpus does not contain enough evidence, the product abstains instead of filling the gap.

## Why ticket creation requires approval

`create_support_ticket` is a write. The model is allowed to suggest it. It is not allowed to authorize it. A reviewer sees the tool, the exact arguments, the risk, the requester, and the policy decision before any ticket row exists.

## What is out of scope

See `docs/non-goals.md`. No multi-agent mesh, no Kubernetes, no extra tools, no real customer data.

## What proves this is not a chatbot

A chatbot ends at a message. AegisTrace ends at a persisted run: version snapshot, retrieved chunk ids, policy decision, approval identity, idempotency key, tool result, evaluation, and audit events. The write either happened once, under an approval, or it did not happen.
