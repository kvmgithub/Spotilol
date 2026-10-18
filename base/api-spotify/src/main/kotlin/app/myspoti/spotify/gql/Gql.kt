package app.myspoti.spotify.gql

import app.myspoti.spotify.bridge.Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class GqlException(val operation: String, message: String) : Exception("$operation: $message")

/** Persisted GraphQL operations of the Web Player: name -> hash. */
class GqlOps(private val hashes: Map<String, String>) {
    fun hash(name: String): String = hashes[name] ?: throw GqlException(name, "unknown operation")
    val names: Set<String> get() = hashes.keys

    companion object {
        fun bundled(): GqlOps {
            val text = GqlOps::class.java.getResourceAsStream("/spotify/gql-ops.json")!!.bufferedReader().use { it.readText() }
            val ops = Bridge.json.parseToJsonElement(text).jsonObject["operations"]!!.jsonObject
            return GqlOps(ops.mapValues { it.value.jsonObject["hash"]!!.jsonPrimitive.content })
        }
    }
}

/** Where the current operation table comes from (it can change while the app runs). */
interface OpsSource { val ops: GqlOps }

/** Runs a persisted GraphQL operation and returns the whole `{data: ...}` response. */
interface Gql {
    suspend fun query(name: String, variables: JsonObject): JsonElement
}

/** Through the page's own loader: always works once the page is ready, a bit slower. */
class BridgeGql(private val bridge: Bridge, private val source: OpsSource) : Gql {
    private val ops get() = source.ops
    override suspend fun query(name: String, variables: JsonObject): JsonElement =
        bridge.call("gql.query", buildJsonObject {
            put("name", name)
            // The page's own definitions say "mutation" for writes (never cached or deduplicated).
            put("operation", if (isMutation(name)) "mutation" else "query")
            put("hash", ops.hash(name))
            put("variables", variables)
        }).also { checkErrors(name, it) }
}

class HttpException(val code: Int, message: String) : Exception("HTTP $code: $message")

/**
 * Direct HTTP to Spotify with the Web Player's credentials: the web token from the bridge,
 * and a client token minted here and kept about 14 days.
 */
class SpotifyHttp(
    private val http: OkHttpClient,
    private val bridge: Bridge,
    private val language: String = "en",
) {
    private data class WebToken(val access: String, val expiresAtMs: Long, val clientId: String, val appVersion: String, val anonymous: Boolean)
    private data class ClientToken(val value: String, val expiresAtMs: Long)

    private val lock = Mutex()
    private var web: WebToken? = null
    private var client: ClientToken? = null
    private val deviceId = UUID.randomUUID().toString()

    /** Drops the cached web token (it belongs to one account); the client token is kept. */
    suspend fun forgetWebToken() = lock.withLock { web = null }

    /** True when the page's token belongs to a signed-in user (the cached one is reused). */
    suspend fun isSignedIn(): Boolean = lock.withLock { !webToken().anonymous }

    /** The account's token, fetched again only when the cached one is missing or stale. */
    private suspend fun webToken(): WebToken {
        val now = System.currentTimeMillis()
        return web?.takeIf { it.expiresAtMs - now > 60_000 } ?: run {
            val t = bridge.call("token.get").jsonObject
            WebToken(
                access = t["accessToken"]!!.jsonPrimitive.content,
                expiresAtMs = t["expiresAtMs"]!!.jsonPrimitive.long,
                clientId = t["clientId"]!!.jsonPrimitive.content,
                appVersion = t["appVersion"]!!.jsonPrimitive.content,
                anonymous = t["isAnonymous"]?.jsonPrimitive?.contentOrNull == "true",
            ).also { web = it }
        }
    }

    /** Sends a request with browser headers. Returns the status and body text. */
    suspend fun send(method: String, url: String, body: String? = null, contentType: String = JSON_TYPE, accept: String = "application/json"): Pair<Int, String> {
        // The account's token only ever goes to Spotify itself.
        val host = url.toHttpUrl().host
        require(host == "spotify.com" || host.endsWith(".spotify.com")) { "not a Spotify host: $host" }
        val (w, c) = tokens()
        val builder = Request.Builder().url(url)
            .header("authorization", "Bearer ${w.access}")
            .header("client-token", c.value)
            .header("app-platform", "WebPlayer")
            .header("spotify-app-version", w.appVersion)
            .header("accept", accept)
            .header("accept-language", language)
            .header("origin", "https://open.spotify.com")
            .header("referer", "https://open.spotify.com/")
            .header("user-agent", USER_AGENT)
        // Bytes keep the content type exactly as given (OkHttp appends a charset to String bodies,
        // which some Spotify endpoints reject with 400).
        builder.method(method, body?.toByteArray()?.toRequestBody(contentType.toMediaType()) ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null))
        val result = withContext(Dispatchers.IO) { http.newCall(builder.build()).execute().use { it.code to it.body.string() } }
        if (result.first == 401) lock.withLock { web = null }
        return result
    }

    suspend fun getJson(url: String): JsonElement? {
        val (code, text) = send("GET", url)
        return when (code) {
            200 -> Bridge.json.parseToJsonElement(text)
            204, 404 -> null
            else -> throw HttpException(code, text.take(200))
        }
    }

    private suspend fun tokens(): Pair<WebToken, ClientToken> = lock.withLock {
        val now = System.currentTimeMillis()
        val w = webToken()
        val c = client?.takeIf { it.expiresAtMs - now > 3_600_000 } ?: mintClientToken(w).also { client = it }
        w to c
    }

    private suspend fun mintClientToken(w: WebToken): ClientToken {
        val body = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", w.appVersion)
                put("client_id", w.clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "linux")
                    put("os_version", "unknown")
                    put("device_id", deviceId)
                    put("device_type", "computer")
                }
            }
        }
        val request = Request.Builder()
            .url("https://clienttoken.spotify.com/v1/clienttoken")
            // Bytes, not a String: OkHttp would append "; charset=utf-8", which this endpoint rejects with 400.
            .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType()))
            .header("accept", "application/json")
            .header("user-agent", USER_AGENT)
            .build()
        val (code, text) = withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code to it.body.string() } }
        val granted = runCatching { Bridge.json.parseToJsonElement(text).jsonObject["granted_token"]?.jsonObject }.getOrNull()
            ?: throw GqlException("clienttoken", "HTTP $code: ${text.take(200)}")
        val seconds = (granted["expires_after_seconds"] as? JsonPrimitive)?.long ?: 86_400
        return ClientToken(granted["token"]!!.jsonPrimitive.content, System.currentTimeMillis() + seconds * 1000)
    }

    companion object {
        const val JSON_TYPE = "application/json;charset=UTF-8"
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
    }
}

/** Operations that change something: not replayed when their outcome is unknown, and any error fails them. */
fun isMutation(name: String): Boolean =
    Regex("^(add|remove|move|update|delete|set|create|follow|unfollow|pin|unpin|edit|save|ban|unban|clear)[A-Z]").containsMatchIn(name)

/**
 * Throws when [response] reports a failure: errors without data for a read, any error or
 * no data at all for a mutation, where partial data would otherwise pass for a success.
 */
fun checkErrors(name: String, response: JsonElement) {
    val obj = response as? JsonObject ?: return
    val errors = obj["errors"]?.let { it as? kotlinx.serialization.json.JsonArray }?.takeIf { it.isNotEmpty() }
    val data = obj["data"]?.takeIf { it !is kotlinx.serialization.json.JsonNull } as? JsonObject
    val failed = if (isMutation(name)) errors != null || data == null
    else errors != null && data == null
    if (!failed) return
    val message = runCatching { errors!!.first().jsonObject["message"]!!.jsonPrimitive.content }.getOrDefault("no result")
    throw GqlException(name, message)
}

/** GraphQL straight to api-partner: fastest, parallel, independent of the page. Falls back to [fallback]. */
class HttpGql(private val http: SpotifyHttp, private val source: OpsSource, private val fallback: Gql) : Gql {
    private val ops get() = source.ops
    /** Calls answered directly, and calls that had to go through [fallback]. */
    val direct = java.util.concurrent.atomic.AtomicInteger()
    val fellBack = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun query(name: String, variables: JsonObject): JsonElement {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", name)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", ops.hash(name))
                }
            }
        }
        val mutation = isMutation(name)
        val (code, text) = try {
            http.send("POST", "https://api-partner.spotify.com/pathfinder/v2/query", body.toString())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            // The write may have reached Spotify before the connection broke: never sent twice.
            if (mutation) throw GqlException(name, "no answer: ${e.message}")
            fellBack.incrementAndGet(); return fallback.query(name, variables)
        } catch (e: Exception) {
            fellBack.incrementAndGet(); return fallback.query(name, variables)
        }
        if (code != 200) {
            // A server error may come after the write was done; a refusal (4xx) was not applied.
            if (mutation && code >= 500) throw GqlException(name, "HTTP $code")
            fellBack.incrementAndGet(); return fallback.query(name, variables)
        }
        direct.incrementAndGet()
        val response = Bridge.json.parseToJsonElement(text)
        checkErrors(name, response)
        return response
    }
}
