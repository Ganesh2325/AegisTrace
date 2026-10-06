# Technical debt

Recorded so it can be removed on purpose.

## Metrics and presentation

- Duration percentiles are end-to-end and include approval wait. The operations card labels that and formats the duration. It does not split out model time, because that interval is not stored.
- The summary loads the workspace’s run rows and computes `percentile_cont` in Java. That is one scan per request, which is acceptable at the current volume and is the tested algorithm.
- Cost for an unknown model is still stored as zero on the run. The summary refuses to display that zero as a price.

## Frontend

- Pages duplicate fetch, error, and empty handling. There is no shared table, badge, or status map.
- Status is raw enum text (`ACTIVE`, `COMPLETED`) with color only for a few error and success strings.
- `Shell` calls `/api/v1/auth/me` on every layout mount, so a full navigation flashes “Checking session…”.
- Run detail polls the run and the timeline every 2.5 seconds and also opens an `EventSource`.
- Approvals poll every 3 seconds and send a fixed reason string (`Reviewed` or `Declined`). The reviewer cannot type a reason.
- Knowledge reads only `bases[0]`.
- Admin settings are a JSON dump. The settings form is not editable in the UI even though `PATCH /api/v1/admin/settings` exists.
- Tailwind tokens live in `tailwind.config.js` (`ink`, `panel`, `line`, `mist`, `paper`, `amber`, `moss`, `rose`, `sky`). Components also use raw `text-rose` and `bg-white/10`.
- The Operations cards now show a one-line context and a `?` tooltip. They are still the same twelve-card grid.

## Backend

- `ALLOW` on a write tool completes the run without enqueueing execution. Only `REQUIRE_APPROVAL` creates the ticket job. That is current behavior, and it is easy to misread as “policy allowed the write, so it happened.”
- Run timeout maintenance excludes `APPROVAL_REQUIRED` so a reviewer is not cancelled at 60 seconds. Other states still use `timeout_at`.
- `RunEventBus.send` swallows every exception so a disconnected browser cannot roll back the transaction. Events can still be published before the surrounding transaction commits.
- Spring Boot logs a generated in-memory security password. The API uses the JWT filter, not that user.
- Seeded documents use `ON CONFLICT (id) DO NOTHING`, so a document left in `FAILED` is not repaired on the next boot.
- Failure-simulation jobs are inserted only when the dev flag is on. The admin UI audited here does not expose them.

## Runtime

- Embeddings are feature hashes. Paraphrases that do not share tokens with the corpus abstain or miss.
- The worker and the control plane use one database role.
- Per-run evaluation is `heuristic-v1`, not a labeled human set. The Evaluation page says so. The pass rule is `min(scores) >= 0.5` and `policy == 1`.

## Delivery

- Compose publishes alternate host ports because this PC already uses 5432, 6379, and 9000. Containers still talk on the Compose network.
- `infrastructure/aws/main.tf` is incomplete and unapplied.
- CI does not run the Compose stack.
- Local `change-me-*` values are committed as documented dev defaults in Compose and `.env.example`.
