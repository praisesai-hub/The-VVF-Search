#!/usr/bin/env node

/**
 * OpenAI API connectivity smoke test.
 *
 * This test intentionally uses GET /v1/models so CI can validate that the
 * configured OPENAI_API_KEY is accepted without generating billable output.
 * Never print the key or authorization header.
 */

const apiKey = process.env.OPENAI_API_KEY;
const expectedModel = process.env.OPENAI_SMOKE_MODEL ?? "gpt-5.4-mini";

if (!apiKey) {
  console.error("OPENAI_API_KEY is not configured.");
  process.exit(2);
}

if (apiKey.length < 20) {
  console.error("OPENAI_API_KEY is present but has an invalid length.");
  process.exit(2);
}

const response = await fetch("https://api.openai.com/v1/models", {
  method: "GET",
  headers: {
    Authorization: `Bearer ${apiKey}`,
    Accept: "application/json",
  },
  signal: AbortSignal.timeout(15_000),
});

if (!response.ok) {
  console.error(`OpenAI API authentication/request failed with HTTP ${response.status}.`);
  process.exit(1);
}

const payload = await response.json();
const models = Array.isArray(payload?.data) ? payload.data : [];
const available = models.some((model) => model?.id === expectedModel);

if (!available) {
  console.error(`OpenAI API is reachable, but smoke model '${expectedModel}' is not available to this key/project.`);
  process.exit(1);
}

console.log(`OpenAI API connectivity verified; model '${expectedModel}' is available.`);
