from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class Evidence:
    """One piece of evidence about a claim: a news article, an official release, or an existing fact-check."""
    kind: str                       # "news" | "official" | "factcheck"
    name: str                       # outlet / publisher display name
    site: str                       # normalized domain (no www.)
    url: str
    title: str
    snippet: str = ""
    published: datetime | None = None   # timezone-aware UTC when known
    rating: str | None = None       # fact-checks only: the publisher's own wording ("False", "Misleading" ...)
