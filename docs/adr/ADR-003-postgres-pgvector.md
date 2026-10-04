# ADR-003: PostgreSQL and pgvector

## Status

Accepted

## Context

The product needs foreign keys, unique idempotency keys, and similarity search. A separate vector database would add a second write path for chunks and a second failure mode for the smallest corpus this system will ever have.

## Decision

PostgreSQL 16 with pgvector stores application data and chunk embeddings. Hybrid retrieval fuses vector rank with full-text rank in the application, using two queries against the same database.

The default embedding is feature hashing into 384 dimensions (`feature-hash-v1`). It needs no network and is deterministic in tests. It is not a neural embedding. A knowledge base may instead be pinned to an OpenAI-compatible embedding of the same width. Chunks are filtered by embedding model so the two are never mixed.

## Consequences

Feature hashing will miss paraphrases that a trained model would catch. The synthetic corpus uses the same vocabulary as the demo questions so the MVP workflow is testable offline. Replacing the embedder is a reindex, not a schema change, as long as the width stays 384.
