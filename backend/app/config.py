import os
from dataclasses import dataclass
from pathlib import Path


def load_dotenv(path: Path | None = None) -> None:
    """Minimal .env loader (no dependency). Real environment variables win over the file."""
    path = path or Path(__file__).resolve().parent.parent / ".env"
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        return
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k, v = k.strip(), v.split(" #", 1)[0].strip().strip("\"'")
        if k and v and k not in os.environ:
            os.environ[k] = v


@dataclass(frozen=True)
class Settings:
    llm_provider: str | None = None     # anthropic | gemini | groq | openrouter | ollama | openai | openai-compatible
    llm_api_key: str | None = None
    llm_model: str | None = None
    llm_base_url: str | None = None
    llm2_provider: str | None = None    # optional backup LLM, used when the primary fails or is rate-limited
    llm2_api_key: str | None = None
    llm2_model: str | None = None
    llm2_base_url: str | None = None
    factcheck_api_key: str | None = None
    gnews_api_key: str | None = None
    use_gdelt: bool = True
    use_rss: bool = True
    backend_api_key: str | None = None  # shared secret expected in X-API-Key; None = open (dev only)
    cache_ttl_s: int = 6 * 60 * 60      # spec: cache by claim hash for 6 hours
    developing_ttl_s: int = 20 * 60     # unsettled stories are re-checked sooner
    max_text_chars: int = 600
    request_budget_s: float = 18.0      # whole /v1/verify budget; the app waits up to 20 s
    extract_timeout_s: float = 6.0
    provider_timeout_s: float = 8.0
    stance_timeout_s: float = 8.0
    free_daily_limit: int = 0           # scans per device per day on the free plan; 0 = no limit (tests, local dev)
    quota_db_path: str = ":memory:"
    quota_reset_offset_min: int = 0     # shifts the day boundary from UTC (e.g. 330 = India midnight)
    premium_device_ids: str = ""        # comma-separated device ids that are premium (testing)
    admin_key: str | None = None        # enables POST /v1/admin/premium (grant/revoke premium); keep off the app

    @classmethod
    def from_env(cls) -> "Settings":
        load_dotenv()
        e = os.environ.get
        flag = lambda name: e(name, "").strip().lower() in ("1", "true", "yes")  # noqa: E731
        return cls(
            llm_provider=e("LLM_PROVIDER") or ("anthropic" if e("ANTHROPIC_API_KEY") else None),
            llm_api_key=e("LLM_API_KEY") or e("ANTHROPIC_API_KEY") or None,   # ANTHROPIC_API_KEY still works
            llm_model=e("LLM_MODEL") or e("CLAUDE_MODEL") or None,
            llm_base_url=e("LLM_BASE_URL") or None,
            llm2_provider=e("LLM2_PROVIDER") or None,
            llm2_api_key=e("LLM2_API_KEY") or None,
            llm2_model=e("LLM2_MODEL") or None,
            llm2_base_url=e("LLM2_BASE_URL") or None,
            factcheck_api_key=e("FACTCHECK_API_KEY") or None,
            gnews_api_key=e("GNEWS_API_KEY") or None,
            use_gdelt=not flag("DISABLE_GDELT"),
            use_rss=not flag("DISABLE_RSS"),
            backend_api_key=e("BACKEND_API_KEY") or None,
            free_daily_limit=int(e("FREE_DAILY_LIMIT", "10") or 0),
            quota_db_path=e("QUOTA_DB") or "quota.db",
            quota_reset_offset_min=int(e("QUOTA_RESET_OFFSET_MIN", "0") or 0),
            premium_device_ids=e("PREMIUM_DEVICE_IDS", ""),
            admin_key=e("ADMIN_KEY") or None,
        )
