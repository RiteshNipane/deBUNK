from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class Verdict(str, Enum):
    LIKELY_TRUE = "LIKELY_TRUE"
    LIKELY_FALSE = "LIKELY_FALSE"
    MISLEADING = "MISLEADING"
    UNVERIFIED = "UNVERIFIED"


class Stance(str, Enum):
    SUPPORTS = "SUPPORTS"
    CONTRADICTS = "CONTRADICTS"
    NEUTRAL = "NEUTRAL"


class VerifyRequest(BaseModel):
    text: str = Field(min_length=1, max_length=600, description="The single headline-like text the app extracted. Never a screenshot.")
    lang: Literal["en", "hi", "mr"] = "en"


class Source(BaseModel):
    name: str
    url: str
    stance: Stance
    credibility_tier: int = Field(ge=1, le=3, description="1 = wire / official / established fact-checker, 2 = major outlet, 3 = other or unknown")
    kind: Literal["fact_check", "news", "official"] = "news"
    published: str | None = Field(default=None, description="ISO-8601 publication or review date when known")
    title: str | None = Field(default=None, description="Headline or review title, for the details sheet")


class ClaimResult(BaseModel):
    claim: str
    verdict: Verdict
    confidence: int = Field(ge=0, le=100)
    summary: str
    sources: list[Source]
    developing: bool = False


class VerifyResponse(BaseModel):
    """Top-level fields are the verdict contract from the spec; `claims` carries the per-claim breakdown."""
    claim: str = Field(description="The claim the headline verdict is about")
    verdict: Verdict
    confidence: int = Field(ge=0, le=100)
    summary: str = Field(description="Max 2 lines")
    sources: list[Source]
    developing: bool = False
    claims: list[ClaimResult] = []
    cached: bool = False
    degraded: bool = Field(default=False, description="True if a stage failed or fell back (no LLM, source timeout). Confidence is capped when degraded.")
