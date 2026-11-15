"""The Android app and this backend must agree on two shapes: what the app sends, and what it
can read back. Added when the two branches were joined. Uses a mocked analyzer: no AI call."""
import json
from pathlib import Path

from fastapi.testclient import TestClient

from backend.app import Settings, create_app
from backend.models import Answers, Profile

ROOT = Path(__file__).resolve().parent.parent
VECTORS = json.loads((ROOT / 'shared' / 'onboarding-request-vectors.json').read_text(encoding='utf-8'))

# The Android enums, as written in app/src/main/java/com/scamguard/profile/VulnerabilityProfile.kt
ANDROID_THREATS = {'digital_arrest', 'kyc_bank', 'courier_customs', 'job_offer',
                   'family_emergency', 'loan_app', 'sim_swap', 'utility_disconnect'}
ANDROID_MAX_WHY = 200


class EchoAnalyzer:
    """Returns one threat of every type, so the response exercises the whole contract."""
    async def analyze(self, answers: Answers) -> Profile:
        return Profile(version=1, userType=answers.userType, threats=[
            {'type': t, 'level': 'medium', 'why': 'A short plain reason.'} for t in sorted(ANDROID_THREATS)])


def client():
    return TestClient(create_app(Settings(api_key='test'), EchoAnalyzer()))


def test_backend_accepts_exactly_what_android_sends():
    with client() as c:
        for name in ('minimal', 'full'):
            r = c.post('/v1/onboarding/profile', json={'consent': True, 'answers': VECTORS[name]})
            assert r.status_code == 200, (name, r.text)


def test_response_is_the_bare_profile_android_parses():
    with client() as c:
        body = c.post('/v1/onboarding/profile', json={'consent': True, 'answers': VECTORS['full']}).json()
    assert set(body) == {'version', 'userType', 'threats'}
    assert body['version'] == 1 and body['userType'] == VECTORS['full']['userType']
    assert {t['type'] for t in body['threats']} == ANDROID_THREATS
    for t in body['threats']:
        assert set(t) == {'type', 'level', 'why'}
        assert t['level'] in {'low', 'medium', 'high'}
        assert len(t['why']) <= ANDROID_MAX_WHY


def test_schema_file_matches_android_enums():
    schema = json.loads((ROOT / 'contracts' / 'profile.schema.json').read_text(encoding='utf-8'))
    threat = schema['$defs']['Threat']['properties']
    assert set(threat['type']['enum']) == ANDROID_THREATS
    assert threat['why']['maxLength'] <= ANDROID_MAX_WHY
