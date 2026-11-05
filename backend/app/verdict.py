"""Turns scored evidence into the spec's verdict JSON.

Weights:   w = tier_weight x recency_weight (x 1.5 for an explicit fact-check)
Independence: one voice per outlet, and near-identical headlines across outlets (wire copy) collapse into one voice.
Rules:     fewer than 2 independent voices => UNVERIFIED. Missing coverage is never read as "false":
           LIKELY_FALSE needs actual contradicting evidence from a fact-checker or a tier 1-2 source.
"""
import math
from datetime import datetime, timedelta, timezone

from .claims import content_tokens
from .evidence import Evidence
from .models import ClaimResult, Source, Stance, Verdict
from .ratings import RatingClass, classify_rating  # noqa: F401 - re-exported
from .scoring import ScoredEvidence
from .tiers import credibility_tier

MIN_INDEPENDENT_SOURCES = 2
TIER_WEIGHT = {1: 1.0, 2: 0.6, 3: 0.3}
FACTCHECK_BOOST = 1.5
MIN_TRUE_WEIGHT = 0.8      # e.g. two tier-2 outlets from the last week (0.6 x 0.8 x 2 = 0.96), or tier 1 + tier 3
WIRE_COPY_JACCARD = 0.8
SYNDICATION_BONUS = 0.5    # other credible outlets carrying the same wire report add half their weight...
SYNDICATED_CAP = 65        # ...but a verdict resting only on one report carried by several outlets stays moderate
MAX_SOURCES_SHOWN = 8
DEVELOPING_WINDOW = timedelta(hours=6)
STALE_AFTER_DAYS = 180     # an old article is a different event, not confirmation of today's claim
CONFIDENCE_SCALE = 0.9     # evidence mass at which confidence reaches ~63% of its ceiling (two fresh tier-2 outlets ~70%)


def recency_weight(published: datetime | None, now: datetime) -> float:
    if published is None:
        return 0.5
    age = now - published
    if age <= timedelta(hours=24):
        return 1.0
    if age <= timedelta(days=7):
        return 0.8
    if age <= timedelta(days=30):
        return 0.5
    if age <= timedelta(days=STALE_AFTER_DAYS):
        return 0.3
    return 0.1


def weight(e: Evidence, now: datetime) -> float:
    w = TIER_WEIGHT[credibility_tier(e.site)]
    if e.kind == "factcheck":
        return w * FACTCHECK_BOOST
    return w * recency_weight(e.published, now)


def _jaccard(a: set[str], b: set[str]) -> float:
    return len(a & b) / len(a | b) if a and b else 0.0


def cluster(items: list[ScoredEvidence]) -> list[list[ScoredEvidence]]:
    """Union-find: same outlet, or near-duplicate headline => same independent voice."""
    parent = list(range(len(items)))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    toks = [set(content_tokens(s.evidence.title)) for s in items]
    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            if items[i].evidence.site == items[j].evidence.site or _jaccard(toks[i], toks[j]) >= WIRE_COPY_JACCARD:
                parent[find(i)] = find(j)
    groups: dict[int, list[ScoredEvidence]] = {}
    for i, s in enumerate(items):
        groups.setdefault(find(i), []).append(s)
    return list(groups.values())


def _unverified(claim: str, summary: str, developing: bool = False, sources: list[Source] | None = None,
                confidence: int = 0) -> ClaimResult:
    return ClaimResult(claim=claim, verdict=Verdict.UNVERIFIED, confidence=confidence, summary=summary,
                       sources=sources or [], developing=developing)


def _names(voices: list[tuple[ScoredEvidence, float]], n: int = 3) -> str:
    return ", ".join(list(dict.fromkeys(v.evidence.name for v, _ in voices))[:n])


def build_verdict(claim: str, scored: list[ScoredEvidence], now: datetime | None = None) -> ClaimResult:
    now = now or datetime.now(timezone.utc)
    stale = timedelta(days=STALE_AFTER_DAYS)
    relevant = [s for s in scored if s.relevant and not (
        s.evidence.kind != "factcheck" and s.evidence.published is not None and now - s.evidence.published > stale)]
    if not relevant:
        return _unverified(claim, "No fact-check or credible coverage found yet. Treat with caution.")

    # One voice per cluster: its strongest item represents it. Other OUTLETS in the same cluster (wire copy) are not
    # independent confirmation, but they are not worthless either: they add partial weight, count the voice as two
    # for the "at least 2 sources" rule when two or more are credible, and are all listed as sources.
    voices: list[tuple[ScoredEvidence, float]] = []
    members: dict[int, list[ScoredEvidence]] = {}   # id(best) -> same-stance items from OTHER outlets in its cluster
    synd_n: dict[int, int] = {}                     # id(best) -> number of distinct credible outlets in its cluster
    is_cred = lambda s: credibility_tier(s.evidence.site) <= 2 or s.evidence.kind == "factcheck"  # noqa: E731
    for grp in cluster(relevant):
        best = max(grp, key=lambda s: weight(s.evidence, now))
        w = weight(best.evidence, now)
        by_site: dict[str, ScoredEvidence] = {}
        for m in grp:
            if m.evidence.site != best.evidence.site and m.stance == best.stance and m.misleading == best.misleading:
                if m.evidence.site not in by_site or weight(m.evidence, now) > weight(by_site[m.evidence.site].evidence, now):
                    by_site[m.evidence.site] = m
        others = list(by_site.values())
        members[id(best)] = others
        synd_n[id(best)] = len({best.evidence.site, *[m.evidence.site for m in others if is_cred(m)]}) if is_cred(best) else 1
        voices.append((best, w + SYNDICATION_BONUS * sum(weight(m.evidence, now) for m in others)))
    voices.sort(key=lambda v: (credibility_tier(v[0].evidence.site), -v[1], v[0].evidence.name))

    def side(v: ScoredEvidence) -> str:
        if v.misleading:
            return "mis"
        return {Stance.SUPPORTS: "sup", Stance.CONTRADICTS: "con"}.get(v.stance, "neu")

    sup = [(v, w) for v, w in voices if side(v) == "sup"]
    con = [(v, w) for v, w in voices if side(v) == "con"]
    mis = [(v, w) for v, w in voices if side(v) == "mis"]
    neu = [(v, w) for v, w in voices if side(v) == "neu"]
    S, C, M, N = (sum(w for _, w in g) for g in (sup, con, mis, neu))
    # a voice carried by 2+ credible outlets counts as two toward the "at least 2 sources" rule (not toward weight)
    directional = sum(2 if synd_n[id(v)] >= 2 else 1 for v, _ in sup + con + mis)
    cred_tier = lambda g: any(credibility_tier(v.evidence.site) <= 2 or v.evidence.kind == "factcheck" for v, _ in g)  # noqa: E731
    # One credible outlet confirms the claim and at least one other independent outlet covers the same story without
    # disputing it: that is real (if modest) corroboration. It gives a low-confidence LIKELY_TRUE, never a strong one.
    single_ok = directional == 1 and len(sup) == 1 and len(voices) >= MIN_INDEPENDENT_SOURCES and cred_tier(sup)

    shown = [(v, w) for v, w in voices if side(v) != "neu"] + [(v, w) for v, w in voices if side(v) == "neu"]
    flat = [m for v, _ in shown for m in [v, *members[id(v)]]]
    sources = [
        Source(name=v.evidence.name, url=v.evidence.url, stance=v.stance if not v.misleading else Stance.NEUTRAL,
               credibility_tier=credibility_tier(v.evidence.site),
               kind={"factcheck": "fact_check", "fact_check": "fact_check", "official": "official"}.get(v.evidence.kind, "news"),
               published=v.evidence.published.isoformat() if v.evidence.published else None,
               title=(v.evidence.title or None))
        for v in flat[:MAX_SOURCES_SHOWN]
    ]
    newest = max((v.evidence.published for v, _ in voices if v.evidence.published), default=None)
    fresh = newest is not None and now - newest <= DEVELOPING_WINDOW

    # Not "0%": a little coverage is a little evidence. Capped low so UNVERIFIED never looks like a finding.
    coverage = min(30, round(100 * (1 - math.exp(-(S + C + M + N) / 3.0))))

    if directional < MIN_INDEPENDENT_SOURCES and not single_ok:
        names = _names(shown)
        msg = (f"Only {len(voices)} source so far ({names}); too early to call. Developing story."
               if len(voices) == 1 else f"Sources ({names}) cover this but none settle it yet. Developing story.")
        return _unverified(claim, msg, developing=True, sources=sources, confidence=coverage)

    total = S + C + M
    mixed = M / total >= 0.4 or min(S, C) / total >= 0.25

    if mixed:
        verdict, dom_share, dom_w = Verdict.MISLEADING, max(S, C, M) / total, max(S, C, M)
    elif C > S:
        if not cred_tier(con):
            return _unverified(claim, "Only low-credibility sources dispute this claim. Not enough to call it false.",
                               developing=True, sources=sources, confidence=coverage)
        verdict, dom_share, dom_w = Verdict.LIKELY_FALSE, C / total, C
    else:
        if (S < MIN_TRUE_WEIGHT and not single_ok) or not cred_tier(sup):
            return _unverified(claim, "Reported, but not yet by enough credible independent outlets. Developing story.",
                               developing=True, sources=sources, confidence=coverage)
        verdict, dom_share, dom_w = Verdict.LIKELY_TRUE, S / total, S

    # Neutral coverage of the same story weighs half as much as a directional voice: it dilutes the lead of the winning side
    # (2 supports + 2 neutrals is a middling result, not a strong one) and still counts as evidence mass.
    denom = total + 0.5 * N
    confidence = round(100 * (dom_w / denom) * (1 - math.exp(-denom / CONFIDENCE_SCALE)))
    only_syndicated = (verdict == Verdict.LIKELY_TRUE and len(sup) == 1 and not con and not mis
                       and synd_n[id(sup[0][0])] >= 2)
    cap = 50 if single_ok else (80 if verdict == Verdict.MISLEADING else 95)
    if only_syndicated:
        cap = min(cap, SYNDICATED_CAP)
    confidence = min(confidence, cap)

    names = _names(sup if verdict == Verdict.LIKELY_TRUE else con if verdict == Verdict.LIKELY_FALSE else mis + con + sup)
    has_fc = any(v.evidence.kind == "factcheck" for v, _ in (con if verdict == Verdict.LIKELY_FALSE else []))
    synd_names = ", ".join(dict.fromkeys([sup[0][0].evidence.name, *[m.evidence.name for m in members[id(sup[0][0])]]][:3])) if only_syndicated else ""
    summary = {
        Verdict.LIKELY_TRUE: (f"Carried by {synd_n[id(sup[0][0])]} credible outlets ({synd_names}), apparently the same report. "
                              f"No independent confirmation or dispute yet."
                              if only_syndicated else
                              f"Reported by {names}; {len(neu)} other outlet(s) cover the story but do not confirm the details."
                              if single_ok else
                              f"{len(sup)} independent sources ({names}) report this. None of the sources found contradicts it."
                              if not con else f"{len(sup)} independent sources ({names}) report this; {len(con)} dispute details."),
        Verdict.LIKELY_FALSE: f"Fact-checkers ({names}) rate this false." if has_fc
                              else f"Credible sources ({names}) contradict this claim.",
        Verdict.MISLEADING: f"Sources ({names}) disagree or say this lacks context.",
    }[verdict]
    developing = single_ok or (fresh and directional < 3)
    return ClaimResult(claim=claim, verdict=verdict, confidence=confidence, summary=summary[:200],
                       sources=sources, developing=developing)


# When a headline has several claims, surface the one most worth warning about.
_PRIORITY = {Verdict.LIKELY_FALSE: 0, Verdict.MISLEADING: 1, Verdict.LIKELY_TRUE: 2, Verdict.UNVERIFIED: 3}


def pick_headline(results: list[ClaimResult]) -> ClaimResult:
    return min(results, key=lambda r: (_PRIORITY[r.verdict], -r.confidence))
