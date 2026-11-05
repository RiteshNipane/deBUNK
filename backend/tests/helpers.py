from datetime import datetime, timedelta, timezone

from app.evidence import Evidence
from app.models import Stance
from app.scoring import ScoredEvidence

NOW = datetime(2026, 10, 27, 12, 0, tzinfo=timezone.utc)


def ev(site: str, title: str = "Fire at Delhi market kills 12", age_h: float | None = 48, kind: str = "news",
       rating: str | None = None, name: str | None = None) -> Evidence:
    published = None if age_h is None else NOW - timedelta(hours=age_h)
    return Evidence(kind, name or site, site, f"https://{site}/{abs(hash((site, title)))}", title, "", published, rating)


def sc(e: Evidence, stance: Stance = Stance.SUPPORTS, relevant: bool = True, misleading: bool = False) -> ScoredEvidence:
    return ScoredEvidence(e, stance, relevant, misleading)


S, C, N = Stance.SUPPORTS, Stance.CONTRADICTS, Stance.NEUTRAL
