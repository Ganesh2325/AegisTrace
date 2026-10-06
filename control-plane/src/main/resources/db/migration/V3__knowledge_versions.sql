ALTER TABLE documents ADD COLUMN IF NOT EXISTS byte_size BIGINT NOT NULL DEFAULT 0;

CREATE TABLE knowledge_base_versions (
    id                  UUID PRIMARY KEY,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id),
    version_number      INT NOT NULL CHECK (version_number > 0),
    current_version     BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          UUID REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (knowledge_base_id, version_number)
);
CREATE UNIQUE INDEX knowledge_base_versions_one_current_idx
    ON knowledge_base_versions (knowledge_base_id) WHERE current_version;

CREATE TABLE knowledge_version_documents (
    knowledge_base_version_id UUID NOT NULL REFERENCES knowledge_base_versions(id),
    document_id               UUID NOT NULL REFERENCES documents(id),
    PRIMARY KEY (knowledge_base_version_id, document_id)
);
CREATE INDEX knowledge_version_documents_document_idx
    ON knowledge_version_documents (document_id);

ALTER TABLE agent_versions
    ADD COLUMN knowledge_base_version_id UUID REFERENCES knowledge_base_versions(id);
ALTER TABLE agent_runs
    ADD COLUMN knowledge_base_version_id UUID REFERENCES knowledge_base_versions(id);

INSERT INTO knowledge_base_versions (id, knowledge_base_id, version_number, current_version, created_at)
SELECT gen_random_uuid(), id, 1, TRUE, created_at
FROM knowledge_bases kb
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_base_versions v WHERE v.knowledge_base_id = kb.id
);

INSERT INTO knowledge_version_documents (knowledge_base_version_id, document_id)
SELECT v.id, d.id
FROM knowledge_base_versions v
JOIN documents d ON d.knowledge_base_id = v.knowledge_base_id AND d.status = 'ACTIVE'
ON CONFLICT DO NOTHING;

UPDATE agent_versions av
SET knowledge_base_version_id = kv.id
FROM knowledge_base_versions kv
WHERE kv.knowledge_base_id = av.knowledge_base_id
  AND kv.current_version
  AND av.knowledge_base_version_id IS NULL;

UPDATE agent_runs ar
SET knowledge_base_version_id = av.knowledge_base_version_id
FROM agent_versions av
WHERE av.id = ar.agent_version_id
  AND ar.knowledge_base_version_id IS NULL;

UPDATE agent_versions
SET snapshot = jsonb_set(snapshot, '{knowledgeBaseVersionId}', to_jsonb(knowledge_base_version_id::text), true)
WHERE knowledge_base_version_id IS NOT NULL
  AND (snapshot ->> 'knowledgeBaseVersionId') IS NULL;

UPDATE agent_runs
SET snapshot = jsonb_set(snapshot, '{knowledgeBaseVersionId}', to_jsonb(knowledge_base_version_id::text), true)
WHERE knowledge_base_version_id IS NOT NULL
  AND (snapshot ->> 'knowledgeBaseVersionId') IS NULL;

CREATE INDEX agent_versions_kb_version_idx ON agent_versions (knowledge_base_version_id);
CREATE INDEX agent_runs_kb_version_idx ON agent_runs (knowledge_base_version_id);
CREATE INDEX documents_kb_checksum_idx ON documents (knowledge_base_id, checksum_sha256);
