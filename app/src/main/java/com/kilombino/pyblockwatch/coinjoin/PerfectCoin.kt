package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The perfect coin of a pool, sent and waiting for its first confirmation. The fingerprint that
 * signed the payment to ourselves also gave the coin's key, kept encrypted with [AutoKeyVault]
 * like a round on its own, and the mixed-output and change addresses of the seat, reserved then.
 * Once the coin confirms, the pool is joined (or opened) by itself and runs on its own: no
 * screen has to stay open and nothing more is asked.
 */
object PerfectCoin {
    private const val PREFS = "cj_perfect_coin"

    class Waiting(
        val txid: String, val vout: Int, val value: Long, val type: ScriptType, val path: String,
        val script: ByteArray, val mixScript: ByteArray, val changeScript: ByteArray,
        /** Join this pool… */
        val poolId: String?, val poolPassword: String?,
        /** …or open one with these terms. */
        val create: JSONObject?,
        val since: Long = System.currentTimeMillis() / 1000,
    ) {
        val key get() = "exact:$txid"
        fun toJson(): JSONObject = JSONObject().put("txid", txid).put("vout", vout).put("value", value).put("type", type.name)
            .put("path", path).put("script", script.toHex()).put("mix", mixScript.toHex()).put("change", changeScript.toHex())
            .put("pool", poolId ?: "").put("poolPassword", poolPassword ?: "").put("create", create ?: JSONObject.NULL).put("since", since)

        companion object {
            private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            fun parse(o: JSONObject) = Waiting(o.getString("txid"), o.getInt("vout"), o.getLong("value"), ScriptType.valueOf(o.getString("type")),
                o.getString("path"), hex(o.getString("script")), hex(o.getString("mix")), hex(o.getString("change")),
                o.optString("pool").ifEmpty { null }, o.optString("poolPassword").ifEmpty { null }, o.optJSONObject("create"), o.optLong("since"))
        }
    }

    private val _waiting = MutableStateFlow<List<Waiting>>(emptyList())
    val waiting: StateFlow<List<Waiting>> = _waiting.asStateFlow()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): List<Waiting> {
        val a = runCatching { JSONArray(prefs(ctx).getString("list", "[]")) }.getOrDefault(JSONArray())
        val week = System.currentTimeMillis() / 1000 - 7 * 86400
        val list = (0 until a.length()).mapNotNull { runCatching { Waiting.parse(a.getJSONObject(it)) }.getOrNull() }.filter { it.since > week }
        _waiting.value = list
        return list
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Waiting>) {
        prefs(ctx).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
        _waiting.value = list
    }

    /** The payment to ourselves went out: remember it, its key and the seat's addresses. */
    fun expect(ctx: Context, w: Waiting, coinKey: java.math.BigInteger) {
        AutoKeyVault.save(ctx, w.key, listOf(coinKey))
        save(ctx, load(ctx).filter { it.txid != w.txid } + w)
        CoinjoinService.start(ctx)
    }

    fun cancel(ctx: Context, txid: String) {
        AutoKeyVault.remove(ctx, "exact:$txid")
        save(ctx, load(ctx).filter { it.txid != txid })
    }

    fun hasWaiting(ctx: Context) = load(ctx).isNotEmpty()

    /**
     * Looks at each coin waiting; the confirmed ones join (or open) their pool on their own.
     * Called by the coinjoin service every minute.
     */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        for (w in load(app)) {
            val confirmed = runCatching {
                val store = Store(app); val e = store.endpoint(Chain.BLAKE2B)
                val c = ElectrumClient(e, store.pinnedFingerprint(e))
                try {
                    c.connect()
                    val h = c.history(Address.electrumScriptHash(w.script)).firstOrNull { it.txid == w.txid }
                    h != null && h.height > 0
                } finally { c.close() }
            }.getOrDefault(false)
            if (!confirmed) continue
            val k = AutoKeyVault.load(app, w.key)?.firstOrNull()
            cancel(app, w.txid)
            if (k == null) { CoinjoinHub.notifyPublic(app, "Perfect coin ready", "${w.value} sats confirmed. Join a pool with it in COINJOIN."); continue }
            runCatching {
                val node = Bip32Priv.ExtendedPrivKey(k, ByteArray(32), 5)
                val pick = CoinjoinHub.Pick(listOf(CoinjoinHub.PickCoin(w.txid, w.vout, w.value, node, w.path)), w.type, w.mixScript, w.changeScript)
                val c = w.create
                if (c != null) CoinjoinHub.create(app, pick, c.getLong("amount"), c.getDouble("feeRate"), c.getInt("maxPeers"), c.getInt("hours"),
                    c.getInt("minPeers"), c.optString("password").ifEmpty { null }, c.optInt("maxInputs", 1), auto = true)
                else {
                    val t = CoinjoinHub.fetchPools().firstOrNull { it.id == w.poolId } ?: error("that pool is no longer open")
                    CoinjoinHub.join(app, t, pick, w.poolPassword, auto = true)
                }
            }.onSuccess { CoinjoinHub.notifyPublic(app, "Perfect coin in", "Confirmed: " + (if (w.create != null) "your pool is open" else "asked to join the pool") + ", on its own.") }
             .onFailure { CoinjoinHub.notifyPublic(app, "Perfect coin ready", "Confirmed, but ${it.message}. Join a pool with it in COINJOIN.") }
        }
    }
}
