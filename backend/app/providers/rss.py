"""Trusted outlets and official sources via RSS (no API keys). Feeds are listed in app/feeds.json and can be edited.
Each feed is fetched at most once per `ttl_s`, then claims are matched against the cached items by keyword overlap."""
import asyncio
import json
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

from ..claims import Claim, content_tokens
from ..evidence import Evidence
from ..tiers import normalize_site
from urllib.parse import urlparse

DEFAULT_FEEDS = Path(__file__).resolve().parent.parent / "feeds.json"
MIN_MATCH_RATIO = 0.5
MIN_MATCHED_TOKENS = 2
MAX_FEED_BYTES = 3_000_000


@dataclass(frozen=True)
class Feed:
    name: str
    url: str
    kind: str = "news"   # "news" | "official"


def load_feeds(path: Path = DEFAULT_FEEDS) -> list[Feed]:
    return [Feed(f["name"], f["url"], f.get("kind", "news")) for f in json.loads(path.read_text(encoding="utf-8"))]


def _parse_date(s: str | None) -> datetime | None:
    if not s:
        return None
    s = s.strip()
    try:
        d = parsedate_to_datetime(s)           # RSS: RFC 822
    except (TypeError, ValueError):
        try:
            d = datetime.fromisoformat(s.replace("Z", "+00:00"))   # Atom: ISO 8601
        except ValueError:
            return None
    return d.replace(tzinfo=timezone.utc) if d.tzinfo is None else d.astimezone(timezone.utc)


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_feed(xml_text: str, feed: Feed) -> list[Evidence]:
    # stdlib ElementTree expands entities, so refuse any document that declares them (billion-laughs protection).
    head = xml_text[:4000].upper()
    if "<!DOCTYPE" in head or "<!ENTITY" in head:
        return []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    out: list[Evidence] = []
    for item in root.iter():
        if _local(item.tag) not in ("item", "entry"):
            continue
        fields: dict[str, str] = {}
        link = ""
        for ch in item:
            t = _local(ch.tag)
            if t == "link":
                link = (ch.get("href") or ch.text or "").strip() or link
            elif t in ("title", "description", "summary", "pubDate", "published", "updated") and ch.text:
                fields.setdefault(t, ch.text.strip())
        title = fields.get("title", "")
        if not (title and link):
            continue
        site = normalize_site(urlparse(link).netloc)
        snippet = fields.get("description") or fields.get("summary") or ""
        out.append(Evidence(feed.kind, feed.name, site, link, title, _strip_tags(snippet)[:400],
                            _parse_date(fields.get("pubDate") or fields.get("published") or fields.get("updated"))))
    return out


def _strip_tags(s: str) -> str:
    out, depth = [], 0
    for c in s:
        if c == "<":
            depth += 1
        elif c == ">" and depth:
            depth -= 1
        elif not depth:
            out.append(c)
    return " ".join("".join(out).split())


def match_items(items: list[Evidence], query_tokens: list[str]) -> list[Evidence]:
    if len(query_tokens) < MIN_MATCHED_TOKENS:
        return []
    qs = set(query_tokens)
    scored = []
    for e in items:
        hay = set(content_tokens(f"{e.title} {e.snippet}"))
        hit = len(qs & hay)
        if hit >= MIN_MATCHED_TOKENS and hit / len(qs) >= MIN_MATCH_RATIO:
            scored.append((hit / len(qs), e))
    scored.sort(key=lambda t: -t[0])
    return [e for _, e in scored[:10]]


class RssProvider:
    name = "rss"

    def __init__(self, feeds: list[Feed], http=None, ttl_s: float = 600, clock=time.monotonic):
        if http is None:
            import httpx
            http = httpx.AsyncClient(timeout=7.0, follow_redirects=True,
                                     headers={"User-Agent": "deBUNK/0.3 (+fact-check research)"})
        self._feeds, self._http, self._ttl, self._clock = feeds, http, ttl_s, clock
        self._cache: dict[str, tuple[float, list[Evidence]]] = {}

    async def _items(self, feed: Feed) -> list[Evidence]:
        hit = self._cache.get(feed.url)
        if hit and hit[0] > self._clock():
            return hit[1]
        try:
            r = await self._http.get(feed.url)
            r.raise_for_status()
            text = r.text if len(r.content) <= MAX_FEED_BYTES else ""
            items = parse_feed(text, feed)
        except Exception:  # noqa: BLE001 - one dead feed must not break the others
            items = hit[1] if hit else []   # serve stale items rather than nothing
        self._cache[feed.url] = (self._clock() + self._ttl, items)
        return items

    async def search(self, claim: Claim, lang: str) -> list[Evidence]:
        lists = await asyncio.gather(*(self._items(f) for f in self._feeds))
        tokens = content_tokens(claim.query)
        out: list[Evidence] = []
        for items in lists:
            out.extend(match_items(items, tokens))
        return out
