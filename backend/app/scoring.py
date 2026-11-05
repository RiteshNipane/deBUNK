"""Evidence scoring: for each retrieved item decide supports / contradicts / neutral relative to the claim."""
import asyncio
import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .claims import Claim, content_tokens
from .evidence import Evidence
from .models import Stance
from .ratings import RatingClass, classify_rating

log = logging.getLogger("debunk.scoring")
SYSTEM_PROMPT = (Path(__file__).parent / "prompts" / "evidence_classification.md").read_text(encoding="utf-8")

_DENIAL_WORDS = ("deny", "denies", "denied", "false", "fake", "hoax", "debunk", "not true", "no truth", "rumour", "rumor",
                 "myth", "baseless", "misleading", "clarifies", "refutes", "dismisses")


@dataclass(frozen=True)
class ScoredEvidence:
    evidence: Evidence
    stance: Stance
    relevant: bool
    misleading: bool = False    # fact-checker rated it misleading / partly false (direction: neither true nor false)


class StanceClassifier(Protocol):
    async def classify(self, claim: Claim, items: list[Evidence]) -> list[tuple[Stance, bool]]: ...


def overlap(claim_tokens: set[str], e: Evidence) -> float:
    if not claim_tokens:
        return 0.0
    return len(claim_tokens & set(content_tokens(f"{e.title} {e.snippet}"))) / len(claim_tokens)


class HeuristicStanceClassifier:
    """Offline fallback. Keyword overlap only: 'same event is covered' => supports; denial words => contradicts.
    Much weaker than the LLM classifier (it cannot compare numbers or details); P3 uses it only when Claude is unavailable."""

    async def classify(self, claim: Claim, items: list[Evidence]) -> list[tuple[Stance, bool]]:
        ct = set(content_tokens(claim.text))
        out: list[tuple[Stance, bool]] = []
        for e in items:
            o = overlap(ct, e)
            text = f"{e.title} {e.snippet}".lower()
            if o >= 0.35 and any(w in text for w in _DENIAL_WORDS):
                out.append((Stance.CONTRADICTS, True))
            elif o >= 0.6:
                out.append((Stance.SUPPORTS, True))
            elif o >= 0.35:
                out.append((Stance.NEUTRAL, True))
            else:
                out.append((Stance.NEUTRAL, False))
        return out


def parse_stance_json(raw: str, n: int) -> list[tuple[Stance, bool]]:
    """Tolerant parse; anything missing/invalid becomes (NEUTRAL, not relevant) so bad output can never create a verdict."""
    default = [(Stance.NEUTRAL, False)] * n
    start, end = raw.find("{"), raw.rfind("}")
    if start == -1 or end <= start:
        return default
    try:
        data = json.loads(raw[start : end + 1])
    except json.JSONDecodeError:
        return default
    out = list(default)
    for r in data.get("results") or [] if isinstance(data, dict) else []:
        if not isinstance(r, dict) or not isinstance(r.get("id"), int) or not 0 <= r["id"] < n:
            continue
        try:
            stance = Stance(str(r.get("stance", "")).upper())
        except ValueError:
            continue
        out[r["id"]] = (stance, bool(r.get("relevant")) and r.get("relevant") is True)
    return out


class LLMStanceClassifier:
    def __init__(self, llm):
        self._llm = llm

    async def classify(self, claim: Claim, items: list[Evidence]) -> list[tuple[Stance, bool]]:
        payload = [
            {"id": i, "source": e.name, "title": e.title[:200], "snippet": e.snippet[:300],
             "published": e.published.date().isoformat() if e.published else None}
            for i, e in enumerate(items)
        ]
        # "<" escaped so evidence text can never close our delimiter tags.
        body = json.dumps(payload, ensure_ascii=False).replace("<", "\\u003c")
        claim_text = claim.text.replace("<", "\u2039")
        raw = await self._llm.complete(SYSTEM_PROMPT, f"<claim>{claim_text}</claim>\n<items>\n{body}\n</items>",
                                       max_tokens=2000, timeout=8.0)
        if '"results"' not in raw:
            raise ValueError("stance reply was empty or not the expected JSON")
        return parse_stance_json(raw, len(items))


ClaudeStanceClassifier = LLMStanceClassifier  # old name kept for compatibility


class FallbackStanceClassifier:
    def __init__(self, primary: StanceClassifier, fallback: StanceClassifier, timeout_s: float = 2.5):
        self._primary, self._fallback, self._timeout = primary, fallback, timeout_s

    async def classify(self, claim: Claim, items: list[Evidence]) -> list[tuple[Stance, bool]]:
        try:
            return await asyncio.wait_for(self._primary.classify(claim, items), self._timeout)
        except Exception as exc:  # noqa: BLE001
            log.warning("stance classification failed (%s); using heuristic", type(exc).__name__)
            return await self._fallback.classify(claim, items)


class EvidenceScorer:
    def __init__(self, classifier: StanceClassifier, max_items: int = 12, min_overlap: float = 0.2):
        self._classifier, self._max, self._min_overlap = classifier, max_items, min_overlap

    async def score(self, claim: Claim, evidence: list[Evidence]) -> list[ScoredEvidence]:
        out: list[ScoredEvidence] = []
        news: list[Evidence] = []
        for e in evidence:
            if e.kind == "factcheck":
                rc = classify_rating(e.rating or "")
                stance = {RatingClass.TRUE: Stance.SUPPORTS, RatingClass.FALSE: Stance.CONTRADICTS}.get(rc, Stance.NEUTRAL)
                out.append(ScoredEvidence(e, stance, True, misleading=rc == RatingClass.MISLEADING))
            else:
                news.append(e)

        # Cheap local pre-filter so the LLM only sees plausibly related items (latency + cost).
        ct = set(content_tokens(claim.text))
        ranked = sorted(((overlap(ct, e), e) for e in news), key=lambda t: -t[0])
        cand = [e for o, e in ranked if o >= self._min_overlap][: self._max]
        if cand:
            for e, (stance, relevant) in zip(cand, await self._classifier.classify(claim, cand)):
                out.append(ScoredEvidence(e, stance, relevant))
        return out
