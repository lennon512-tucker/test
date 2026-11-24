# Gemini API notes (checked 2026-11-24)
Docs (ai.google.dev now 301-redirects to aistudio.google.com):
- https://ai.google.dev/api/generate-content -> https://aistudio.google.com/api-reference/generate-content
- https://ai.google.dev/api/models -> https://aistudio.google.com/api-reference/models
- https://ai.google.dev/gemini-api/docs/models -> https://aistudio.google.com/docs/models
- https://ai.google.dev/gemini-api/docs/changelog -> https://aistudio.google.com/docs/changelog

generateContent: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
Auth: header `x-goog-api-key` (the fetched reference excerpt did not state it; the header is the documented API-key header and models.list already works with it).
Response: candidates[].content.parts[] with text and optional boolean `thought`; candidates[].finishReason (STOP, MAX_TOKENS, SAFETY...); usageMetadata (promptTokenCount, candidatesTokenCount, thoughtsTokenCount, totalTokenCount); modelVersion; responseId; promptFeedback.blockReason.
models.list: GET /v1beta/models, Model.name = "models/{id}", supportedGenerationMethods[]. Pagination (pageSize, pageToken query params; nextPageToken in response) was not shown in the fetched excerpt; the runner follows nextPageToken when present (standard Google list pagination).

Model choice evidence: docs models page lists gemini-3.1-pro-preview as the only current Pro text model (gemini-2.5-pro deprecated, gemini-3-pro-preview shut down). Changelog: gemini-3.1-pro-preview released 2026-02-19 (preview). Gemini 4 models (Neon/Carbon/Argon/Hydrogen) are not named Pro.
