"""Google Fact Check Tools API client (existing fact-checks by publishers such as Alt News, BOOM, PIB Fact Check)."""
import logging
from dataclasses import dataclass
from typing import Protocol
from urllib.parse import urlparse

log = logging.getLogger("debunk.factcheck")
ENDPOINT = "https://factchecktools.googleapis.com/v1alpha1/claims:search"


@dataclass(frozen=True)
class FactCheckReview:
    publisher: str
    site: str       # publisher domain, used for de-duplication and credibility tier
    url: str
    title: str
    rating: str     # the publisher's own wording: "False", "Pants on Fire", "Misleading" ...
    date: str | None = None  # ISO reviewDate when the API provides it
    date: str | None = None  # reviewDate, ISO-8601 when the publisher supplies it


class FactCheckClient(Protocol):
    async def search(self, claim: str, lang: str) -> list[FactCheckReview]: ...


class GoogleFactCheckClient:
    def __init__(self, api_key: str, http=None):
        import httpx  # lazy: pure logic (and tests) import this module without httpx installed

        self._key = api_key
        self._http = http or httpx.AsyncClient(timeout=5.0)

    async def search(self, claim: str, lang: str) -> list[FactCheckReview]:
        resp = await self._http.get(
            ENDPOINT, params={"query": claim, "languageCode": lang, "pageSize": 10, "key": self._key}
        )
        resp.raise_for_status()
        return parse_reviews(resp.json())


class NullFactCheckClient:
    async def search(self, claim: str, lang: str) -> list[FactCheckReview]:
        return []


def parse_reviews(payload: dict) -> list[FactCheckReview]:
    out: list[FactCheckReview] = []
    for c in payload.get("claims") or []:
        for r in c.get("claimReview") or []:
            pub = r.get("publisher") or {}
            url = r.get("url") or ""
            site = (pub.get("site") or urlparse(url).netloc or "").lower().removeprefix("www.")
            rating = (r.get("textualRating") or "").strip()
            if not (url and site and rating):
                continue
            out.append(FactCheckReview(pub.get("name") or site, site, url, r.get("title") or "", rating, r.get("reviewDate")))
    return out
