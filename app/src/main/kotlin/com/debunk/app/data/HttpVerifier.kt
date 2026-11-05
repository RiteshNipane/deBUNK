package com.debunk.app.data

import com.debunk.core.domain.SourceRef
import com.debunk.core.domain.Stance
import com.debunk.core.domain.Verdict
import com.debunk.core.domain.VerdictResult
import com.debunk.core.verify.QuotaExceededException
import com.debunk.core.verify.Verifier
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Talks to the FastAPI backend (see backend/openapi.json).
 * Sends ONLY the headline-like text the detector picked (<= 300 chars) and a language code.
 * Timeouts are generous because the backend runs an LLM and several news sources (typically 3-8 s); any failure throws and the coordinator goes quiet.
 */
class HttpVerifier(
    private val baseUrl: String,
    private val apiKey: String,
    private val lang: () -> String = { "en" },
    private val deviceId: () -> String = { "" },
    private val onQuota: (QuotaInfo) -> Unit = {},
) : Verifier {

    override suspend fun verify(claim: String): VerdictResult = withContext(Dispatchers.IO) {
        val conn = (URL("$baseUrl/v1/verify").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotEmpty()) setRequestProperty("X-API-Key", apiKey)
            deviceId().takeIf { it.isNotEmpty() }?.let { setRequestProperty("X-Device-Id", it) }
        }
        try {
            val body = JSONObject().put("text", claim.take(600)).put("lang", lang()).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 402) {
                val detail = runCatching {
                    JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()).optJSONObject("detail")
                }.getOrNull()
                val limit = detail?.optInt("limit", 10) ?: 10
                val used = detail?.optInt("used", limit) ?: limit
                onQuota(QuotaInfo(used, limit, premium = false))
                throw QuotaExceededException(limit, used)
            }
            if (code !in 200..299) throw IOException("backend returned HTTP $code")
            readQuota(conn)?.let(onQuota)
            parse(claim, conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun readQuota(conn: HttpURLConnection): QuotaInfo? {
        val limit = conn.getHeaderField("X-Quota-Limit")?.toIntOrNull() ?: return null
        val used = conn.getHeaderField("X-Quota-Used")?.toIntOrNull() ?: return null
        return QuotaInfo(used, limit, premium = conn.getHeaderField("X-Premium") == "1")
    }

    /** Asks the server how many free checks are left (shown on the main screen). Returns null if unreachable. */
    suspend fun fetchQuota(): QuotaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$baseUrl/v1/quota").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                if (apiKey.isNotEmpty()) setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("X-Device-Id", deviceId())
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val limit = o.optInt("limit", 0)
                if (limit <= 0) null else QuotaInfo(o.optInt("used", 0), limit, o.optBoolean("premium", false))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    internal fun parse(originalText: String, json: String): VerdictResult {
        val o = JSONObject(json)
        val claims = o.optJSONArray("claims")?.let { arr ->
            List(arr.length()) { i -> parseOne(originalText, arr.getJSONObject(i)) }
        } ?: emptyList()
        // Only keep a breakdown when it actually adds something beyond the headline verdict.
        return parseOne(originalText, o).copy(
            degraded = o.optBoolean("degraded", false),
            claims = if (claims.size > 1) claims else emptyList(),
        )
    }

    private fun parseOne(originalText: String, o: JSONObject): VerdictResult {
        val sources = o.optJSONArray("sources")?.let { arr ->
            List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                SourceRef(
                    name = s.getString("name"),
                    url = s.optString("url", ""),
                    stance = runCatching { Stance.valueOf(s.getString("stance").uppercase()) }.getOrDefault(Stance.NEUTRAL),
                    credibilityTier = s.optInt("credibility_tier", 2),
                    kind = s.optString("kind", "news"),
                    published = s.optString("published", "").ifEmpty { null },
                    title = s.optString("title", "").ifEmpty { null },
                )
            }
        } ?: emptyList()

        var verdict = runCatching { Verdict.valueOf(o.getString("verdict")) }.getOrDefault(Verdict.UNVERIFIED)
        // Defence in depth for the spec rule: never show a verdict resting on fewer than 2 sources.
        if (sources.size < 2) verdict = Verdict.UNVERIFIED

        return VerdictResult(
            claim = o.optString("claim", originalText),
            verdict = verdict,
            confidence = o.optInt("confidence", 0).coerceIn(0, 100),
            summary = o.optString("summary", "").take(200),
            sources = sources,
            developing = o.optBoolean("developing", false),
        )
    }
}
