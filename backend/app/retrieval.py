import asyncio
import logging
from dataclasses import dataclass

from .claims import Claim
from .evidence import Evidence
from .providers.base import EvidenceProvider

log = logging.getLogger("debunk.retrieval")


@dataclass
class Retrieval:
    evidence: list[Evidence]
    ok: list[str]       # providers that answered (even with zero results)
    failed: list[str]   # providers that errored or timed out
    counts: dict[str, int] | None = None   # items each provider returned (for diagnostics)


class Retriever:
    """Runs all providers in parallel. Each has its own time limit; a failing provider is skipped, never fatal."""

    def __init__(self, providers: list[EvidenceProvider], per_provider_timeout_s: float = 3.0):
        self._providers, self._timeout = providers, per_provider_timeout_s

    async def retrieve(self, claim: Claim, lang: str) -> Retrieval:
        async def one(p: EvidenceProvider):
            try:
                return p.name, await asyncio.wait_for(p.search(claim, lang), getattr(p, "timeout_s", self._timeout)), None
            except Exception as exc:  # noqa: BLE001
                status = getattr(getattr(exc, "response", None), "status_code", "")
                log.warning("provider %s failed (%s %s)", p.name, type(exc).__name__, status)
                return p.name, [], exc

        results = await asyncio.gather(*(one(p) for p in self._providers))
        seen: set[str] = set()
        evidence: list[Evidence] = []
        for _, items, _err in results:
            for e in items:
                if e.url not in seen:
                    seen.add(e.url)
                    evidence.append(e)
        return Retrieval(evidence, [n for n, _, err in results if err is None], [n for n, _, err in results if err is not None],
                         {n: len(items) for n, items, _ in results})
