package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** One `Myspoti.call` the bridge sent: its id, the page generation it was made for, its method and arguments. */
data class CapturedCall(
    val id: Int,
    val page: Int,
    val method: String,
    val args: JsonObject,
)

/**
 * A page for tests that answers nothing by itself. Every call the bridge sends is recorded ([calls],
 * in the order sent) and stays open until the test answers it ([succeed], [fail]); only methods
 * given a standing answer ([answer], for the page's background reads in a scenario) are answered at
 * once. The page's other messages (ready, state, ...) are sent when the test says ([post]): a call's
 * result, the page's readiness and its state are separate events, as in the real page.
 *
 * It only reads the call envelope [Bridge.call] writes; it has no player or retry logic of its own.
 */
class ScriptedBridgeHost : BridgeHost {
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 256)

    private val lock = Any()
    private val history = ArrayList<CapturedCall>()
    /** Calls handed to the test by [awaitCall] or answered by a standing answer. */
    private val taken = HashSet<Int>()
    /** Bumped at every call recorded: [awaitCall] waits on it. */
    private val recorded = MutableStateFlow(0)
    private val standing = ConcurrentHashMap<String, (JsonObject) -> JsonElement>()

    /** Every call sent so far, in order. */
    val calls: List<CapturedCall> get() = synchronized(lock) { history.toList() }

    /** [method] answered at once from now on, with [value] of its arguments. */
    fun answer(method: String, value: (JsonObject) -> JsonElement = { JsonNull }) { standing[method] = value }

    override suspend fun evaluate(script: String) {
        val m = CALL.find(script) ?: return
        val call = CapturedCall(
            id = m.groupValues[2].toInt(),
            page = m.groupValues[1].toInt(),
            method = m.groupValues[3],
            args = Json.parseToJsonElement(m.groupValues[4]).jsonObject,
        )
        val auto = standing[call.method]
        synchronized(lock) {
            history += call
            if (auto != null) taken += call.id
            recorded.value = recorded.value + 1
        }
        if (auto != null) succeed(call, auto(call.args))
    }

    /** Until the bridge listens: a message posted before that is lost. */
    suspend fun awaitSubscriber(timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) { messages.subscriptionCount.first { it > 0 } }
    }

    /** The next call of [method] not handed out yet (waited for, [timeoutMs] at most). It stays open until answered. */
    suspend fun awaitCall(method: String, timeoutMs: Long = 10_000): CapturedCall = withTimeout(timeoutMs) { nextCall(method) }

    private suspend fun nextCall(method: String): CapturedCall {
        while (true) {
            val seen = recorded.value
            take(method)?.let { return it }
            recorded.first { it != seen }
        }
    }

    private fun take(method: String): CapturedCall? = synchronized(lock) {
        history.firstOrNull { it.method == method && it.id !in taken }?.also { taken += it.id }
    }

    /** The page answers [call] with [value]. */
    suspend fun succeed(call: CapturedCall, value: JsonElement = JsonNull) {
        post(buildJsonObject { put("type", "result"); put("id", call.id); put("ok", true); put("value", value) })
    }

    /** The page answers [call] with an error [message]. */
    suspend fun fail(call: CapturedCall, message: String) {
        post(buildJsonObject { put("type", "result"); put("id", call.id); put("ok", false); put("error", JsonPrimitive(message)) })
    }

    /** A message from the page. */
    suspend fun post(message: JsonObject) = messages.emit(message.toString())

    /** A message from the page, as JSON text. */
    suspend fun post(json: String) = post(Json.parseToJsonElement(json).jsonObject)

    private companion object {
        /** The envelope [Bridge.call] writes: `(... window.__mspPage === <page>) ? (window.Myspoti.call(<id>, "<method>", <args>).catch(...` */
        val CALL = Regex("""__mspPage === (\d+)\) \? \(window\.Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL)
    }
}
