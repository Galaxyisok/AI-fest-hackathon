#!/usr/bin/env bash
# Start both servers the app needs, with one key, for local development and the demo laptop.
#
#   echo ANTHROPIC_API_KEY=sk-ant-... > .env      # repo root, git-ignored
#   bash scripts/dev-servers.sh
#
#   profile service (Phase 1, Python)  http://localhost:8000/v1/onboarding/profile
#   call judge      (Phase 2, Node)    http://localhost:8787/api/judge
#
# The Android emulator reaches these as 10.0.2.2, which is what a debug build points at.
# A real phone on the same Wi-Fi needs this laptop's address typed into Setup in the app,
# and HTTPS, so for a phone deploy the servers instead (see proxy/README.md, backend/README.md).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then set -a; . ./.env; set +a; fi
if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  echo "ANTHROPIC_API_KEY is not set. Put it in .env at the repo root." >&2
  exit 1
fi

PY=.venv/Scripts/python
[ -x "$PY" ] || PY=.venv/bin/python
if [ ! -x "$PY" ]; then
  echo "Creating .venv and installing the profile service..." >&2
  python -m venv .venv
  PY=.venv/Scripts/python; [ -x "$PY" ] || PY=.venv/bin/python
  "$PY" -m pip install -q -r backend/requirements.lock
fi
[ -d proxy/node_modules ] || (cd proxy && npm install --silent)

"$PY" -m uvicorn backend.app:app --host 0.0.0.0 --port 8000 --no-access-log --workers 1 --no-proxy-headers &
P1=$!
(cd proxy && node local.mjs) &
P2=$!
trap 'kill $P1 $P2 2>/dev/null || true' EXIT INT TERM
echo "Both servers starting. Ctrl+C stops both."
wait -n $P1 $P2
echo "A server stopped; shutting the other down." >&2
