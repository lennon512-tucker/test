# Draft question to the client: model and API (2026-11-24)

Not sent. The user sends it.

---

Hello,

A question on section 4 of the brief before I make the official run.

The brief asks for "the latest Gemini Pro model available through the Gemini API". Today that is `gemini-3.1-pro-preview` (I checked the API's model list and Google's models page). My API key is on the Free Tier, where this model has a quota of 0, so every request is refused (HTTP 429, RESOURCE_EXHAUSTED).

Which of these would you like?

1. I enable billing on my own key and run `gemini-3.1-pro-preview` through the Gemini API with default settings, exactly as the brief says. (This is my plan unless you say otherwise.)
2. You provide a key, or a project with billing, that I should use for the run.
3. I use the newer Gemini 4 model `gemini-4-carbon` instead. It is not named "Pro", and I can reach it only through a third-party OpenAI-compatible gateway, not Google's API. The gateway sometimes adds its own system text to the request, and it does not report an exact model version. I would document all of this in summary.json and the README.

Thank you.
