# deBUNK (Phase 4: details sheet on top of the P3 multi-source backend)

Background Android app that spots news on screen and shows a verdict in a floating dot + connector line + message box.
Without a backend URL the app stays fully offline (P1 behaviour, `MockVerifier`). With one configured, only the headline-like sentence is sent to the backend.

## Layout
```
backend/ FastAPI: LLM claim extraction -> Google Fact Check -> verdict rules, 6 h cache, OpenAPI (openapi.json)
core/   Pure Kotlin/JVM (unit-tested on any machine): domain models, NewsDetector, AppPolicy,
        ScanThrottle, ScanCoordinator (detect -> dedupe -> verify -> UI state + timers),
        OverlayGeometry (box/line placement), MockVerifier
app/    Android: AccessibilityService, ScreenTextExtractor, OverlayService (foreground),
        OverlayController (3 WindowManager windows), Compose UI, onboarding (MainActivity)
```
Flow: `AccessibilityService -> (throttle 2s, hash-unchanged skip) -> ScreenTextExtractor -> ScanCoordinator
-> NewsDetector (local) -> Verifier -> OverlayUiState -> OverlayController renders`.

## Setup
1. Android Studio (Koala+), JDK 17, Android SDK 35. Open this folder; Gradle sync.
   (No `gradlew` jar is committed: run `gradle wrapper --gradle-version 8.9` once, or let Studio sync.)
2. Run `app` on a device/emulator with Android 10+ (API 29+).
3. In the app: tick consent -> allow overlay -> enable the deBUNK accessibility service -> (Android 13+) allow notifications -> Start.
4. Tap **Simulate news detection** (cycles all four verdict states), or open any news site/app.

Unit tests (no device needed): `./gradlew :core:test`

## Phase 1 acceptance checklist (on a device)
- [ ] Dot appears on a news screen, pulses blue while verifying, then turns green/red/amber with check/cross/? icon
- [ ] Line draws from dot to box corner, box fades in with label+confidence, 2-line summary, Sources line, Details/Dismiss
- [ ] Tap dot toggles box; tap outside or 8 s collapses it; the app underneath stays touchable and the keyboard is not affected
- [ ] Drag dot, it snaps to the nearest edge and remembers its place; box flips below the dot near the top edge
- [ ] Long-press: Pause / Ignore this app / Settings. Pause hides everything; resume from the notification
- [ ] WhatsApp/banking/password screens never trigger anything
- [ ] Light and dark theme both legible

## Decisions I made (flag any you want changed)
- **`:core` JVM module** split out so logic is testable without Android.
- **Dot lifecycle:** hidden until the first detection; after the news leaves the screen for 15 s it settles to a grey idle dot (not hidden).
- **Details button** is a stub (toast) because the bottom sheet is P4.
- **Foreground service type `specialUse`** (Android 14+). Play requires a declaration for it; P5 adds `mediaProjection` for OCR.
- **English-only triggers/verbs** now; Hindi/Marathi in P5.
- **`isAccessibilityTool` is not set** (see Play policy).

## Play policy (plan for P6)
The Accessibility API is restricted on Google Play to apps whose *primary purpose* is helping users with disabilities, unless
Google approves another use. A fact-checker is likely NOT eligible, so plan for the **sideload APK** as the main channel and
treat a Play listing as a separate policy exercise (declaration form, in-app prominent disclosure as built in onboarding, video).
Also: `SYSTEM_ALERT_WINDOW` and `specialUse` FGS need justification at review.

## Known limits / untested
- Android module was written without an SDK in this environment, so it has **not been compiled or run on a device**; `:core` is compiled and tested.
- Box vertical placement assumes `FLAG_LAYOUT_NO_LIMITS` windows use full-screen coordinates; some OEM skins differ by a few dp (check on first device).
- Apps that render text to canvas/video (reels, images) expose no text to Accessibility; that is the P5 OCR fallback.
- `CoordinatorTest` uses `kotlinx-coroutines-test` (needs Maven); an equivalent flow was verified with a plain-coroutines harness.

## Phase 3: backend (multi-source verification)
```
cd backend
python -m venv .venv && source .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env   # fill in keys, then load them into the environment
uvicorn app.main:app --reload --port 8000
pytest                 # 49 offline tests (scoring rules, parsers, retrievers, pipeline) + API tests
python scripts/export_openapi.py   # regenerate openapi.json from the running app
```
Pipeline (`app/main.py`), each stage under its own time budget (worst case 6.8 s of the 8 s target):
1. **Extract** (LLM, 2.5 s): 1-3 atomic claims + 2-3 search queries each. Heuristic fallback.
2. **Retrieve** in parallel (2.5 s each, failures isolated): Google Fact Check (key), GDELT (keyless, 1 req/5 s), GNews (optional key),
   trusted-outlet RSS (BBC, The Hindu, Indian Express, NDTV, HT, TOI, Al Jazeera, Guardian) and official feeds (PIB, WHO).
3. **Filter** by keyword relevance; fact-checks skip this and are rated by the publisher's own wording.
4. **Classify** news/official items as supports / contradicts / neutral / unrelated (LLM, 1.8 s; strict about numbers, dates, places).
   Prompt: `app/prompts/evidence_classification.md`. LLM access goes through `app/llm.py` (provider-neutral). Heuristic fallback marks the result `degraded`.
5. **Score** (`app/scoring.py`, pure and deterministic): weight = tier x origin x recency; outlets of one owner and syndicated copies
   count once; fewer than 2 independent directional sources => UNVERIFIED; a win needs >= 2 independent sources and >= 65% of weight;
   a fact-check dispute blocks LIKELY_TRUE; news-only "true" is capped at 85, degraded runs at 65. **Silence never counts as "false".**

LLM (any one of): set `LLM_PROVIDER` = `gemini` | `groq` | `openrouter` | `ollama` | `openai` | `openai-compatible` | `anthropic`, plus `LLM_API_KEY` (`LLM_MODEL`, `LLM_BASE_URL` optional).
Keys: `LLM_API_KEY` (or the old `ANTHROPIC_API_KEY`), `FACTCHECK_API_KEY`, optional `GNEWS_API_KEY`, `BACKEND_API_KEY` (see `.env.example`).
Deploy: `render.yaml` or the `Dockerfile`. Point the app at it via `local.properties` (git-ignored):
```
debunk.backendUrl=http://10.0.2.2:8000     # emulator -> your PC. Use https://... for a real deployment
debunk.backendKey=<BACKEND_API_KEY>
```
Smoke test: `curl -s localhost:8000/v1/verify -H 'content-type: application/json' -d '{"text":"BREAKING: <a real headline>"}'`
Look for `"degraded": false` in the response: that means the LLM and the sources all answered.

### P3 status (read this)
- Offline tests pass (49 incl. a mutation check on the "<2 sources" rule). **The FastAPI layer and every live call (Claude, Fact Check,
  GDELT, GNews, RSS) were NOT run here** (no PyPI / API access). Run `pytest` and a few real `curl`s before trusting verdicts.
- RSS feed URLs are best-effort and may be stale: a dead feed is skipped silently. Check the server log at INFO level.
- Only headlines recent enough to be in GDELT (7 days) / feeds / fact-check indexes can be verified; older stories mostly give UNVERIFIED.
- Without an LLM key everything is `degraded`: the keyword classifier cannot catch a changed number or place.
- This build still contains MockVerifier on Android only when `debunk.backendUrl` is empty.

## Phase 4: details sheet
- The box's **Details** button now opens `DetailsActivity`, a bottom sheet over the app you were reading: full claim, why, all claims found
  (if several), and every source with stance (word + symbol), kind (fact-check / news / official), date, tier and a tap-to-open link.
- Shows a "LIMITED CHECK" note when the backend reports `degraded`, and "DEVELOPING STORY" when relevant.
- Only `http(s)` links are opened. The sheet holds claim + verdict only, cleared on close; deBUNK never scans its own screens.
- `HttpVerifier` now reads `kind`, `published`, `title`, `degraded` and `claims` from the P3 response.
- **Not compiled or run** (no Android SDK here). Device check: Details opens over a news app, scrim tap and Close dismiss it, a source link opens the browser.

## Point mode (default)
Drag the dot onto any headline or sentence and let go. deBUNK reads ONLY the text under the dot (accessibility node at the dot's
centre, within 28 dp, at least 3 words), sends it to the backend and shows the verdict. Tap the dot to re-check what is under it.
No background scanning happens in this mode. Password/editable fields and blocked apps are never read. The old auto-detect
behaviour is still in the code: set `prefs.pointMode = false` to restore it (a settings switch comes later).
Not compiled here; check on device: drop on a Google News card, on a web headline in Chrome, and on a plain sentence.

## Branding
`branding/debunk-logo-1024.png` is the source logo. The adaptive launcher icon (red background + white wordmark, themed-icon ready),
notification icon and in-app header image are generated from it under `app/src/main/res`.

## Free plan limit and premium

The server counts checks per device per day (`FREE_DAILY_LIMIT`, default 10; `0` turns the limit off). The app sends an anonymous id
(SHA-256 of ANDROID_ID, header `X-Device-Id`), which survives uninstall/reinstall until a factory reset. Over the limit,
`/v1/verify` answers HTTP 402 and the app shows the "Go premium" box. Only real checks count: "no claim found" and
"sources unreachable" are free. Responses carry `X-Quota-Used`, `X-Quota-Limit`, `X-Premium`; `GET /v1/quota` returns the same.

Counters live in SQLite (`QUOTA_DB`). On a host with a throwaway disk they reset on every restart, so keep the file on a
persistent disk (see `render.yaml`).

Granting premium for testing (set `ADMIN_KEY` on the server; the device id is shown on the app's Premium screen):

    curl -X POST https://YOUR-SERVER/v1/admin/premium -H "X-Admin-Key: YOUR_ADMIN_KEY" -H "Content-Type: application/json" -d '{"device_id":"<id>","premium":true}'

The upgrade button is not connected to a payment yet: `PremiumActivity.startPurchase()` is where Google Play Billing goes.
After a purchase is verified, the server calls the same grant to mark the device premium.
