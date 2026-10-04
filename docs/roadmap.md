# Roadmap

Only after the MVP workflow, tests, and demo are stable.

| Stage | Idea | Why it is not in the MVP |
|---|---|---|
| A | Learned embeddings as the default, keep hybrid fusion | The offline hasher is enough to prove the pipeline. Swapping the embedder is a knowledge-base reindex, not a new product. |
| B | Model routing by budget | One configured provider is enough to show the boundary. |
| C | Human-labeled RAG evaluation | The current checks are automatic and conservative. They are not a substitute for a labeled set. |
| D | MCP tools behind the same policy engine | MCP must not be a side door. |
| E | Multiple agents | No evaluation yet shows that a supervisor would answer the support question better. |
| F | Policy simulation on historical proposals | Useful once there is a history worth replaying. |
| G | Agent replay | Depends on immutable snapshots, which already exist. |
| H | Version comparison in the UI | Evaluation rows already store version ids. |
| I | Anomaly detection on denial rate and cost | Needs a baseline from real traffic. |
| J | Cross-workspace analytics | The MVP isolates workspaces. Analytics across them is a different privacy decision. |
