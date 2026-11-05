"""Claim extraction: raw screen text -> 1-3 atomic, checkable claims, each with a news-search query."""
import asyncio
import json
import logging
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

log = logging.getLogger("debunk.claims")

SYSTEM_PROMPT = (Path(__file__).parent / "prompts" / "claim_extraction.md").read_text(encoding="utf-8")
MAX_CLAIMS = 3
MIN_CLAIM_CHARS = 15
MAX_CLAIM_CHARS = 300
MAX_QUERY_TOKENS = 8

_STOPWORDS = frozenset(
    "a an the and or but of in on at to for from by with as is are was were be been being has have had do does did "
    "that this these those it its their his her he she they them we you i not no says say said after before over "
    "into out up down about than then so such will would could should may might can new more most also just all any some".split()
)
_TOKEN = re.compile(r"[\w][\w'\-]*", re.UNICODE)


def content_tokens(text: str) -> list[str]:
    """Lowercased content words (stopwords and 1-char tokens removed), order preserved, duplicates removed."""
    seen: set[str] = set()
    out: list[str] = []
    for t in _TOKEN.findall(text.lower()):
        t = t.strip("'-")
        if len(t) > 1 and t not in _STOPWORDS and t not in seen:
            seen.add(t)
            out.append(t)
    return out


def keyword_query(text: str, max_tokens: int = MAX_QUERY_TOKENS) -> str:
    return " ".join(content_tokens(text)[:max_tokens])


@dataclass(frozen=True)
class Claim:
    text: str
    query: str


class ClaimExtractor(Protocol):
    async def extract(self, text: str, lang: str) -> list[Claim]: ...


def _clean_query(raw: object, fallback_from: str) -> str:
    if isinstance(raw, str):
        toks = content_tokens(re.sub(r"[\"“”()]|\b(?:AND|OR|NOT)\b", " ", raw))
        if toks:
            return " ".join(toks[:MAX_QUERY_TOKENS])
    return keyword_query(fallback_from)


def parse_claims_json(raw: str) -> list[Claim]:
    """Tolerant parser for the model output: strips code fences / chatter, validates, dedupes, caps."""
    raw = raw.strip()
    raw = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw, flags=re.IGNORECASE)
    start, end = raw.find("{"), raw.rfind("}")
    if start == -1 or end <= start:
        return []
    try:
        data = json.loads(raw[start : end + 1])
    except json.JSONDecodeError:
        return []
    if not isinstance(data, dict) or data.get("is_news") is False:
        return []
    out: list[Claim] = []
    for item in data.get("claims") or []:
        text = item.get("text") if isinstance(item, dict) else item
        if not isinstance(text, str):
            continue
        text = " ".join(text.split())
        if not (MIN_CLAIM_CHARS <= len(text) <= MAX_CLAIM_CHARS) or text.lower() in {c.text.lower() for c in out}:
            continue
        query = _clean_query(item.get("query") if isinstance(item, dict) else None, text)
        out.append(Claim(text, query))
        if len(out) == MAX_CLAIMS:
            break
    return out


class LLMClaimExtractor:
    def __init__(self, llm):
        self._llm = llm

    async def extract(self, text: str, lang: str) -> list[Claim]:
        safe = text.replace("<", "\u2039")  # screen text must not be able to close our delimiter tag
        raw = await self._llm.complete(SYSTEM_PROMPT, f"<screen_text>\n{safe}\n</screen_text>", max_tokens=1000, timeout=8.0)
        claims = parse_claims_json(raw)
        if not claims and not re.search(r'"is_news"\s*:\s*false', raw, re.IGNORECASE):
            # Not an honest "no claim" answer: the reply was empty, truncated or not JSON. Let the heuristic take over.
            raise ValueError("unparseable claim-extraction reply")
        return claims


ClaudeClaimExtractor = LLMClaimExtractor  # old name kept for compatibility


_PREFIX = re.compile(
    r"^\s*(?:breaking(?:\s+news)?|just\s+in|news\s+alert|developing\s+story|exclusive|live\s+updates?|alert)\s*[:\-|–—]*\s*",
    re.IGNORECASE,
)


class HeuristicClaimExtractor:
    """Offline fallback: strips 'BREAKING:'-style prefixes and returns the sentence as the single claim."""

    async def extract(self, text: str, lang: str) -> list[Claim]:
        cleaned = " ".join(_PREFIX.sub("", text).split())
        if len(cleaned.split()) < 5 or len(cleaned) < MIN_CLAIM_CHARS:
            return []
        cleaned = cleaned[:MAX_CLAIM_CHARS]
        return [Claim(cleaned, keyword_query(cleaned))]


class FallbackExtractor:
    """Use the primary extractor within a time budget; on error/timeout degrade to the heuristic instead of failing."""

    def __init__(self, primary: ClaimExtractor, fallback: ClaimExtractor, timeout_s: float = 3.0):
        self._primary, self._fallback, self._timeout = primary, fallback, timeout_s

    async def extract(self, text: str, lang: str) -> list[Claim]:
        try:
            return await asyncio.wait_for(self._primary.extract(text, lang), self._timeout)
        except Exception as exc:  # noqa: BLE001 - any upstream failure should degrade, not 500
            log.warning("claim extraction failed (%s); using heuristic", type(exc).__name__)
            return await self._fallback.extract(text, lang)
