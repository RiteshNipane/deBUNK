from typing import Protocol

from ..claims import Claim
from ..evidence import Evidence


class EvidenceProvider(Protocol):
    name: str

    async def search(self, claim: Claim, lang: str) -> list[Evidence]: ...


def short_query(query: str, n: int = 5) -> str:
    """News search engines AND all terms: 8 keywords usually return nothing. Use the first few."""
    return " ".join(query.split()[:n])
