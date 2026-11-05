"""GDELT DOC 2.0 article search: free, no key, global coverage incl. Hindi/Marathi.
Note: GDELT asks for roughly one request per 5 s per IP and answers 429 beyond that. Fine for a beta;
for real traffic add GNEWS_API_KEY (or another paid news API) and set DISABLE_GDELT=1."""
from datetime import datetime, timezone

from ..claims import Claim
from ..evidence import Evidence
from ..tiers import normalize_site
from .base import short_query

ENDPOINT = "https://api.gdeltproject.org/api/v2/doc/doc"
_LANG = {"en": "english", "hi": "hindi", "mr": "marathi"}


def parse_gdelt(payload: dict) -> list[Evidence]:
    out: list[Evidence] = []
    for a in payload.get("articles") or []:
        url, title, domain = a.get("url"), a.get("title"), a.get("domain")
        if not (url and title and domain):
            continue
        published = None
        if seen := a.get("seendate"):
            try:
                published = datetime.strptime(seen, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
            except ValueError:
                pass
        site = normalize_site(domain)
        out.append(Evidence("news", site, site, url, title.strip(), published=published))
    return out


class GdeltProvider:
    name = "gdelt"
    timeout_s = 2.0   # GDELT is slow and optional: never let it hold up the verdict

    def __init__(self, http=None):
        if http is None:
            import httpx
            http = httpx.AsyncClient(timeout=4.0)
        self._http = http

    async def search(self, claim: Claim, lang: str) -> list[Evidence]:
        q = f"{short_query(claim.query)} sourcelang:{_LANG.get(lang, 'english')}"
        r = await self._http.get(ENDPOINT, params={
            "query": q, "mode": "artlist", "maxrecords": 20, "format": "json", "timespan": "7d", "sort": "datedesc"})
        r.raise_for_status()
        return parse_gdelt(r.json())
