# Recorded Gemini answers

Real answers of `POST /v1beta/interactions`, recorded on 2026-10-12 with the model
`gemini-3.5-flash-lite` and the prompt and schema of prompt version 1. The tests serve them
from a local HTTP server, so the provider is tested against what the API really returns and
not against an assumption about it.

| File | Request | HTTP status |
|---|---|---|
| `completed.json` | two small files | 200 |
| `incomplete.json` | the same request with `max_output_tokens` of 20 | 200 |
| `error-invalid-key.json` | a key that does not exist | 400 |
| `error-model-not-found.json` | a model that does not exist | 404 |
| `error-invalid-request.json` | an unknown parameter in the body | 400 |

The bodies are unchanged except for the `signature` of the thought step, which is replaced
by a placeholder. No API key appears in them. An answer to a rate limit, a server error or a
blocked generation could not be produced on purpose; the tests for those use bodies written
after the documentation.
