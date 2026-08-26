# OpenAI API integration

## Status

The repository now contains a **safe CI connectivity integration** for the OpenAI API. The API credential is consumed only through the GitHub Actions secret `OPENAI_API_KEY`; it is never committed to the repository and never packaged into the Android APK.

The manual workflow is `.github/workflows/openai-api-smoke.yml`.

## Why the Android app must not contain the API key

`The-VVF-Search` is an Android application. A project API key embedded in an APK can be extracted by a determined user and abused. Therefore the production application must not call `api.openai.com` with the project's secret directly from the Android process.

The secure production topology is:

```text
Android app
   |
   | user-opted-in AI request
   v
VVF AI Gateway / backend
   |
   | server-side OPENAI_API_KEY
   v
OpenAI API
```

The existing local/on-device semantic search remains the default path. OpenAI is an **optional cloud AI capability**, not a replacement for local indexing or local duplicate detection.

## Data-minimization contract

The Android client must not upload arbitrary device files automatically. A cloud AI request must be:

1. explicitly initiated by the user;
2. disabled by default until the user enables cloud AI;
3. limited to the smallest text/metadata required for the requested operation;
4. free of vault contents, secrets, authentication tokens, and raw private files unless a future feature explicitly requires a user-confirmed upload;
5. cancellable and time-bounded;
6. represented in UI as a cloud/network operation rather than a local operation.

## Recommended API roles

Use the OpenAI Responses API for assistant-style reasoning and structured answers. Keep model selection server-side so the Android app cannot arbitrarily increase cost.

For semantic retrieval, prefer the existing on-device embedding pipeline for private/local search. If a future cloud-search mode is added, use a server-side embedding service and keep cloud indexes logically separate from the local Room database.

## Secret handling

- GitHub Actions: `OPENAI_API_KEY` repository/environment secret.
- Production gateway: platform secret manager/environment secret.
- Android: **never** store the project API key in `BuildConfig`, resources, assets, Gradle properties, source code, or the APK.
- Logs: never log Authorization headers, API keys, request bodies containing private content, or full OpenAI responses.
- Rotation: rotate the key without requiring an Android release.

## CI smoke test

The manual workflow calls `GET /v1/models` with the configured secret and verifies that `gpt-5.4-mini` is available to the configured project. This deliberately avoids a billable generation request while proving authentication and model entitlement.

Run it from GitHub Actions with **Run workflow**. A green result means the secret can authenticate to OpenAI and the selected model is available; it does **not** prove that a production gateway or Android feature is complete.

## Production gate

The OpenAI feature is not production-complete until a backend gateway exists and has:

- authenticated/authorized client requests;
- strict request-size and token budgets;
- rate limiting and abuse protection;
- request correlation IDs without sensitive payload logging;
- timeout, retry, and cancellation policy;
- explicit cloud-AI consent state in the Android app;
- tests for offline fallback and API failure;
- monitoring without private content capture;
- a deployed HTTPS endpoint;
- a secret stored outside the mobile binary;
- release tests proving that the APK contains no OpenAI credential.

This separation preserves VVF's local-first privacy model while allowing an explicitly opted-in OpenAI capability.
