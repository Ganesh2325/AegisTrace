# Explicit Non-Goals

These are excluded on purpose for the MVP. They are not unfinished accidents.

- Kubernetes, service meshes, and multi-region active-active.
- A fleet of specialized agents. One support agent is the product.
- MCP servers. An MCP tool would still have to pass the same policy, approval, audit, and budget checks. That integration waits until the single-agent path is stable.
- Autonomous production writes. The write tool cannot execute inside the model loop.
- More tools than `search_knowledge` and `create_support_ticket`.
- Real customer data. Documents, tickets, and users are synthetic.
- Fine-tuning. Retrieval plus policy is the control mechanism.
- Invented latency, accuracy, cost, or uptime numbers. Measurements come from the benchmark and evaluation scripts or they are not published.
- Fake traces, fake approvals, or a dashboard that renders hardcoded metrics.
- Using the model as the source of permission, approval, or budget decisions.
- Letting retrieved document text change tool permissions, system policy, or approval requirements.

Later stages (hybrid retrieval beyond the current rank fusion, model routing, MCP, multi-agent, replay, anomaly detection) are listed in `docs/roadmap.md` and are not part of the completion bar for this repository.
