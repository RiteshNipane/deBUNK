"""Provider-neutral LLM access. The rest of the backend only calls `LLM.complete(system, user, ...)`.

Providers:
  anthropic  - Claude via the Anthropic SDK.
  openai     - ANY OpenAI-compatible /chat/completions endpoint, called with plain httpx (no extra SDK):
               Google Gemini, Groq, OpenRouter, Together, Mistral, OpenAI, or a local Ollama / LM Studio server.
Presets (LLM_PROVIDER=gemini|groq|openrouter|ollama) just fill in base URL and a default model.
"""
import logging
from typing import Protocol

log = logging.getLogger("debunk.llm")

PRESETS: dict[str, tuple[str, str]] = {
    # name: (base_url, default_model). Model names change often: override with LLM_MODEL if one is retired.
    "gemini": ("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
    "groq": ("https://api.groq.com/openai/v1", "openai/gpt-oss-20b,openai/gpt-oss-120b"),
    "openrouter": ("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free"),
    "ollama": ("http://localhost:11434/v1", "llama3.1"),
    "openai": ("https://api.openai.com/v1", "gpt-4o-mini"),
}


class LLM(Protocol):
    async def complete(self, system: str, user: str, max_tokens: int = 500, timeout: float = 6.0) -> str: ...


class AnthropicLLM:
    def __init__(self, api_key: str, model: str):
        import anthropic  # lazy: the SDK is only needed if this provider is chosen

        self._client = anthropic.AsyncAnthropic(api_key=api_key, max_retries=0)
        self._model = model

    async def complete(self, system: str, user: str, max_tokens: int = 500, timeout: float = 6.0) -> str:
        msg = await self._client.messages.create(
            model=self._model, max_tokens=max_tokens, temperature=0, system=system, timeout=timeout,
            messages=[{"role": "user", "content": user}],
        )
        return "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")


class OpenAICompatLLM:
    """`model` may be a comma-separated list, tried in order.

    Per-model memory (this process only): a model that answers 404 (retired / not available to this key) is skipped for
    an hour; 429 (rate limit) for 60 s; 503 (overloaded) for 20 s. If every model is cooling down they are all tried
    anyway. All attempts share ONE deadline (`timeout`), so a row of failing models can no longer eat the stage budget.
    """

    COOLDOWN_S = {404: 3600.0, 400: 600.0, 401: 600.0, 403: 600.0, 429: 60.0}
    DEFAULT_COOLDOWN_S = 20.0   # 5xx and anything else

    def __init__(self, base_url: str, api_key: str | None, model: str):
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._key = api_key
        self._models = [m.strip().removeprefix("models/") for m in model.split(",") if m.strip()]
        if not self._models:
            raise ValueError("no model configured")
        self._bad_until: dict[str, float] = {}

    def _order(self, now: float) -> list[str]:
        ok = [m for m in self._models if self._bad_until.get(m, 0.0) <= now]
        return ok or list(self._models)

    async def complete(self, system: str, user: str, max_tokens: int = 500, timeout: float = 6.0) -> str:
        import time

        import httpx

        headers = {"Content-Type": "application/json"}
        if self._key:
            headers["Authorization"] = f"Bearer {self._key}"
        start = time.monotonic()
        last: Exception | None = None
        for model in self._order(start):
            remaining = timeout - (time.monotonic() - start)
            if remaining <= 0.3:
                break
            body = {
                "model": model, "max_tokens": max_tokens, "temperature": 0,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            }
            try:
                async with httpx.AsyncClient(timeout=remaining) as c:
                    r = await c.post(self._url, headers=headers, json=body)
            except (httpx.TimeoutException, httpx.TransportError) as exc:
                last = exc
                break  # network trouble or a slow provider: another model of the same provider won't be faster
            if r.status_code in (400, 401, 403, 404, 429) or r.status_code >= 500:
                cd = self.COOLDOWN_S.get(r.status_code, self.DEFAULT_COOLDOWN_S)
                self._bad_until[model] = time.monotonic() + cd
                log.warning("LLM model %s answered HTTP %s%s; skipping it for %.0f s", model, r.status_code,
                            " (API key rejected)" if r.status_code in (401, 403) else "", cd)
                last = RuntimeError(f"{model}: HTTP {r.status_code}")
                continue
            r.raise_for_status()
            text = extract_openai_text(r.json())
            if not text.strip():
                last = RuntimeError(f"{model}: empty reply")
                continue
            return text
        raise last or RuntimeError("no LLM model answered in time")


class ChainLLM:
    """Primary provider first; if it fails or times out, the backup provider gets the remaining time."""

    def __init__(self, llms: list):
        self._llms = llms

    async def complete(self, system: str, user: str, max_tokens: int = 500, timeout: float = 6.0) -> str:
        import time

        start = time.monotonic()
        last: Exception | None = None
        for i, llm in enumerate(self._llms):
            remaining = timeout - (time.monotonic() - start)
            if remaining <= 0.3:
                break
            # the primary may use at most 60 % of the time so a backup always has a real chance
            budget = remaining * 0.6 if i < len(self._llms) - 1 else remaining
            try:
                return await llm.complete(system, user, max_tokens=max_tokens, timeout=budget)
            except Exception as exc:  # noqa: BLE001
                log.warning("LLM provider #%d failed (%s)", i + 1, type(exc).__name__)
                last = exc
        raise last or RuntimeError("no LLM provider answered")


def extract_openai_text(data: dict) -> str:
    """Pure helper (unit-tested): pull the assistant text out of a chat-completions response."""
    try:
        content = data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ValueError("unexpected LLM response shape") from exc
    return content if isinstance(content, str) else ""


def make_llm(provider: str | None, api_key: str | None, model: str | None, base_url: str | None) -> LLM | None:
    """Returns None when no LLM is configured (the backend then runs in degraded, heuristic mode)."""
    p = (provider or "").strip().lower()
    if not p:  # auto: a key alone picks the obvious provider
        p = "anthropic" if api_key and api_key.startswith("sk-ant-") else ("openai" if base_url else "")
        if not p:
            return None
    if p == "anthropic":
        if not api_key:
            return None
        return AnthropicLLM(api_key, model or "claude-haiku-5-5")
    if p in PRESETS or p == "openai-compatible":
        preset_url, preset_model = PRESETS.get(p, ("", ""))
        url = base_url or preset_url
        if not url:
            log.warning("LLM_PROVIDER=%s needs LLM_BASE_URL", p)
            return None
        if not api_key and p not in ("ollama", "openai-compatible"):
            return None  # hosted providers need a key; local servers don't
        return OpenAICompatLLM(url, api_key, model or preset_model)
    log.warning("unknown LLM_PROVIDER %r; running without an LLM", p)
    return None


def make_llm_chain(settings) -> LLM | None:
    """Primary LLM_* provider, plus an optional backup LLM2_* provider (recommended: free tiers are often busy)."""
    llms = [x for x in (
        make_llm(settings.llm_provider, settings.llm_api_key, settings.llm_model, settings.llm_base_url),
        make_llm(settings.llm2_provider, settings.llm2_api_key, settings.llm2_model, settings.llm2_base_url),
    ) if x]
    if not llms:
        return None
    return llms[0] if len(llms) == 1 else ChainLLM(llms)
