"""Vercel entry point for the Phase 1 profile service (backend/, unchanged).

Vercel serves this file as one Python function; vercel.json rewrites every path to it, so the
app still answers at /v1/onboarding/profile. The key comes from the ANTHROPIC_API_KEY project
environment variable, never from the repo.
"""
from backend.app import app  # noqa: F401  (Vercel looks for an ASGI callable named `app`)
