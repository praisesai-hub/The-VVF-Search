# OpenAI API integration

## Status

The repository contains a **safe CI connectivity integration** for the OpenAI API. The API credential is consumed only through the GitHub Actions secret `OPENAI_API_KEY`; it is never committed to the repository and must never be packaged into the Android APK.

The manual connectivity workflow is `.github/workflows/openai-api-smoke.yml`.

## Security boundary

`The-VVF-Search` is an Android application. A project API key embedded in an APK can be extracted and abused. The Android process therefore must not call OpenAI directly with the project secret.

Production topology:

```text
Android app
   |
   | explicit user-opted-in request
   v
VVF AI Gateway / backend
   |
   | server-side OPENAI_API_KEY
   v
OpenAI API
```

The existing local/on-device semantic search remains the default path. Cloud OpenAI is optional and must not replace local indexing or local duplicate detection.

## Data minimization

A cloud AI request must be explicitly initiated by the user, disabled by default, limited to the minimum required data, and must exclude vault contents, secrets, authentication tokens, and arbitrary private files unless a future feature obtains separate user confirmation. Requests must be cancellable and time-bounded and the UI must clearly identify network/cloud processing.

## Credential handling

- GitHub Actions: `OPENAI_API_KEY` repository/environment secret.
- Production gateway: platform secret manager/environment secret.
- Android: never store the project API key in `BuildConfig`, resources, assets, Gradle properties, source code, or the APK.
- Logs: never log authorization headers, API keys, private request bodies, or complete sensitive responses.
- Rotation must not require an Android release.

## CI smoke test

The manual workflow calls `GET /v1/models` using the configured secret and verifies that the configured smoke model is available. It intentionally does not generate model output.

The smoke model is supplied by `OPENAI_SMOKE_MODEL` and defaults to `gpt-5.4-mini`; model selection must remain an operational CI setting rather than an Android-embedded credential or billing control.

## Production gate

The OpenAI feature is not production-complete until an authenticated backend gateway exists with authorization, strict request/token budgets, rate limiting, abuse protection, correlation IDs without sensitive payload logging, timeout/retry/cancellation policy, explicit Android consent state, offline/API failure tests, monitoring without private content capture, HTTPS deployment, external secret storage, and release tests proving that no OpenAI credential is present in the APK.

This separation preserves VVF's local-first privacy model while allowing an explicitly opted-in OpenAI capability.
