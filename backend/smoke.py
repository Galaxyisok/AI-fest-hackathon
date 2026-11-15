"""Opt-in live Haiku check using synthetic selections only; prints no profile."""
import asyncio
import os

import httpx

from .models import Answers
from .provider import HaikuAnalyzer


async def main():
    key = os.getenv('ANTHROPIC_API_KEY')
    if not key:
        raise SystemExit('Set ANTHROPIC_API_KEY on the server before running this paid smoke test.')
    async with httpx.AsyncClient(timeout=30, follow_redirects=False) as client:
        profile = await HaikuAnalyzer(client, key, 'claude-haiku-4-5-20251001').analyze(
            Answers(userType='student', employment='seeking_work', activities=['job_search']))
        assert profile.version == 1
        print('Live Haiku response passed the version-1 profile contract.')


if __name__ == '__main__':
    asyncio.run(main())
