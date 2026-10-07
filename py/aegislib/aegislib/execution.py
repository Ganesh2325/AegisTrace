"""Idempotent ticket execution.

The unique keys are the safety property. The select-then-insert race is closed
by catching UniqueViolation and returning the row that won.
"""

from __future__ import annotations

import json
import uuid
from typing import Any


class ExecutionError(Exception):
    def __init__(self, error_type: str, message: str, retryable: bool):
        super().__init__(message)
        self.error_type = error_type
        self.retryable = retryable


def execution_key(run_id: str, proposal_id: str) -> str:
    return f"{run_id}:{proposal_id}"


def execute_ticket(conn, job: dict[str, Any], simulate: str | None = None) -> dict[str, Any]:
    payload = job["payload"]
    run_id = str(payload["runId"])
    proposal_id = str(payload["proposalId"])
    workspace_id = str(payload["workspaceId"])
    key = execution_key(run_id, proposal_id)
    arguments = payload["arguments"]

    with conn.cursor() as cur:
        # Serialize cancellation with the external side effect. A cancellation
        # that commits first makes this job skip; a committed ticket makes the
        # later cancellation report a conflict instead of hiding the side effect.
        cur.execute("select state from agent_runs where id = %s for update", (run_id,))
        run = cur.fetchone()
        if run is None:
            raise ExecutionError("INTERNAL", "Run does not exist.", False)
        if run[0] in {"CANCELLED", "TIMED_OUT", "FAILED", "COMPLETED"}:
            return {"status": "SKIPPED", "reason": run[0], "idempotencyKey": key}

        cur.execute(
            """
            select status, expires_at <= now() as expired, proposal_id::text, run_id::text
            from approvals where id = %s
            """,
            (payload["approvalId"],),
        )
        approval = cur.fetchone()
        if (
            approval is None
            or approval[0] != "APPROVED"
            or approval[1]
            or str(approval[2]) != proposal_id
            or str(approval[3]) != run_id
        ):
            _record_execution(
                cur,
                workspace_id,
                run_id,
                proposal_id,
                key,
                "SKIPPED",
                None,
                "APPROVAL_EXPIRED",
                "Approval is not an executable approval.",
            )
            raise ExecutionError("APPROVAL_EXPIRED", "Approval is not executable.", False)

        cur.execute(
            "select status, result from tool_executions where idempotency_key = %s",
            (key,),
        )
        existing = cur.fetchone()
        if existing and existing[0] == "SUCCEEDED":
            return existing[1]

        mode = simulate or payload.get("simulate")
        if mode == "crash_before_insert":
            raise ExecutionError("TICKET_500", "Simulated crash before insert.", True)
        if mode == "ticket_500":
            raise ExecutionError("TICKET_500", "Simulated ticket failure.", True)
        if mode == "ticket_400":
            raise ExecutionError("TICKET_400", "Simulated invalid ticket.", False)
        if mode == "ticket_timeout":
            raise ExecutionError("TIMEOUT", "Simulated ticket timeout.", True)

        ticket_id = str(uuid.uuid4())
        execution_id = str(uuid.uuid4())
        result = {
            "ticketId": ticket_id,
            "idempotencyKey": key,
            "priority": arguments["priority"],
            "subject": arguments["subject"],
        }
        try:
            cur.execute(
                """
                insert into tool_executions (
                    id, workspace_id, run_id, proposal_id, idempotency_key, tool_name,
                    status, result, started_at, ended_at
                ) values (%s, %s, %s, %s, %s, 'create_support_ticket', 'SUCCEEDED', %s::jsonb, now(), now())
                on conflict (idempotency_key) do nothing
                """,
                (execution_id, workspace_id, run_id, proposal_id, key, json.dumps(result)),
            )
            inserted_execution = cur.rowcount == 1
            cur.execute(
                """
                insert into tickets (
                    id, workspace_id, run_id, proposal_id, idempotency_key,
                    subject, description, priority, category, status
                ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'OPEN')
                on conflict (idempotency_key) do nothing
                """,
                (
                    ticket_id,
                    workspace_id,
                    run_id,
                    proposal_id,
                    key,
                    arguments["subject"],
                    arguments["description"],
                    arguments["priority"],
                    arguments["category"],
                ),
            )
        except Exception as exc:
            message = str(exc).lower()
            if "duplicate" in message or "unique" in message:
                conn.rollback()
                return _load_existing(conn, key)
            raise

        if not inserted_execution:
            cur.execute("select result from tool_executions where idempotency_key = %s", (key,))
            row = cur.fetchone()
            return row[0]
        if mode == "crash_after_insert":
            # The ticket row is committed by the caller only if we return.
            # Raising here after a successful insert simulates a crash before
            # callback. The caller must commit first when it wants that case.
            pass
        return result


def _load_existing(conn, key: str) -> dict:
    with conn.cursor() as cur:
        cur.execute("select result from tool_executions where idempotency_key = %s", (key,))
        row = cur.fetchone()
        if row is None:
            raise ExecutionError("INTERNAL", "Idempotent execution row missing.", True)
        return row[0]


def _record_execution(cur, workspace_id, run_id, proposal_id, key, status, result, error_type, error_message):
    cur.execute(
        """
        insert into tool_executions (
            id, workspace_id, run_id, proposal_id, idempotency_key, tool_name,
            status, result, error_type, error_message, started_at, ended_at
        ) values (%s, %s, %s, %s, %s, 'create_support_ticket', %s, %s::jsonb, %s, %s, now(), now())
        on conflict (idempotency_key) do nothing
        """,
        (
            str(uuid.uuid4()),
            workspace_id,
            run_id,
            proposal_id,
            key,
            status,
            json.dumps(result) if result else None,
            error_type,
            error_message,
        ),
    )
