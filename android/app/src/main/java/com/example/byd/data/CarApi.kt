package com.example.byd.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
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

/** Thin HTTP layer over the Render server. */
class CarApi(private val prefs: AppPrefs) {

    class Reply(val httpCode: Int, val json: JSONObject?, val tookMs: Long) {
        val ok: Boolean get() = httpCode in 200..299 && json?.optBoolean("ok", true) != false
        val error: ApiError? get() = if (ok) null else ApiError.from(httpCode, json?.optString("error"))
    }

    /** A [command] is sent at most once: no silent resend that could toggle the tailgate back. */
    suspend fun get(path: String, query: Map<String, String> = emptyMap(), command: Boolean = false): Reply {
        val base = prefs.serverUrl.toHttpUrlOrNull() ?: throw IOException("Invalid server URL")
        val url = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${prefs.secret}")
            // A command is a POST: OkHttp health-checks a pooled connection before a POST, so an
            // idle socket the server already closed is replaced instead of failing the command.
            .apply { if (command) post(ByteArray(0).toRequestBody(null)) }
            .build()

        val started = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.IO) {
            (if (command) commandHttp else http).newCall(request).await().use {
                val body = it.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                Reply(it.code, json, SystemClock.elapsedRealtime() - started)
            }
        }
    }

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

    companion object {
        /**
         * One client for the whole app. Its pool keeps the TLS connection to the server for
         * 5 minutes, so every tap after the first skips DNS + TCP + TLS (~150-300 ms).
         * An idle pooled socket costs no battery; nothing here polls or runs in the background.
         */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS) // a sleeping Render instance can be slow to accept
                .readTimeout(50, TimeUnit.SECONDS) // the server answers a command within ~50 s, queue included
                .callTimeout(55, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
                .build()
        }

        /** Same pool, but a lost reply is reported instead of silently sending the command again. */
        private val commandHttp: OkHttpClient by lazy { http.newBuilder().retryOnConnectionFailure(false).build() }
    }
}

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
