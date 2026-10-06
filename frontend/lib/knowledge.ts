export type KnowledgeBase = {
  id: string;
  name: string;
  slug: string;
  embeddingModel: string;
  status: string;
  currentVersionId: string | null;
  currentVersion: number | null;
  documentCount: number;
  readyCount: number;
  processingCount: number;
  failedCount: number;
};

export type KnowledgeDocument = {
  id: string;
  title: string;
  mediaType: string;
  status: string;
  errorMessage?: string | null;
  createdAt: string;
  byteSize: number;
  chunks: number;
  embeddings: number;
  embeddingStatus: string;
  testArtifact?: string | null;
  checksumSha256?: string;
  knowledgeBaseId?: string;
  knowledgeName?: string;
  embeddingModel?: string;
  extractedText?: string;
};

export type KnowledgeVersion = {
  id: string;
  version: number;
  current: boolean;
  createdAt: string;
  documentCount: number;
  readyCount: number;
};

export type DocumentPage = {
  items: KnowledgeDocument[];
  total: number;
  page: number;
  size: number;
};

export type ChunkRow = {
  id: string;
  sequence: number;
  section: string;
  page: number | null;
  text: string;
  embeddingModel: string;
  embeddingStatus: string;
  characters: number;
};

export type RetrievalHit = {
  documentId: string;
  documentTitle: string;
  chunkId: string;
  section: string;
  page: number | null;
  quote: string;
  fusionScore: number;
  vectorScore: number;
  lexicalScore: number;
  score: number;
};

export type RetrievalPreview = {
  query: string;
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  knowledgeBaseVersionId: string;
  knowledgeVersion: number;
  topK: number;
  similaritySemantics: string;
  latencyMs: number;
  hits: RetrievalHit[];
};

export const ALLOWED_UPLOAD = ".md,.txt,.pdf";
export const MAX_UPLOAD_LABEL = "10 MB";
export const DEFAULT_DOC_PAGE = 20;

export function fileKind(mediaType: string | undefined): string {
  if (mediaType === "application/pdf") return "PDF";
  if (mediaType === "text/markdown") return "Markdown";
  if (mediaType === "text/plain") return "Text";
  return mediaType || "Unknown";
}

export function formatBytes(value: number | null | undefined): string {
  if (value == null || value <= 0) return "Unknown size";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

export function scoreLabel(score: number | null | undefined): string {
  if (score == null || Number.isNaN(score)) return "Unavailable";
  return score.toFixed(4);
}

export function knowledgeVersionLabel(version: number | null | undefined, status?: string): string {
  if (version != null) return `v${version}`;
  return status || "No version";
}
