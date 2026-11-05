# deBUNK

An Android app that fact-checks a claim without leaving the app you are reading. A small dot floats over the screen.
Drag it onto a headline, a social post or a chat message, let go, and a box shows a verdict (likely true, likely false,
misleading or unverified), a confidence score and the sources behind it.

## How it works

```
 phone                                   backend (FastAPI)
 ─────                                   ─────────────────
 AccessibilityService                    1. extract one checkable claim + search query (LLM, heuristic fallback)
   reads the line under the dot   ──▶    2. gather evidence in parallel:
 Compose overlay: dot, line, box           Google Fact Check API · Google News RSS search · trusted-outlet RSS feeds
 details sheet with every source           3. judge each article vs the claim: relevant? supports / contradicts / neutral (LLM)
                                    ◀──  4. deterministic scoring: source tier, recency, independence, wire-copy collapse
        verdict + sources                 5. cache 6 h, per-device daily quota
```

Design choices worth reading:

- **The LLM never decides the verdict.** It extracts the claim and labels each article's stance. The verdict comes from
  plain, tested rules in `backend/app/verdict.py`: at least two independent sources are needed, wire copy carried by
  several outlets counts as one voice, old articles are discounted, and missing coverage is never read as "false".
- **Fails soft.** Every LLM call has a time budget and a heuristic fallback. Models are tried in order with cooldowns
  on rate limits (429/503), and a backup provider can take over. A timeout returns an error the app shows, not a fake result.
- **Privacy by construction.** Only the single line under the dot is sent, only when the user drops the dot. Banking,
  payment, password and keyboard apps are never read. The server logs a hash, not the text. Untrusted text is delimited so
  it cannot instruct the model.
- **Server-side free-plan limit.** 10 checks per device per day, counted from a salted hash of `ANDROID_ID`, enforced by the
  backend so clearing app data does not reset it.

## Tech stack

| Part | Tech |
|---|---|
| Android app (`app/`) | Kotlin, Jetpack Compose, Material 3, AccessibilityService, overlay windows, foreground service, coroutines |
| Shared logic (`core/`) | Pure Kotlin/JVM: scan coordinator, app policy, throttling, overlay geometry, domain models (JUnit tests) |
| Backend (`backend/`) | Python, FastAPI, httpx, pydantic, SQLite (quota), pytest |
| LLM | Any OpenAI-compatible API (Groq, Gemini, OpenRouter, Ollama) or Anthropic; default `gpt-oss-20b` on Groq |
| Evidence | Google Fact Check Tools API, Google News RSS, RSS feeds of established outlets |
| Deploy | Docker, `render.yaml` for Render |

## Run it

**Backend**

```bash
cd backend
python -m venv .venv && source .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env                                    # then fill in your keys
uvicorn app.main:app --host 0.0.0.0 --port 8000
pytest                                                  # tests need no network or keys
```

Useful endpoints: `GET /healthz`, `POST /v1/verify`, `POST /v1/debug` (shows which sources answered and how each article
was judged), `GET /v1/quota`. Send the key from `BACKEND_API_KEY` as `X-API-Key`.

**Android app**

1. Copy `local.properties.example` to `local.properties` and set `debunk.backendUrl` and `debunk.backendKey`.
   Leave the URL empty to run offline with simulated verdicts.
2. Open the project in Android Studio and run on a device (Android 10+).
3. Follow the in-app setup: accept the disclosure, allow display over other apps, enable the accessibility service,
   allow notifications, then start.

## Evaluation

Not done yet. A labelled set of claims with accuracy, wrongly-"false" rate, latency and cost per check, compared across
the heuristic and different models, is the next step. Until then, treat accuracy claims with caution.

## Known limitations

- Verdicts come from news coverage and fact-checks, so very new or very local stories often stay "unverified".
- A small LLM judges stance from headlines and snippets, not full articles, so it can misjudge nuance.
- English and Hindi are the best-supported languages. Source lists are India and US/UK weighted.
- Payments are not connected: the premium screen is a placeholder for Google Play Billing.
- Free LLM tiers are for development. Check a provider's terms before any commercial use.

More detail, including the original phase notes, is in [`docs/DEVELOPMENT_NOTES.md`](docs/DEVELOPMENT_NOTES.md).
