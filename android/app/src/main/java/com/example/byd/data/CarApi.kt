package com.example.byd.data

import android.os.SystemClock
import com.example.byd.bus.CarTransport
import com.example.byd.bus.Health
import com.example.byd.bus.LostReplyException
import com.example.byd.bus.NotSentException
import com.example.byd.bus.ServerJob
import com.example.byd.bus.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin HTTP layer over the server, and the command bus's transport. Works with the old 2.7 server
 * (no request ids, no jobs) and the new one (X-Request-Id, `?async=1`, `/jobs/<id>`, `rev`).
 *
 * A failure is reported as [NotSentException] only when not a byte of the request went out
 * (DNS, connect, TLS): only then may the bus send it again. Anything later is a
 * [LostReplyException]: it may have reached the server.
 */
class CarApi(private val prefs: AppPrefs) : CarTransport {

    // ------------------------------------------------------------------ status

    suspend fun status(fresh: Boolean): Wire =
        call(get(url("status", if (fresh) mapOf("fresh" to "1") else emptyMap())), STATUS_TIMEOUT_S)

    // ------------------------------------------------------------------ transport

    override suspend fun health(): Health {
        val wire = call(get(url("health", mapOf("v" to "2"))), HEALTH_TIMEOUT_S, client = healthHttp)
        if (wire.http >= 500) throw LostReplyException("health HTTP ${wire.http}") // Render still starting
        val body = wire.body as? JSONObject
        return if (body != null && body.has("ok")) {
            Health(v2 = true, boot = body.opt("boot")?.toString(), warm = if (body.has("warm")) body.optBoolean("warm") else null)
        } else {
            Health(v2 = false) // the 2.7 server answers a plain "OK"
        }
    }

    override suspend fun command(path: String, query: Map<String, String>, requestId: String, async: Boolean): Wire {
        val request = Request.Builder()
            .url(url(path, if (async) query + ("async" to "1") else query))
            .header("Authorization", "Bearer ${prefs.secret}")
            .header("X-Request-Id", requestId)
            // A command is a POST: OkHttp health-checks a pooled connection before a POST, so an
            // idle socket the server already closed is replaced instead of failing the command.
            .post(ByteArray(0).toRequestBody(null))
            .build()
        // The old server answers when the car has; the new one answers at once (202 + job).
        return call(request, if (async) ASYNC_TIMEOUT_S else SYNC_TIMEOUT_S, client = commandHttp)
    }

    override suspend fun job(requestId: String): Wire =
        call(get(url("jobs/$requestId", emptyMap())), JOB_TIMEOUT_S)

    /** Round-trip time to /health of [baseUrl] (used by "Test connection"). */
    suspend fun ping(baseUrl: String): Long {
        val url = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.addPathSegment("health")?.build()
            ?: throw IOException("Invalid server URL")
        val started = SystemClock.elapsedRealtime()
        http.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
        return SystemClock.elapsedRealtime() - started
    }

    // ------------------------------------------------------------------ plumbing

    private fun url(path: String, query: Map<String, String>): HttpUrl {
        val base = prefs.serverUrl.toHttpUrlOrNull() ?: throw NotSentException("Invalid server URL")
        return base.newBuilder().addPathSegments(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
    }

    private fun get(url: HttpUrl) = Request.Builder().url(url).header("Authorization", "Bearer ${prefs.secret}").build()

    private suspend fun call(request: Request, timeoutS: Long, client: OkHttpClient = http): Wire {
        val tracker = SendTracker()
        val tagged = request.newBuilder().tag(SendTracker::class.java, tracker).build()
        val started = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.IO) {
            val call = client.newCall(tagged).apply { timeout().timeout(timeoutS, TimeUnit.SECONDS) }
            val response = try {
                call.await()
            } catch (e: IOException) {
                if (e is NotSentException || e is LostReplyException) throw e
                throw if (tracker.started) LostReplyException(e.javaClass.simpleName, e) else NotSentException(e.javaClass.simpleName, e)
            }
            response.use {
                val text = try {
                    it.body?.string().orEmpty()
                } catch (e: IOException) {
                    throw LostReplyException("reply cut off", e)
                }
                parse(it, text, SystemClock.elapsedRealtime() - started)
            }
        }
    }

    private fun parse(response: Response, text: String, tookMs: Long): Wire {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val job = json?.optJSONObject("job")?.let { j ->
            ServerJob(
                id = j.optString("id"),
                state = j.optString("state"),
                outcome = j.str("outcome"),
                error = j.str("error"),
                sent = j.opt("sent")?.takeUnless { it == JSONObject.NULL }?.toString(),
            )
        }
        val code = response.code
        return Wire(
            http = code,
            ok = code in 200..299 && json?.optBoolean("ok", true) != false,
            error = json?.str("error"),
            requestId = json?.str("request_id") ?: response.header("X-Request-Id"),
            job = job,
            rev = json?.takeIf { it.has("rev") && !it.isNull("rev") }?.optLong("rev"),
            boot = json?.str("boot"),
            retryAfterS = json?.takeIf { it.has("retry_after_s") }?.optInt("retry_after_s")
                ?: response.header("Retry-After")?.toIntOrNull(),
            sent = json?.opt("sent")?.takeUnless { it == JSONObject.NULL }?.toString(),
            json = json != null,
            tookMs = tookMs,
            body = json,
        )
    }

    /** Set once OkHttp starts writing the request: from then on it may have reached the server. */
    private class SendTracker {
        @Volatile
        var started = false
    }

    private class TrackingListener(private val tracker: SendTracker) : EventListener() {
        override fun requestHeadersStart(call: Call) {
            tracker.started = true
        }
    }

    companion object {
        private const val STATUS_TIMEOUT_S = 55L
        private const val SYNC_TIMEOUT_S = 55L // the old server answers a command within ~50 s
        private const val ASYNC_TIMEOUT_S = 25L // the new one answers at once (it was woken first)
        private const val JOB_TIMEOUT_S = 15L
        private const val HEALTH_TIMEOUT_S = 70L // a sleeping Render instance holds the first request while it starts

        private val listeners = EventListener.Factory { call ->
            call.request().tag(SendTracker::class.java)?.let(::TrackingListener) ?: EventListener.NONE
        }

        /**
         * One client for the whole app. Its pool keeps the TLS connection to the server for
         * 5 minutes, so every tap after the first skips DNS + TCP + TLS (~150-300 ms).
         */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(50, TimeUnit.SECONDS)
                .callTimeout(55, TimeUnit.SECONDS) // each call sets its own below
                .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
                .eventListenerFactory(listeners)
                .build()
        }

        /**
         * Commands: never resent by OkHttp itself. The bus resends only what provably didn't go
         * out (and then OkHttp tries the server's other addresses, having marked the failed one).
         */
        private val commandHttp: OkHttpClient by lazy { http.newBuilder().retryOnConnectionFailure(false).build() }

        private val healthHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build() }
    }
}

private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
