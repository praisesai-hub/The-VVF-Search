#!/usr/bin/env bash
set -euo pipefail

destination="${1:?destination file is required}"
readonly model_url="https://storage.googleapis.com/download/storage/v1/b/mediapipe-models/o/text_embedder%2Fembedding_gemma%2Fint4int8%2Flatest%2Fembedding_gemma.task?alt=media&generation=1782238479465541"
readonly expected_sha256="913b7a1edc7c7c3d1da3979ec1d0648ed9e0a370f181bb59ab177ca4b97707ad"

mkdir -p "$(dirname "$destination")"
if [[ -f "$destination" ]] && [[ "$(sha256sum "$destination" | awk '{print $1}')" == "$expected_sha256" ]]; then
  exit 0
fi

temporary="${destination}.part"
rm -f "$temporary"
curl --fail --location --retry 2 --connect-timeout 30 --max-time 600 --output "$temporary" "$model_url"
actual_sha256="$(sha256sum "$temporary" | awk '{print $1}')"
if [[ "$actual_sha256" != "$expected_sha256" ]]; then
  rm -f "$temporary"
  printf '%s\n' "EmbeddingGemma model integrity verification failed" >&2
  exit 1
fi

mv -f "$temporary" "$destination"
