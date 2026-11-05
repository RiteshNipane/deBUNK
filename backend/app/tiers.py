"""Source credibility tiers. 1 = fact-checkers, wires, public broadcasters and official bodies;
2 = major national / international outlets; 3 = everything else (counts, but weighs little)."""

_TIER1 = (
    # fact-checkers
    "snopes.com", "factcheck.org", "politifact.com", "fullfact.org", "leadstories.com", "factcheck.afp.com",
    "boomlive.in", "altnews.in", "factly.in", "vishvasnews.com", "newschecker.in", "thequint.com",
    # wires / public broadcasters / quality dailies
    "reuters.com", "apnews.com", "afp.com", "bbc.com", "bbc.co.uk", "ptinews.com", "thehindu.com", "aninews.in",
    # official
    "pib.gov.in", "who.int", "un.org", "worldbank.org", "imf.org", "rbi.org.in", "eci.gov.in", "mea.gov.in",
)
_TIER1_SUFFIXES = (".gov.in", ".nic.in", ".gov", ".gov.uk", ".europa.eu")
_TIER2 = (
    "ndtv.com", "hindustantimes.com", "indiatoday.in", "timesofindia.indiatimes.com", "indianexpress.com",
    "theprint.in", "scroll.in", "livemint.com", "business-standard.com", "economictimes.indiatimes.com",
    "deccanherald.com", "telegraphindia.com", "thewire.in", "news18.com", "firstpost.com", "wionews.com",
    "aljazeera.com", "cnn.com", "nytimes.com", "theguardian.com", "washingtonpost.com", "bloomberg.com",
    "ft.com", "dw.com", "france24.com", "npr.org", "cbsnews.com", "nbcnews.com", "abcnews.go.com",
    "timesnownews.com", "thehindubusinessline.com", "outlookindia.com", "tribuneindia.com", "mid-day.com",
    "loksatta.com", "lokmat.com", "maharashtratimes.com", "amarujala.com", "jagran.com", "bhaskar.com",
)


def _match(site: str, domains: tuple[str, ...]) -> bool:
    return any(site == d or site.endswith("." + d) for d in domains)


def normalize_site(site: str) -> str:
    return site.lower().strip().removeprefix("www.")


def credibility_tier(site: str) -> int:
    site = normalize_site(site)
    if _match(site, _TIER1) or site.endswith(_TIER1_SUFFIXES):
        return 1
    if _match(site, _TIER2):
        return 2
    return 3


def is_factchecker(site: str) -> bool:
    return _match(normalize_site(site), (
        "snopes.com", "factcheck.org", "politifact.com", "fullfact.org", "leadstories.com", "factcheck.afp.com",
        "boomlive.in", "altnews.in", "factly.in", "vishvasnews.com", "newschecker.in"))
