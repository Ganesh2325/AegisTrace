# Knowledge and retrieval

AegisTrace answers support questions from a versioned document corpus. Retrieved text is evidence. It is not authority. Policy, tools, and approval stay in the control plane.

## Architecture

Workspace → Knowledge Base → Knowledge Base Version → Documents → Chunks → Embeddings → Retrieval → Citations → Run.

There is no separate `document_versions` table. A document row is an immutable ingested object identified by SHA-256. A knowledge base version is a snapshot of which documents belong to that corpus. Changing membership requires a new knowledge version.

## Knowledge bases

Seeded base: Support policies (`feature-hash-v1`, 384 dimensions). Fields: id, workspace, name, slug, embedding model, status, current version.

## Knowledge versions

Table `knowledge_base_versions`. Insert-only version numbers. At most one `current_version` per knowledge base (`knowledge_base_versions_one_current_idx`).

`POST /api/v1/knowledge-bases/{id}/versions` snapshots every `ACTIVE` document that already has embeddings. The new version is not current.

`POST /api/v1/knowledge-bases/{id}/versions/{versionId}/activate` makes it current after checking that every member document is `ACTIVE` and embedded. Incomplete corpora cannot be activated.

Uploads do not join a published version. They become eligible for the next publish.

Agent versions store `knowledge_base_version_id` and copy `knowledgeBaseVersionId` plus `knowledgeVersion` into the immutable snapshot. Historical runs keep that identifier.

## Document lifecycle

`UPLOADED` → `PROCESSING` → `ACTIVE` or `FAILED`. `DISABLED` exists in the schema for later deactivation. Historical membership rows are not deleted.

Duplicate checksums in the same knowledge base among `UPLOADED`, `PROCESSING`, or `ACTIVE` documents are rejected (`409 DUPLICATE_DOCUMENT`). A failed document may be replaced by a new upload.

## Storage

Original bytes go to the existing Garage/S3-compatible store under `documents/{workspaceId}/{documentId}`. Checksum and byte size are stored on the document. Object URLs are not returned to the console.

## Extraction and normalization

Markdown and text are read as UTF-8. PDFs use pypdf per page. Repeated spaces and extra blank lines are collapsed. Headings and list text are kept. Markdown and text do not invent page numbers. PDF pages keep the extractor’s page index.

Supported uploads: `.md`, `.txt`, `.pdf`, up to 10 MB. The backend checks PDF magic bytes (`%PDF`). Empty files and unsupported types are rejected.

## Chunking

Deterministic windows of 900 characters with 150 overlap, split on markdown headings when present. Metadata: document, sequence, section, page (PDF only), embedding model. Vectors are not shown in the UI.

## Embeddings

Model id `feature-hash-v1`, dimension 384. Batched in-process (no remote embedding API). Chunks store `embedding_model`. Mixing another model requires a new knowledge/document ingest later; this increment does not migrate vectors in place.

Document `ACTIVE` requires chunks with embeddings.

## Retrieval

PostgreSQL `pgvector` HNSW cosine plus lexical overlap, fused with reciprocal rank fusion. Higher fusion, vector cosine, and lexical scores are better. The product displays those numbers and does not convert them to percentages.

Default top-K is 5. Maximum 20. Query length maximum 2000 characters.

Filters: workspace id, knowledge base id, knowledge base version membership, embedding model. Membership is the corpus boundary, not “every ACTIVE document on the base.”

Support Run sends the run snapshot’s `knowledgeBaseVersionId` to `POST /v1/plan`. The inspector uses `POST /v1/retrieve` with the same loader.

## Citations and abstention

Citations come from the runtime. Each quote must appear in the retrieved chunk. If best lexical overlap is below 0.2, or no citeable sentences remain, the answer is: `I don't have enough documented evidence to answer that confidently.`

Instruction-like sentences inside documents are skipped when building the extractive answer.

## Prompt injection

Seed document `11-malicious-override.md` is labeled `PROMPT_INJECTION_FIXTURE` in development. It may rank as evidence. It cannot change tools, approval, or policy. User uploads are not auto-scored as malicious.

## APIs

| Method | Path | Roles |
| --- | --- | --- |
| GET | `/api/v1/knowledge-bases` | Operator, Developer, Admin |
| POST | `/api/v1/knowledge-bases` | Developer, Admin |
| GET | `/api/v1/knowledge-bases/{id}/documents` | Operator, Developer, Admin (paginated, default 20, max 50) |
| POST | `/api/v1/knowledge-bases/{id}/documents` | Developer, Admin (`202 UPLOADED`) |
| GET | `/api/v1/knowledge-bases/{id}/versions` | Operator, Developer, Admin |
| POST | `/api/v1/knowledge-bases/{id}/versions` | Developer, Admin |
| POST | `/api/v1/knowledge-bases/{id}/versions/{versionId}/activate` | Developer, Admin |
| POST | `/api/v1/knowledge-bases/{id}/retrieval` | Operator, Developer, Admin |
| GET | `/api/v1/documents/{id}` | Operator, Developer, Admin |
| GET | `/api/v1/documents/{id}/chunks` | Operator, Developer, Admin (max 50, no vectors) |

Capability `knowledge.manage` is Developer and Admin (upload, publish, activate). `knowledge.read` is Operator, Developer, Admin. Reviewer has neither.

## Console

`/knowledge` is the Knowledge Center (summary, documents, versions, retrieval inspector). `/knowledge/documents/{id}` shows metadata, extracted text, and chunks.

## Embedding model changes

A future model must not overwrite old vectors. Introduce a new knowledge version and re-embed. There is no migration engine in this increment.

## Performance notes

Retrieval is one SQL load of the version’s chunks plus in-process ranking. Document list uses count subqueries, not per-row round trips. Upload returns after enqueueing `EMBED_DOCUMENT`.

## Limitations

- Feature hashing is not a neural embedding.
- PDF support is text extraction only.
- No DOCX/HTML.
- No automatic object-storage garbage collection.
- No retry button (worker retries `EMBED_DOCUMENT` with existing job attempts).
