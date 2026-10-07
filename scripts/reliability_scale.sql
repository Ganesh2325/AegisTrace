\timing on
\pset pager off

BEGIN;
SET LOCAL statement_timeout = '60s';

CREATE TEMP TABLE reliability_runs AS
SELECT gen_random_uuid() AS id, n
FROM generate_series(1, 5000) AS n;

INSERT INTO agent_runs (
    id, workspace_id, user_id, agent_id, agent_version_id, prompt_version_id,
    knowledge_base_id, knowledge_base_version_id, request_id, trace_id, span_id,
    state, question, final_response, citations, input_tokens, output_tokens,
    estimated_cost_usd, provider, model, snapshot, event_seq,
    orchestration_attempts, started_at, ended_at, timeout_at, created_at
)
SELECT f.id, s.workspace_id, s.user_id, s.agent_id, s.agent_version_id, s.prompt_version_id,
       s.knowledge_base_id, s.knowledge_base_version_id, 'reliability-scale:' || f.n,
       md5(f.n::text), substr(md5((f.n + 1)::text), 1, 16),
       CASE WHEN f.n % 10 = 0 THEN 'FAILED' ELSE 'COMPLETED' END,
       'Synthetic reliability query ' || f.n, 'Synthetic bounded answer', '[]'::jsonb,
       100 + f.n % 500, 50 + f.n % 100, 0, s.provider, s.model, s.snapshot, 10, 0,
       now() - interval '1 hour', now() - interval '1 hour' + ((f.n % 1000) || ' milliseconds')::interval,
       now() + interval '1 hour', now() - ((f.n % 43200) || ' seconds')::interval
FROM reliability_runs f
CROSS JOIN LATERAL (SELECT * FROM agent_runs ORDER BY created_at LIMIT 1) s;

INSERT INTO run_events (id, run_id, sequence, event_type, state, payload, created_at)
SELECT gen_random_uuid(), r.id, e.sequence, 'SCALE_EVENT', 'COMPLETED',
       jsonb_build_object('fixture', true, 'sequence', e.sequence), now()
FROM reliability_runs r
CROSS JOIN generate_series(1, 10) AS e(sequence);

INSERT INTO audit_events (
    id, workspace_id, actor_id, action, resource_type, resource_id,
    trace_id, run_id, request_id, metadata, created_at
)
SELECT gen_random_uuid(), s.workspace_id, s.user_id, 'RELIABILITY_SCALE_READ',
       'agent_run', r.id::text, md5(r.n::text), r.id, 'scale:' || r.n,
       '{"fixture":true}'::jsonb, now() - ((r.n % 604800) || ' seconds')::interval
FROM reliability_runs r
JOIN agent_runs s ON s.id = r.id;

CREATE TEMP TABLE reliability_documents AS
SELECT gen_random_uuid() AS id, n
FROM generate_series(1, 500) AS n;

INSERT INTO documents (
    id, workspace_id, knowledge_base_id, title, media_type, storage_key,
    checksum_sha256, byte_size, status, created_by, created_at
)
SELECT d.id, seed.workspace_id, seed.knowledge_base_id, 'Reliability document ' || d.n,
       'text/plain', 'reliability-scale/' || d.n, md5('reliability-scale-' || d.n),
       2048, 'ACTIVE', seed.created_by, now()
FROM reliability_documents d
CROSS JOIN LATERAL (SELECT * FROM documents ORDER BY created_at LIMIT 1) seed;

INSERT INTO document_chunks (
    id, document_id, chunk_index, section, page_number, content, embedding, embedding_model
)
SELECT gen_random_uuid(), d.id, c.n, 'Reliability', NULL,
       seed.content || ' fixture ' || d.n || ' chunk ' || c.n,
       seed.embedding, seed.embedding_model
FROM reliability_documents d
CROSS JOIN generate_series(0, 9) AS c(n)
CROSS JOIN LATERAL (SELECT * FROM document_chunks ORDER BY id LIMIT 1) seed;

INSERT INTO knowledge_version_documents (knowledge_base_version_id, document_id)
SELECT seed.knowledge_base_version_id, d.id
FROM reliability_documents d
CROSS JOIN LATERAL (
    SELECT knowledge_base_version_id FROM knowledge_version_documents LIMIT 1
) seed;

ANALYZE agent_runs;
ANALYZE run_events;
ANALYZE audit_events;
ANALYZE documents;
ANALYZE document_chunks;

\echo 'Run list plan: 5,000 synthetic runs'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, state, created_at
FROM agent_runs
WHERE workspace_id = (SELECT workspace_id FROM agent_runs LIMIT 1)
ORDER BY created_at DESC
LIMIT 20;

\echo 'Run event replay plan: 50,000 synthetic events'
EXPLAIN (ANALYZE, BUFFERS)
SELECT sequence, event_type, state, payload, created_at
FROM run_events
WHERE run_id = (SELECT id FROM reliability_runs LIMIT 1)
ORDER BY sequence DESC
LIMIT 200;

\echo 'Audit list plan: 5,000 synthetic rows'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, action, created_at
FROM audit_events
WHERE workspace_id = (SELECT workspace_id FROM agent_runs LIMIT 1)
  AND created_at >= now() - interval '7 days'
ORDER BY created_at DESC
LIMIT 25;

\echo 'Bounded hybrid retrieval candidate plan: 5,000 synthetic chunks'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM document_chunks
ORDER BY embedding <=> (SELECT embedding FROM document_chunks LIMIT 1)
LIMIT 100;

ROLLBACK;
