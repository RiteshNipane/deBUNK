from ..claims import Claim
from ..evidence import Evidence
from ..factcheck import FactCheckClient
from ..tiers import normalize_site


class FactCheckProvider:
    """Adapts the Google Fact Check client (P2) to the evidence-provider interface."""
    name = "factcheck"

    def __init__(self, client: FactCheckClient):
        self._client = client

    async def search(self, claim: Claim, lang: str) -> list[Evidence]:
        reviews = await self._client.search(claim.text, lang)
        return [Evidence("factcheck", r.publisher, normalize_site(r.site), r.url, r.title, rating=r.rating) for r in reviews]
