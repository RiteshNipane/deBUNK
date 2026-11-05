import asyncio
import hmac
import logging
import os
import time
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header, HTTPException, Response
from pydantic import BaseModel

from .cache import TTLCache, text_key
from .claims import Claim, ClaimExtractor, LLMClaimExtractor, FallbackExtractor, HeuristicClaimExtractor
from .llm import make_llm_chain
from .config import Settings
from .quota import QuotaStore, clean_device_id
from .factcheck import GoogleFactCheckClient
from .models import ClaimResult, VerifyRequest, VerifyResponse
from .providers.base import EvidenceProvider
from .providers.factcheck_provider import FactCheckProvider
from .providers.gdelt import GdeltProvider
from .providers.gnews import GNewsProvider
from .providers.gnewsrss import GoogleNewsRssProvider
from .providers.rss import RssProvider, load_feeds
from .retrieval import Retriever
from .scoring import LLMStanceClassifier, EvidenceScorer, FallbackStanceClassifier, HeuristicStanceClassifier, StanceClassifier
from .verdict import build_verdict, pick_headline, _unverified

log = logging.getLogger("debunk.api")


def default_providers(settings: Settings) -> list[EvidenceProvider]:
    providers: list[EvidenceProvider] = []
    if settings.factcheck_api_key:
        providers.append(FactCheckProvider(GoogleFactCheckClient(settings.factcheck_api_key)))
    if settings.gnews_api_key:
        providers.append(GNewsProvider(settings.gnews_api_key))
    if settings.use_gdelt:
        providers.append(GdeltProvider())
    if settings.use_rss:
        providers.append(RssProvider(load_feeds()))
    if not os.environ.get("DISABLE_GNEWS_RSS"):
        providers.append(GoogleNewsRssProvider())
    return providers


def create_app(
    settings: Settings | None = None,
    extractor: ClaimExtractor | None = None,
    providers: list[EvidenceProvider] | None = None,
    stance_classifier: StanceClassifier | None = None,
    now=lambda: datetime.now(timezone.utc),
) -> FastAPI:
    """Everything external is injectable so tests (and later phases) never need network access."""
    settings = settings or Settings.from_env()
    llm = make_llm_chain(settings)
    if extractor is None:
        heuristic = HeuristicClaimExtractor()
        extractor = (
            FallbackExtractor(LLMClaimExtractor(llm), heuristic,
                              settings.extract_timeout_s)
            if llm else heuristic
        )
    if stance_classifier is None:
        heuristic_s = HeuristicStanceClassifier()
        stance_classifier = (
            FallbackStanceClassifier(LLMStanceClassifier(llm),
                                     heuristic_s, settings.stance_timeout_s)
            if llm else heuristic_s
        )
    if providers is None:
        providers = default_providers(settings)
    retriever = Retriever(providers, settings.provider_timeout_s)
    scorer = EvidenceScorer(stance_classifier)

    quota = QuotaStore(
        settings.quota_db_path, settings.quota_reset_offset_min,
        frozenset(x.strip() for x in settings.premium_device_ids.split(",") if x.strip()),
    )

    response_cache: TTLCache[VerifyResponse] = TTLCache(settings.cache_ttl_s)
    claim_cache: TTLCache[ClaimResult] = TTLCache(settings.cache_ttl_s)

    app = FastAPI(title="deBUNK API", version="0.3.0",
                  description="Verifies the headline-like text the deBUNK Android app extracts from the screen.")

    def require_key(x_api_key: str | None = Header(default=None)) -> None:
        if settings.backend_api_key and not (x_api_key and hmac.compare_digest(x_api_key, settings.backend_api_key)):
            raise HTTPException(status_code=401, detail="invalid or missing X-API-Key")

    async def check_claim(claim: Claim, lang: str) -> tuple[ClaimResult, bool, bool]:
        """Returns (result, cacheable, degraded)."""
        key = text_key(claim.text)
        if (hit := claim_cache.get(key)) is not None:
            return hit, True, False
        retrieval = await retriever.retrieve(claim, lang)
        if providers and not retrieval.ok:  # every source down: say so, and don't cache it
            return _unverified(claim.text, "Could not reach news sources right now. Try again shortly."), False, True
        degraded = llm is None or bool(retrieval.failed)
        log.info("claim=%s evidence_per_provider=%s failed=%s llm=%s", text_key(claim.text), retrieval.counts,
                 retrieval.failed, "on" if llm else "OFF")
        scored = await scorer.score(claim, retrieval.evidence)
        result = build_verdict(claim.text, scored, now())
        ttl = settings.developing_ttl_s if (result.developing or result.verdict.value == "UNVERIFIED") else None
        claim_cache.set(key, result, ttl)
        return result, True, degraded

    async def run(req: VerifyRequest) -> tuple[VerifyResponse, bool]:
        claims = await extractor.extract(req.text, req.lang)
        if not claims:
            return VerifyResponse(claim=req.text[:200], verdict="UNVERIFIED", confidence=0,
                                  summary="No checkable factual claim found in this text.", sources=[]), False
        pairs = await asyncio.gather(*(check_claim(c, req.lang) for c in claims))
        results = [r for r, _, _ in pairs]
        head = pick_headline(results)
        resp = VerifyResponse(claim=head.claim, verdict=head.verdict, confidence=head.confidence, summary=head.summary,
                              sources=head.sources, developing=head.developing, claims=results,
                              degraded=any(d for _, _, d in pairs))
        return resp, all(ok for _, ok, _ in pairs)

    @app.get("/healthz")
    async def healthz() -> dict:
        return {"ok": True, "providers": [p.name for p in providers]}

    @app.post("/v1/debug", dependencies=[Depends(require_key)])
    async def debug(req: VerifyRequest) -> dict:
        """Shows which sources answered and how many items each returned. Use it to find a dead key or feed."""
        out: dict = {"llm": "on" if llm else "OFF (heuristic mode)", "claims": []}
        if llm:  # raw model reply, so a bad model name / odd output format is visible
            from .claims import SYSTEM_PROMPT as _P
            try:
                raw = await llm.complete(_P, f"<screen_text>\n{req.text}\n</screen_text>", max_tokens=500, timeout=10.0)
                out["llm_raw_reply"] = raw[:600]
            except Exception as exc:  # noqa: BLE001
                out["llm_error"] = f"{type(exc).__name__}: {str(exc)[:300]}"
        claims = await extractor.extract(req.text, req.lang)
        for c in claims:
            r = await retriever.retrieve(c, req.lang)
            scored = await scorer.score(c, r.evidence)
            out["claims"].append({"claim": c.text, "query": c.query, "items_per_provider": r.counts,
                                  "providers_failed": r.failed,
                                  "judged": [{"source": x.evidence.name, "title": x.evidence.title[:90], "stance": x.stance.value,
                                              "relevant": x.relevant} for x in scored[:8]]})
        return out

    def quota_headers(device: str | None, premium: bool, used: int) -> dict[str, str]:
        if not device or settings.free_daily_limit <= 0:
            return {}
        return {"X-Premium": "1" if premium else "0", "X-Quota-Limit": str(settings.free_daily_limit),
                "X-Quota-Used": str(used)}

    @app.post("/v1/verify", response_model=VerifyResponse, dependencies=[Depends(require_key)])
    async def verify(req: VerifyRequest, response: Response, x_device_id: str | None = Header(default=None)) -> VerifyResponse:
        # Never log req.text: only a hash, so screen content doesn't end up in server logs.
        key = text_key(req.text)
        if os.environ.get("DEBUG_LOG_TEXT") == "1":   # dev only: see exactly what the app sends
            log.info("verify input=%r", req.text)
        device = clean_device_id(x_device_id)
        premium, used = False, 0
        if settings.free_daily_limit > 0:
            if device is None:
                raise HTTPException(status_code=400, detail={"error": "device_id_required"})
            premium = quota.is_premium(device)
            used = quota.used(device)
            if not premium and used >= settings.free_daily_limit:
                raise HTTPException(status_code=402, detail={
                    "error": "quota_exceeded", "limit": settings.free_daily_limit, "used": used,
                    "resets_at": quota.resets_at()})
        t0 = time.monotonic()

        def counted(resp: VerifyResponse, counts: bool) -> VerifyResponse:
            nonlocal used
            if device and settings.free_daily_limit > 0 and counts and not premium:
                used = quota.add(device)
            response.headers.update(quota_headers(device, premium, used))
            return resp

        if (hit := response_cache.get(key)) is not None:
            return counted(hit.model_copy(update={"cached": True}), True)
        try:
            resp, cacheable = await asyncio.wait_for(run(req), timeout=settings.request_budget_s)
        except asyncio.TimeoutError:
            log.warning("verify timed out input=%s", key)
            # 504, not a fake "UNVERIFIED 0%": a timeout is a failure, and the app retries instead of showing it as a result.
            raise HTTPException(status_code=504, detail="verification timed out")
        log.info("verify done in %.1fs verdict=%s", time.monotonic() - t0, resp.verdict.value)
        if cacheable:
            unsettled = resp.developing or resp.verdict.value == "UNVERIFIED"
            response_cache.set(key, resp, settings.developing_ttl_s if unsettled else None)
        # Only a real check uses up the allowance: "no claim found" and "sources unreachable" are free.
        return counted(resp, cacheable)

    @app.get("/v1/quota", dependencies=[Depends(require_key)])
    async def get_quota(x_device_id: str | None = Header(default=None)) -> dict:
        device = clean_device_id(x_device_id)
        if device is None:
            raise HTTPException(status_code=400, detail={"error": "device_id_required"})
        return {"limit": settings.free_daily_limit, "used": quota.used(device), "premium": quota.is_premium(device),
                "resets_at": quota.resets_at()}

    class PremiumGrant(BaseModel):
        device_id: str
        premium: bool = True

    @app.post("/v1/admin/premium")
    async def admin_premium(body: PremiumGrant, x_admin_key: str | None = Header(default=None)) -> dict:
        """Grant or revoke premium for a device. Disabled unless ADMIN_KEY is set. Later, a verified Play purchase calls this."""
        if not settings.admin_key or not (x_admin_key and hmac.compare_digest(x_admin_key, settings.admin_key)):
            raise HTTPException(status_code=404, detail="not found")
        device = clean_device_id(body.device_id)
        if device is None:
            raise HTTPException(status_code=400, detail={"error": "device_id_invalid"})
        quota.set_premium(device, body.premium)
        return {"device_id": device, "premium": body.premium}

    return app


def _default_app() -> FastAPI:
    return create_app()


app = _default_app()
