import asyncio

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")
from fastapi.testclient import TestClient  # noqa: E402

from app.claims import Claim  # noqa: E402
from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.models import Stance  # noqa: E402
from tests.helpers import C, NOW, ev  # noqa: E402

CLAIM = "The government announced free electricity for all farmers from next month"


class FakeExtractor:
    def __init__(self, claims):
        self.claims, self.calls = claims, 0

    async def extract(self, text, lang):
        self.calls += 1
        return self.claims


class FakeProvider:
    def __init__(self, name, items=None, fail=False):
        self.name, self.items, self.fail, self.calls = name, items or [], fail, 0

    async def search(self, claim, lang):
        self.calls += 1
        if self.fail:
            raise RuntimeError("down")
        return self.items


class FakeStance:
    """Deterministic: titles containing 'deny' contradict, everything else supports."""
    async def classify(self, claim, items):
        return [((Stance.CONTRADICTS if "deny" in e.title.lower() else Stance.SUPPORTS), True) for e in items]


def client(claims, providers, **settings):
    app = create_app(Settings(**settings), extractor=FakeExtractor(claims), providers=providers,
                     stance_classifier=FakeStance(), now=lambda: NOW)
    return TestClient(app)


TRUE_EVIDENCE = [ev("reuters.com", "Government announces free electricity for farmers"),
                 ev("thehindu.com", "Free power for all farmers from next month, says Centre")]
FALSE_EVIDENCE = [ev("altnews.in", "Viral claim of free electricity is fake", kind="factcheck", rating="False"),
                  ev("boomlive.in", "No, free electricity for farmers not announced", kind="factcheck", rating="Fake")]
C1 = [Claim(CLAIM, "government free electricity farmers")]


def test_true_path_matches_contract():
    body = client(C1, [FakeProvider("news", TRUE_EVIDENCE)]).post("/v1/verify", json={"text": "BREAKING " + CLAIM}).json()
    assert body["verdict"] == "LIKELY_TRUE" and 0 <= body["confidence"] <= 100 and len(body["sources"]) == 2
    assert {"name", "url", "stance", "credibility_tier"} <= set(body["sources"][0])
    assert body["claim"] == CLAIM and len(body["summary"]) <= 200


def test_false_path_from_factchecks():
    body = client(C1, [FakeProvider("fc", FALSE_EVIDENCE)]).post("/v1/verify", json={"text": CLAIM}).json()
    assert body["verdict"] == "LIKELY_FALSE" and "Fact-checkers" in body["summary"]


def test_never_a_verdict_without_two_sources():
    body = client(C1, [FakeProvider("news", TRUE_EVIDENCE[:1])]).post("/v1/verify", json={"text": CLAIM}).json()
    assert body["verdict"] == "UNVERIFIED" and body["developing"] is True


def test_no_claims_found_calls_no_provider():
    p = FakeProvider("news", TRUE_EVIDENCE)
    body = client([], [p]).post("/v1/verify", json={"text": "Subscribe to our newsletter today"}).json()
    assert body["verdict"] == "UNVERIFIED" and body["sources"] == [] and p.calls == 0


def test_one_provider_down_still_works_all_down_degrades_uncached():
    body = client(C1, [FakeProvider("a", fail=True), FakeProvider("b", TRUE_EVIDENCE)]).post("/v1/verify", json={"text": CLAIM}).json()
    assert body["verdict"] == "LIKELY_TRUE"
    down = FakeProvider("a", fail=True)
    c = client(C1, [down])
    r = c.post("/v1/verify", json={"text": CLAIM}); c.post("/v1/verify", json={"text": CLAIM})
    assert r.status_code == 504 and down.calls == 2   # not cached


def test_identical_request_served_from_cache():
    p = FakeProvider("news", TRUE_EVIDENCE)
    c = client(C1, [p])
    first = c.post("/v1/verify", json={"text": CLAIM}).json()
    second = c.post("/v1/verify", json={"text": CLAIM.upper() + "!!"}).json()
    assert first["cached"] is False and second["cached"] is True and p.calls == 1


def test_headline_is_most_alarming_of_several_claims():
    claims = [Claim("Claim one is true per many sources", "claim one true sources"), Claim(CLAIM, "free electricity farmers")]

    class Mixed:
        name = "m"
        async def search(self, claim, lang):
            return TRUE_EVIDENCE if "one" in claim.text else FALSE_EVIDENCE
    body = client(claims, [Mixed()]).post("/v1/verify", json={"text": "x"}).json()
    assert body["verdict"] == "LIKELY_FALSE" and len(body["claims"]) == 2


def test_validation_auth_health():
    c = client(C1, [FakeProvider("news", TRUE_EVIDENCE)], backend_api_key="s3cret")
    assert c.post("/v1/verify", json={"text": CLAIM}).status_code == 401
    assert c.post("/v1/verify", json={"text": CLAIM}, headers={"X-API-Key": "s3cret"}).status_code == 200
    assert c.post("/v1/verify", json={"text": "x" * 601}, headers={"X-API-Key": "s3cret"}).status_code == 422
    assert c.post("/v1/verify", json={"text": CLAIM, "lang": "fr"}, headers={"X-API-Key": "s3cret"}).status_code == 422
    assert c.get("/healthz").json() == {"ok": True, "providers": ["news"]}


def test_text_is_not_logged(caplog):
    caplog.set_level("DEBUG")
    client(C1, [FakeProvider("a", fail=True)]).post("/v1/verify", json={"text": CLAIM})
    assert "free electricity" not in caplog.text


def test_timeout_is_a_failure_not_a_verdict():
    class Slow:
        async def extract(self, text, lang):
            await asyncio.sleep(1)
            return C1
    app = create_app(Settings(request_budget_s=0.05), extractor=Slow(), providers=[], stance_classifier=FakeStance())
    r = TestClient(app).post("/v1/verify", json={"text": CLAIM})
    assert r.status_code == 504


# ---- daily quota (free plan) ----
def _quota_client(limit=2, **extra):
    app = create_app(Settings(free_daily_limit=limit, **extra), extractor=FakeExtractor([Claim("Fire at Delhi market kills 12 people", "delhi market fire")]),
                     providers=[], stance_classifier=FakeStance())
    return TestClient(app)


DEV = "a" * 32


def test_quota_blocks_after_limit_and_reports_usage():
    c = _quota_client(limit=2)
    for n in (1, 2):
        r = c.post("/v1/verify", json={"text": f"Fire at Delhi market kills 1{n} people today"}, headers={"X-Device-Id": DEV})
        assert r.status_code == 200 and r.headers["X-Quota-Used"] == str(n) and r.headers["X-Quota-Limit"] == "2"
    r = c.post("/v1/verify", json={"text": "Fire at Delhi market kills 13 people today"}, headers={"X-Device-Id": DEV})
    assert r.status_code == 402 and r.json()["detail"]["error"] == "quota_exceeded"
    other = c.post("/v1/verify", json={"text": "Fire at Delhi market kills 14 people today"}, headers={"X-Device-Id": "b" * 32})
    assert other.status_code == 200   # another device has its own allowance


def test_quota_requires_device_id_and_premium_is_unlimited():
    c = _quota_client(limit=1, premium_device_ids=DEV)
    assert c.post("/v1/verify", json={"text": "Fire at Delhi market kills 12 people"}).status_code == 400
    for i in range(3):
        r = c.post("/v1/verify", json={"text": f"Fire at Delhi market kills 2{i} people"}, headers={"X-Device-Id": DEV})
        assert r.status_code == 200 and r.headers["X-Premium"] == "1"


def test_admin_premium_grant():
    c = _quota_client(limit=1, admin_key="adm")
    assert c.post("/v1/admin/premium", json={"device_id": DEV}).status_code == 404
    assert c.post("/v1/admin/premium", json={"device_id": DEV}, headers={"X-Admin-Key": "adm"}).status_code == 200
    assert c.get("/v1/quota", headers={"X-Device-Id": DEV}).json()["premium"] is True
