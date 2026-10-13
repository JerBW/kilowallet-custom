package com.kilombino.pyblockwatch.ln

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kilombino's inbound liquidity service (lsp.kilombino.com): it opens a private channel from
 * Kilombino's node to this one, so the wallet can receive. The request is signed with the
 * node key; the fee is paid with a hold invoice that Kilombino's node only settles once the
 * channel's funding transaction is out (otherwise it is cancelled and the payment comes back).
 */
object Inbound {
    const val API = "https://lsp.kilombino.com/v1"

    data class Info(val pubkey: String, val minSats: Long, val maxSats: Long, val feePerMillion: Long,
                    val availableSats: Long, val minDays: Int)
    data class Quote(val id: String, val invoice: String, val fee: Long, val sats: Long, val expires: Long)
    data class Order(val status: String, val fundingTxid: String?, val reason: String?)

    private fun http(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "Kilowallet")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val o = JSONObject(text)
            if (code !in 200..299) throw IllegalStateException(o.optString("error", "HTTP $code"))
            return o
        } finally { c.disconnect() }
    }

    fun info(): Info = http("GET", "/info").let {
        Info(it.getString("pubkey"), it.getLong("minSats"), it.getLong("maxSats"), it.getLong("feePerMillion"),
            it.getLong("availableSats"), it.getInt("minDays"))
    }

    /** Asks for a channel of [sats]; the node signs the request. */
    suspend fun quote(ourPubkey: String, sats: Long): Quote {
        val ts = System.currentTimeMillis() / 1000
        val sig = Lnd.signMessage("kilowallet-lsp:v1:$ourPubkey:$sats:$ts")
        val o = http("POST", "/quote", JSONObject().put("pubkey", ourPubkey).put("sats", sats).put("ts", ts).put("signature", sig))
        return Quote(o.getString("id"), o.getString("invoice"), o.getLong("fee"), o.getLong("sats"), o.getLong("expires"))
    }

    fun order(id: String): Order = http("GET", "/order/$id").let {
        Order(it.getString("status"), it.optString("fundingTxid").takeIf { t -> t.length == 64 }, if (it.isNull("reason")) null else it.optString("reason").ifBlank { null })
    }
}
