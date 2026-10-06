UPDATE document_chunks c
SET page_number = NULL
FROM documents d
WHERE d.id = c.document_id
  AND d.media_type <> 'application/pdf';
