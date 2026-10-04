# Data Retention

Defaults, changeable by an admin in workspace settings. A daily job applies them. This is not a claim that a specific customer's legal hold was reviewed.

| Data | Default | After the window |
|---|---|---|
| Audit events | 2 years | Delete row |
| Run timeline events | 180 days | Delete row |
| Agent run question and final answer | 180 days | Replace text with `[redacted]` |
| Evaluation scores | 1 year | Delete row |
| Original documents and embeddings | Until a developer deletes or disables the document | Object delete and chunk delete |
| Tool arguments on proposals | 180 days | Replace JSON with `{}` and `redacted: true` |
| Tool outputs and ticket body | 180 days | Redact body, keep id and status |
| Job attempt error messages | 90 days | Truncate to the error class |

Ids, states, policy decisions, and token counts stay so metrics and audit structure still make sense after content is gone.

Content logging in application logs is off unless `AEGIS_CONTENT_LOGGING=true`. Traces do not contain prompts, retrieved passages, tool arguments, or tool output.
