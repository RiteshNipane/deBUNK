"""GNews search API (https://gnews.io). Free tier is ~100 requests/day; use a paid plan for real traffic."""
import logging
from datetime import datetime, timezone
from urllib.parse import urlparse

from ..claims import Claim
from ..evidence import Evidence
from ..tiers import normalize_site
from .base import short_query

ENDPOINT = "https://gnews.io/api/v4/search"
log = logging.getLogger("debunk.gnews")


def _parse_time(s: str | None) -> datetime | None:
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        return None


def parse_gnews(payload: dict) -> list[Evidence]:
    out: list[Evidence] = []
    for a in payload.get("articles") or []:
        url, title = a.get("url"), a.get("title")
        src = a.get("source") or {}
        site = normalize_site(urlparse(src.get("url") or url or "").netloc)
        if not (url and title and site):
            continue
        out.append(Evidence("news", src.get("name") or site, site, url, title.strip(),
                            (a.get("description") or "").strip(), _parse_time(a.get("publishedAt"))))
    return out


class GNewsProvider:
    name = "gnews"

    def __init__(self, api_key: str, http=None):
        if http is None:
            import httpx
            http = httpx.AsyncClient(timeout=4.0)
        self._key, self._http = api_key, http

    async def search(self, claim: Claim, lang: str) -> list[Evidence]:
        r = await self._http.get(ENDPOINT, params={
            "q": short_query(claim.query), "lang": lang, "max": 10, "sortby": "relevance", "apikey": self._key})
        if r.status_code >= 400:
            log.warning("gnews HTTP %s: %s", r.status_code, r.text[:200])
        r.raise_for_status()
        data = r.json()
        items = parse_gnews(data)
        if not items:
            log.warning("gnews returned 0 items for %r (totalArticles=%s, errors=%s)",
                        short_query(claim.query), data.get("totalArticles"), data.get("errors"))
        return items
