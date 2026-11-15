"""Stateless Phase 1 proxy. Never log request bodies or provider responses."""
import asyncio
import os
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .models import OnboardingRequest, Profile
from .provider import HaikuAnalyzer, ProviderFailure


@dataclass(frozen=True)
class Settings:
    api_key: str = ''
    model: str = 'claude-haiku-4-5-20251001'
    requests_per_minute: int = 30
    max_inflight: int = 4

    def __post_init__(self):
        if not self.model.startswith('claude-haiku-'):
            raise ValueError('Phase 1 requires Claude Haiku')
        if self.requests_per_minute < 1 or self.max_inflight < 1:
            raise ValueError('Limits must be positive')

    @classmethod
    def from_env(cls):
        return cls(os.getenv('ANTHROPIC_API_KEY', ''),
                   os.getenv('CLAUDE_MODEL', 'claude-haiku-4-5-20251001'),
                   int(os.getenv('REQUESTS_PER_MINUTE', '30')),
                   int(os.getenv('MAX_INFLIGHT', '4')))


def error(status, code, message):
    return JSONResponse({'error': {'code': code, 'message': message}}, status_code=status,
                        headers={'Cache-Control': 'no-store'})


class BodyLimit:
    """Bound actual received bytes, including chunked requests, before JSON parsing."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        if scope['path'] != '/v1/onboarding/profile' or scope['method'] != 'POST':
            return await self.app(scope, receive, send)
        headers = dict(scope['headers'])
        if headers.get(b'content-type', b'').split(b';')[0].strip() != b'application/json':
            return await error(415, 'unsupported_media_type', 'Use application/json.')(scope, receive, send)
        if headers.get(b'content-encoding', b'identity') != b'identity':
            return await error(415, 'unsupported_encoding', 'Compressed bodies are not supported.')(scope, receive, send)
        body = bytearray()
        try:
            async with asyncio.timeout(10):
                while True:
                    message = await receive()
                    if message['type'] == 'http.disconnect':
                        return
                    body.extend(message.get('body', b''))
                    if len(body) > 8192:
                        return await error(413, 'body_too_large', 'Request is too large.')(scope, receive, send)
                    if not message.get('more_body', False):
                        break
        except TimeoutError:
            return await error(408, 'request_timeout', 'Request timed out.')(scope, receive, send)
        delivered = False

        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {'type': 'http.request', 'body': bytes(body), 'more_body': False}
            return await receive()

        try:
            await self.app(scope, replay, send)
        finally:
            body.clear()


def create_app(settings=None, analyzer=None):
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app):
        async with httpx.AsyncClient(timeout=30, follow_redirects=False) as client:
            app.state.analyzer = analyzer or HaikuAnalyzer(client, settings.api_key, settings.model)
            yield

    app = FastAPI(title='Phase 1 onboarding proxy', version='1.0.0', lifespan=lifespan)
    app.add_middleware(BodyLimit)
    # Global, bounded counters: no IP addresses, user identifiers or answer data.
    app.state.window = time.monotonic()
    app.state.requests = 0
    app.state.inflight = 0

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        # FastAPI's default errors echo submitted values; deliberately suppress them.
        return error(422, 'invalid_request', 'Check consent and the allowed onboarding selections.')

    @app.get('/healthz')
    async def health():
        return {'status': 'ok'}

    @app.get('/readyz')
    async def ready():
        if not settings.api_key and analyzer is None:
            return error(503, 'not_configured', 'AI service is not configured.')
        return {'status': 'ready'}

    @app.post('/v1/onboarding/profile', response_model=Profile)
    async def generate(payload: OnboardingRequest, request: Request):
        if not settings.api_key and analyzer is None:
            return error(503, 'not_configured', 'AI service is not configured.')
        now = time.monotonic()
        if now - app.state.window >= 60:
            app.state.window, app.state.requests = now, 0
        if app.state.requests >= settings.requests_per_minute or app.state.inflight >= settings.max_inflight:
            response = error(429, 'busy', 'Please try again shortly.')
            response.headers['Retry-After'] = '60'
            return response
        app.state.requests += 1
        app.state.inflight += 1
        try:
            profile = await app.state.analyzer.analyze(payload.answers)
            return JSONResponse(profile.model_dump(), headers={'Cache-Control': 'no-store'})
        except ProviderFailure:
            return error(502, 'analysis_unavailable', 'Analysis could not be completed. Please try again.')
        finally:
            app.state.inflight -= 1

    return app


app = create_app()
