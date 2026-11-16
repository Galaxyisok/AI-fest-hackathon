#!/usr/bin/env bash
# Copies ANTHROPIC_API_KEY from the root .env into both Vercel projects (production).
# Run it yourself from the repo root:   bash scripts/vercel-set-key.sh
# The key goes straight from .env to Vercel; it is not printed and not written anywhere else.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "No .env at the repo root." >&2; exit 1; }
set -a; . ./.env; set +a
[ -n "${ANTHROPIC_API_KEY:-}" ] || { echo "ANTHROPIC_API_KEY is not set in .env." >&2; exit 1; }

for dir in . proxy; do
  echo "Setting the key on the project in '$dir'..."
  (cd "$dir" && printf '%s' "$ANTHROPIC_API_KEY" | npx -y vercel@latest env add ANTHROPIC_API_KEY production --force >/dev/null)
done
echo "Done. Both projects have the key. Tell Claude to redeploy."
