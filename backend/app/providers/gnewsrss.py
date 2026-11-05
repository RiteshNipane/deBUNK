"""Google News RSS search: free, no API key, query-based, many outlets. Complements GNews/RSS feeds."""
import logging
import re
import xml.etree.ElementTree as ET
from urllib.parse import quote_plus, urlparse

from ..claims import Claim
from ..evidence import Evidence
from ..tiers import normalize_site
from .base import short_query
from .rss import _parse_date, _strip_tags

log = logging.getLogger("debunk.gnewsrss")
MAX_ITEMS = 10
REGIONS = {"en": ("en-US", "US", "US:en")}


def parse_gnews_rss(xml_text: str) -> list[Evidence]:
    head = xml_text[:4000].upper()
    if "<!DOCTYPE" in head or "<!ENTITY" in head:
        return []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    out: list[Evidence] = []
    for item in root.iter("item"):
        title = (item.findtext("title") or "").strip()
        link = (item.findtext("link") or "").strip()
        src = item.find("source")
        name = (src.text or "").strip() if src is not None and src.text else ""
        site = normalize_site(urlparse((src.get("url") if src is not None else "") or "").netloc)
        if name and title.endswith(" - " + name):
            title = title[: -len(name) - 3].strip()
        if not (title and link and site):
            continue
        desc = _strip_tags(item.findtext("description") or "")
        desc = re.sub(r"\s+", " ", desc).strip()
        if desc.startswith(title):
            desc = ""
        out.append(Evidence("news", name or site, site, link, title, desc[:300], _parse_date(item.findtext("pubDate"))))
        if len(out) >= MAX_ITEMS:
            break
    return out


class GoogleNewsRssProvider:
    name = "gnewsrss"
    timeout_s = 8.0

    def __init__(self, http=None):
        if http is None:
            import httpx
            http = httpx.AsyncClient(timeout=7.0, follow_redirects=True, headers={"User-Agent": "deBUNK/1.0"})
        self._http = http

    async def search(self, claim: Claim, lang: str) -> list[Evidence]:
        hl, gl, ceid = REGIONS.get(lang, ("en-US", "US", "US:en"))
        url = f"https://news.google.com/rss/search?q={quote_plus(short_query(claim.query))}&hl={hl}&gl={gl}&ceid={ceid}"
        r = await self._http.get(url)
        if r.status_code >= 400:
            log.warning("google news rss HTTP %s", r.status_code)
        r.raise_for_status()
        items = parse_gnews_rss(r.text)
        if not items:
            log.warning("google news rss returned 0 items for %r", short_query(claim.query))
        return items
