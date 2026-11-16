"""Opt-in live Haiku check using synthetic selections only (so the printed profile is not personal)."""
import asyncio
import os

import httpx

from .models import Answers
from .provider import DEFAULT_MODEL, HaikuAnalyzer


async def main():
    key = os.getenv('ANTHROPIC_API_KEY')
    if not key:
        raise SystemExit('Set ANTHROPIC_API_KEY on the server before running this paid smoke test.')
    async with httpx.AsyncClient(timeout=30, follow_redirects=False) as client:
        profile = await HaikuAnalyzer(client, key, os.getenv('CLAUDE_MODEL', DEFAULT_MODEL)).analyze(
            Answers(userType='student', employment='seeking_work', activities=['job_search']))
        assert profile.version == 1
        # Synthetic selections in, so the profile is safe to show. This is how relevance gets eyeballed.
        for t in profile.threats:
            print(f'{t.level:6} {t.type:20} {t.why}')
        print('Live Haiku response passed the version-1 profile contract.')


if __name__ == '__main__':
    asyncio.run(main())
