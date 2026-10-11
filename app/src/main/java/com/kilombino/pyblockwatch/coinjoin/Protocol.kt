package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kilojoin v1: coinjoin pools over nostr, after Joinstr's design (no coordinator holds funds;
 * the relay only carries messages), adapted to the BLAKE2b chain and to how Kilowallet wants
 * a round to start: people join an open pool, and once two or more are in, anyone can ask to
 * close it and the rest accept.
 *
 * Events (relay.kilombino.com):
 *  - [KIND_POOL] (parameterised replaceable, `d` = pool id, `t` = kilojoin), signed by the
 *    pool's own key, plaintext: the pool's terms and its current state. Public, so every
 *    wallet can list it and notify "new pool".
 *  - [KIND_MSG], tag `p` = pool key, NIP-44 encrypted:
 *    - join request: from a fresh join key to the pool key;
 *    - welcome / reject: from the pool key back to that join key;
 *    - everything else: from the pool key to itself — once welcomed, every member holds the
 *      pool's secret, so on the relay all members' messages look like one author.
 *
 * Sybil protection: a join must prove ownership of a real unspent coin of at least the pool
 * amount (a signature by the coin's key, checked by the pool's creator against the chain),
 * and a coin can hold one seat only. The creator therefore learns which coins joined; those
 * coins become public in the transaction anyway. What stays unlinked is which mixed output
 * belongs to which coin: mixed outputs are posted with no identity, after a random delay.
 *
 * Kilojoin v2 (Kilowallet 0.27): a pool has one address type, `script` = `p2wpkh` or `p2tr`
 * (every input, mixed output and change of the round is of it), and lets each person bring up
 * to `max_inputs` coins. A seat then carries `coins` (and, for v1 readers, `coin` = the first),
 * a join carries one ownership proof per coin in `proofs`, and the change is signed over all of
 * the seat's outpoints. v1 pools (one P2WPKH coin each) are still listed and joined as before;
 * a v1 wallet does not list v2 pools at all (it only accepts `v` = 1).
 */
object Protocol {
    const val RELAY = "wss://relay.kilombino.com"
    const val KIND_POOL = 32022
    /** Seconds between an open pool's re-announcements by its creator. */
    const val HEARTBEAT = 10 * 60L
    /** An open pool not re-announced for this long has lost its creator and is not listed. */
    const val STALE_AFTER = 35 * 60L
    const val KIND_MSG = 2023
    const val TAG = "kilojoin"
    /** Newest version this wallet speaks; it still reads and joins [V1] pools. */
    const val VERSION = 2
    const val V1 = 1
    /** Most coins one person may bring to a v2 pool. */
    const val MAX_INPUTS = 10
    /** BLAKE2b mainnet. Tests against a regtest node set another name so their pools never list here. */
    @Volatile var NETWORK = "blake2b"

    const val MIN_AMOUNT = 10_000L
    const val MAX_AMOUNT = 100_000_000L
    /** Smallest amount a pool may announce at all; the app itself only lists and creates from [MIN_AMOUNT]. */
    const val TEST_MIN_AMOUNT = 1_000L
    const val MIN_PEERS = 2
    const val MAX_PEERS = 20

    /** How long people get to answer "close now?"; whoever has not answered is left out. */
    const val VOTE_SECONDS = 10 * 60L
    /** From "closing" until every mixed output and change is in. */
    const val OUTPUTS_SECONDS = 5 * 60L
    /** From the transaction being ready until everyone has signed it (needs the user's touch). */
    const val SIGN_SECONDS = 20 * 60L

    /**
     * A private pool's password never travels: the creator keeps a key derived from it, and a
     * join carries an HMAC of the join key under that key, so only someone who typed the right
     * password can make one (and a captured one is useless for any other join key).
     */
    fun passwordKey(poolId: String, password: String): ByteArray =
        Hashes.sha256("kilojoin/v1/pw|$poolId|$password".toByteArray(Charsets.UTF_8))

    fun passwordProof(pwKey: ByteArray, joinPub: String): String = Hashes.hmacSha256(pwKey, joinPub.toByteArray()).toHex()

    fun tokenHash(token: String): String = Hashes.sha256(("kilojoin/v1/token|$token").toByteArray()).toHex()

    data class Terms(
        val id: String,
        val poolPub: String,
        val amount: Long,
        val feeRate: Double,
        val maxPeers: Int,
        val expiresAt: Long,
        val state: String,      // open, closing, done, aborted
        val peers: Int,
        val createdAt: Long,
        /** Fewest people the round may close with (2 to [maxPeers]). */
        val minPeers: Int = MIN_PEERS,
        /** Joining needs the pool's password (shared by its creator outside the app). */
        val private: Boolean = false,
        /** Protocol version of the pool: [V1] (one P2WPKH coin each) or [VERSION]. */
        val version: Int = V1,
        /** The one address type of the round (v2; always P2WPKH in v1). */
        val script: ScriptType = ScriptType.P2WPKH,
        /** Most coins one person may bring (v2; always 1 in v1). */
        val maxInputs: Int = 1,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("v", version).put("app", "kilowallet").put("network", NETWORK)
            .put("id", id).put("public_key", poolPub).put("denomination", amount)
            .put("fee_rate", feeRate).put("min_peers", minPeers).put("max_peers", maxPeers).put("private", private)
            .put("timeout", expiresAt).put("state", state).put("peers", peers)
            .also { if (version >= 2) it.put("script", scriptName(script)).put("max_inputs", maxInputs) }

        companion object {
            fun parse(ev: NostrEvent): Terms? = runCatching {
                val o = JSONObject(ev.content)
                val v = o.getInt("v")
                if (v != V1 && v != VERSION || o.getString("network") != NETWORK) return null
                val t = Terms(
                    id = o.getString("id"), poolPub = o.getString("public_key"),
                    amount = o.getLong("denomination"), feeRate = o.getDouble("fee_rate"),
                    maxPeers = o.getInt("max_peers"), expiresAt = o.getLong("timeout"),
                    state = o.getString("state"), peers = o.optInt("peers", 0), createdAt = ev.createdAt,
                    minPeers = o.optInt("min_peers", MIN_PEERS), private = o.optBoolean("private", false),
                    version = v,
                    script = if (v == V1) ScriptType.P2WPKH else parseScript(o.getString("script")) ?: return null,
                    maxInputs = if (v == V1) 1 else o.getInt("max_inputs"),
                )
                if (t.maxInputs !in 1..MAX_INPUTS) return null
                // Only the pool's own key may speak for it, and the terms must be in range.
                if (ev.pubkey != t.poolPub || ev.tag("d") != t.id) return null
                if (t.amount < TEST_MIN_AMOUNT || t.amount > MAX_AMOUNT) return null
                if (t.maxPeers !in MIN_PEERS..MAX_PEERS || t.feeRate < 1.0 || t.feeRate > 500.0) return null
                if (t.minPeers !in MIN_PEERS..t.maxPeers) return null
                t
            }.getOrNull()
        }
    }

    fun scriptName(t: ScriptType): String = if (t == ScriptType.P2TR) "p2tr" else "p2wpkh"
    fun parseScript(s: String): ScriptType? = when (s) { "p2wpkh" -> ScriptType.P2WPKH; "p2tr" -> ScriptType.P2TR; else -> null }

    /** A coin as it travels in messages (its address type is the pool's, not sent). */
    fun coinJson(c: CoinjoinTx.Coin): JSONObject = JSONObject()
        .put("txid", c.txid).put("vout", c.vout).put("value", c.value).put("pubkey", c.pubkey.toHex())

    fun parseCoin(o: JSONObject, type: ScriptType = ScriptType.P2WPKH): CoinjoinTx.Coin =
        CoinjoinTx.Coin(o.getString("txid"), o.getInt("vout"), o.getLong("value"), Hashes.hexToBytes(o.getString("pubkey")), type)
            .also {
                require(it.txid.matches(Regex("[0-9a-f]{64}")) && it.vout >= 0 && it.value > 0 && it.pubkey.size == 33)
            }

    /**
     * A member's seat: their coins (one in v1), their change (script + value, 0 = no change) and
     * the signature by the first coin's key over all of it, so nobody can swap someone else's change.
     */
    data class Seat(val tokenHash: String, val coins: List<CoinjoinTx.Coin>, val changeScript: ByteArray?, val changeValue: Long) {
        init { require(coins.isNotEmpty()) { "a seat with no coin" } }
        constructor(tokenHash: String, coin: CoinjoinTx.Coin, changeScript: ByteArray?, changeValue: Long) :
            this(tokenHash, listOf(coin), changeScript, changeValue)
        /** The first coin: the only one in a v1 pool, the one that signs the change in v2. */
        val coin: CoinjoinTx.Coin get() = coins.first()
        val inputValue: Long get() = coins.sumOf { it.value }

        fun toJson(): JSONObject = JSONObject().put("token_hash", tokenHash).put("coin", coinJson(coin))
            .put("change_script", changeScript?.toHex() ?: "").put("change_value", changeValue)
            .also { if (coins.size > 1) it.put("coins", JSONArray().also { a -> coins.forEach { c -> a.put(coinJson(c)) } }) }

        companion object {
            fun parse(o: JSONObject, type: ScriptType = ScriptType.P2WPKH): Seat {
                val cs = o.optString("change_script")
                val list = o.optJSONArray("coins")?.let { a -> (0 until a.length()).map { parseCoin(a.getJSONObject(it), type) } }
                    ?: listOf(parseCoin(o.getJSONObject("coin"), type))
                return Seat(o.getString("token_hash"), list, if (cs.isEmpty()) null else Hashes.hexToBytes(cs), o.getLong("change_value"))
            }
        }
    }

    /** What the change signature covers: the v1 message for one coin, all the outpoints for several. */
    fun changeMessage(poolId: String, coins: List<CoinjoinTx.Coin>, script: ByteArray?, value: Long): ByteArray =
        if (coins.size == 1) Hashes.sha256("kilojoin/v1/change|$poolId|${coins[0].outpoint}|${script?.toHex() ?: ""}|$value".toByteArray())
        else Hashes.sha256("kilojoin/v2/change|$poolId|${coins.joinToString(",") { it.outpoint }}|${script?.toHex() ?: ""}|$value".toByteArray())

    fun seatsJson(seats: Collection<Seat>): JSONArray = JSONArray().also { a -> seats.forEach { a.put(it.toJson()) } }
    fun parseSeats(a: JSONArray, type: ScriptType = ScriptType.P2WPKH): List<Seat> =
        (0 until a.length()).map { Seat.parse(a.getJSONObject(it), type) }
}
