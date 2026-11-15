"""Only coarse onboarding answers enter the proxy; no identity fields."""
import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

UserType = Literal['student', 'parent', 'senior', 'professional']
ThreatType = Literal['digital_arrest', 'kyc_bank', 'courier_customs', 'job_offer',
                     'family_emergency', 'loan_app', 'sim_swap', 'utility_disconnect']


class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)


class Answers(StrictModel):
    userType: UserType
    ageBand: Literal['under_18', '18_29', '30_44', '45_59', '60_plus'] | None = None
    livingSituation: Literal['alone', 'with_family', 'with_others'] | None = None
    employment: Literal['studying', 'employed', 'self_employed', 'seeking_work', 'retired', 'other'] | None = None
    digitalConfidence: Literal['low', 'medium', 'high'] | None = None
    bankAppUsage: Literal['none', 'one', 'multiple'] | None = None
    activities: list[Literal['online_banking', 'online_shopping', 'job_search',
                             'loan_apps', 'international_parcels', 'online_bill_payments',
                             'social_media']] = Field(default_factory=list, max_length=7)
    concerns: list[ThreatType] = Field(default_factory=list, max_length=8)
    priorScamExposure: Literal['yes', 'no', 'prefer_not_to_say'] | None = None

    @field_validator('activities', 'concerns')
    @classmethod
    def unique(cls, value):
        if len(set(value)) != len(value):
            raise ValueError('Duplicate selections')
        return value


class OnboardingRequest(StrictModel):
    consent: Literal[True]
    answers: Answers

    @field_validator('consent', mode='before')
    @classmethod
    def explicit_consent(cls, value):
        if value is not True:
            raise ValueError('Explicit boolean consent required')
        return value


class Threat(StrictModel):
    type: ThreatType
    level: Literal['low', 'medium', 'high']
    why: str = Field(min_length=1, max_length=160)

    @field_validator('why')
    @classmethod
    def safe_reason(cls, value):
        # Defense in depth, not a general-purpose PII detector. Inputs contain no
        # free text or identifying fields, and the model is told not to invent them.
        if (value != value.strip() or not value.strip() or not value.isascii()
                or any(ord(c) < 32 for c in value)
                or re.search(r'@|https?://|www\.|\d{4,}', value, re.I)):
            raise ValueError('Reason must be a short non-identifying English line')
        return value


class Profile(StrictModel):
    version: Literal[1]
    userType: UserType
    threats: list[Threat] = Field(min_length=1, max_length=8)

    @field_validator('version', mode='before')
    @classmethod
    def integer_version(cls, value):
        if type(value) is not int or value != 1:
            raise ValueError('Integer version 1 required')
        return value

    @model_validator(mode='after')
    def unique_threats(self):
        if len({t.type for t in self.threats}) != len(self.threats):
            raise ValueError('Duplicate threat types')
        return self


def provider_schema():
    """Anthropic supports a subset of JSON Schema; validate full constraints locally."""
    schema = Profile.model_json_schema()

    def clean(node):
        if isinstance(node, dict):
            return {k: clean(v) for k, v in node.items()
                    if k not in {'minLength', 'maxLength', 'maxItems', 'title'}}
        if isinstance(node, list):
            return [clean(v) for v in node]
        return node

    return clean(schema)
