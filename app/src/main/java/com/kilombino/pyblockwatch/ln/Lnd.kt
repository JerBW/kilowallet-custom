package com.kilombino.pyblockwatch.ln

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import lndmobile.Callback
import lndmobile.Lndmobile
import lndmobile.RecvStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Lightning node inside the app: LND (the Bitcoin BLAKE2b fork, with the neutrino light
 * client) run as a library in this process. It follows the chain itself through compact block
 * filters from peers that serve them, checks BLAKE2b proof of work and the chain's activation
 * block, and keeps its channels and keys on this phone.
 *
 * Calls go in and out as serialized protobuf ([Pb], [PbMsg]); each is wrapped as a suspend
 * function. The node runs once per process: [start] is idempotent, and it stops with the app.
 */
object Lnd {
    /** Peers that serve the chain's compact block filters, tried in order. Ours first. */
    val DEFAULT_PEERS = listOf(
        "nodoblake2b.airdns.org:11010",   // Kilombino's Knots node
        "179.27.118.130:8343",            // PyBLOCK
        "173.249.217.35:4142",            // PyBLOCK
        "82.67.102.15:8333",              // mempool.guide
        "home.skyhawk.ca:8333",
    )

    /** BLAKE2b fee estimates in LND's fee.url format, from Kilombino's node. */
    const val FEE_URL = "https://nodex.kilombino.com/data/lnd-fees.json"

    fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "lnd")

    @Volatile var running = false
        private set

    /**
     * The configuration file LND reads: mainnet, neutrino with only [peers] (the DNS seeds are
     * Bitcoin's and return nodes on the SHA256d chain), no listening port (a phone cannot be
     * reached), and Tor through [torSocks] when given.
     */
    fun writeConfig(ctx: Context, peers: List<String>, torSocks: Int?, alias: String = "Kilowallet") {
        val d = dir(ctx); d.mkdirs()
        val conf = buildString {
            appendLine("[Application Options]")
            appendLine("debuglevel=info")
            appendLine("nolisten=true")
            appendLine("norest=true")
            // The TLS certificate would list every interface's address, and Android does not let
            // apps read them (netlink): LND then stops at start. Only localhost talks to it here.
            appendLine("tlsdisableautofill=true")
            appendLine("alias=$alias")
            appendLine("maxpendingchannels=3")
            // Lightning DNS seeds list nodes of the SHA256d chain; peers here are chosen by hand.
            appendLine("nobootstrap=true")
            // Channels up to 0.5 BTC can be opened to this wallet.
            appendLine("maxchansize=50000000")
            appendLine("[Bitcoin]")
            appendLine("bitcoin.mainnet=true")
            appendLine("bitcoin.node=neutrino")
            appendLine("[neutrino]")
            peers.forEach { appendLine("neutrino.connect=$it") }
            // Room for every peer given: with fewer slots it drops and redials one every few seconds.
            appendLine("neutrino.maxpeers=${maxOf(4, peers.size + 2)}")
            appendLine("[fee]")
            appendLine("fee.url=$FEE_URL")
            appendLine("[protocol]")
            appendLine("protocol.wumbo-channels=true")
            appendLine("[wtclient]")
            appendLine("wtclient.active=true")
            if (torSocks != null) {
                appendLine("[tor]")
                appendLine("tor.active=true")
                appendLine("tor.socks=127.0.0.1:$torSocks")
                appendLine("tor.skip-proxy-for-clearnet-targets=true")
            }
        }
        File(d, "lnd.conf").writeText(conf)
    }

    /** Starts the node (once per process); returns when its RPC answers. */
    suspend fun start(ctx: Context) {
        if (running) return
        val d = dir(ctx)
        suspendCancellableCoroutine<Unit> { cont ->
            Lndmobile.start("--lnddir=${d.absolutePath} --configfile=${File(d, "lnd.conf").absolutePath}", object : Callback {
                override fun onError(e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(b: ByteArray?) { running = true; if (cont.isActive) cont.resume(Unit) }
            })
        }
    }

    /** The node stopped on its own (it logs why): the next [start] starts it again. */
    fun markStopped() { running = false }

    /**
     * How far the chain sync has got while LND does not answer yet (its RPC only opens once
     * neutrino has caught up): the highest block height in the end of its log, or null.
     */
    fun syncHeight(ctx: Context): Int? = runCatching {
        val f = File(dir(ctx), "logs/bitcoin/mainnet/lnd.log")
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(r.length(), 48_000L)
            r.seek(r.length() - n)
            val b = ByteArray(n.toInt()); r.readFully(b)
            Regex("""height[= ](\d{3,7})""").findAll(String(b, Charsets.UTF_8)).maxOfOrNull { it.groupValues[1].toInt() }
        }
    }.getOrNull()

    /** The last error LND logged, to show when it does not come up. */
    fun lastError(ctx: Context): String? = runCatching {
        File(dir(ctx), "logs/bitcoin/mainnet/lnd.log").readLines().lastOrNull { "[ERR]" in it }
            ?.substringAfter("[ERR] ")?.substringAfter(": ")
    }.getOrNull()

    suspend fun stop() {
        if (!running) return
        runCatching { call { Lndmobile.stopDaemon(ByteArray(0), it) } }
        running = false
    }

    /** One unary call: [f] gets the callback to hand to Lndmobile. */
    suspend fun call(f: (Callback) -> Unit): PbMsg = suspendCancellableCoroutine { cont ->
        f(object : Callback {
            override fun onError(e: Exception) { if (cont.isActive) cont.resumeWithException(LndError(e.message ?: "LND error")) }
            override fun onResponse(b: ByteArray?) { if (cont.isActive) cont.resume(PbMsg.parse(b ?: ByteArray(0))) }
        })
    }

    /** A server stream: [onMsg] for each message, [onEnd] when it ends or fails. */
    fun stream(f: (RecvStream) -> Unit, onMsg: (PbMsg) -> Unit, onEnd: (Exception?) -> Unit) {
        f(object : RecvStream {
            override fun onError(e: Exception) { onEnd(if (e.message?.contains("EOF") == true) null else e) }
            override fun onResponse(b: ByteArray?) { onMsg(PbMsg.parse(b ?: ByteArray(0))) }
        })
    }

    // ---------------------------------------------------------------- wallet lifecycle

    /** lnrpc.WalletState: what the node needs before its main RPC works. */
    enum class WalletState { NON_EXISTING, LOCKED, UNLOCKED, RPC_ACTIVE, SERVER_ACTIVE, WAITING_TO_START, UNKNOWN }

    suspend fun state(): WalletState = runCatching {
        when (call { Lndmobile.getState(ByteArray(0), it) }.int(1)) {
            0 -> WalletState.NON_EXISTING; 1 -> WalletState.LOCKED; 2 -> WalletState.UNLOCKED
            3 -> WalletState.RPC_ACTIVE; 4 -> WalletState.SERVER_ACTIVE; 255 -> WalletState.WAITING_TO_START
            else -> WalletState.UNKNOWN
        }
    }.getOrDefault(WalletState.UNKNOWN)

    /**
     * Creates the node's wallet from [xprv], the Lightning master key worked out from the
     * recovery words ([LnSeed.masterXprv]), encrypted on disk with [password].
     * [birthday] (unix seconds) lets the first scan start late instead of at genesis.
     */
    suspend fun initWallet(password: ByteArray, xprv: String, birthday: Long, channelBackup: ByteArray? = null) {
        val req = Pb().bytes(1, password).string(7, xprv).int(8, birthday).int(4, 2500)
        // channel_backups (5): ChanBackupSnapshot.multi_chan_backup (2).multi_chan_backup (2).
        if (channelBackup != null) req.msg(5, Pb().msg(2, Pb().bytes(2, channelBackup)))
        call { Lndmobile.initWallet(req.build(), it) }
    }

    suspend fun unlock(password: ByteArray) {
        call { Lndmobile.unlockWallet(Pb().bytes(1, password).build(), it) }
    }

    // ---------------------------------------------------------------- reads

    data class Info(
        val pubkey: String, val alias: String, val height: Int, val syncedChain: Boolean,
        val syncedGraph: Boolean, val peers: Int, val active: Int, val pending: Int, val headerTime: Long,
    )

    suspend fun info(): Info {
        val m = call { Lndmobile.getInfo(ByteArray(0), it) }
        return Info(m.string(1), m.string(2), m.int(6), m.bool(9), m.bool(18), m.int(5), m.int(4), m.int(3), m.long(13))
    }

    data class Balances(val onchainConfirmed: Long, val onchainUnconfirmed: Long, val channels: Long, val pendingOpen: Long)

    suspend fun balances(): Balances {
        val w = call { Lndmobile.walletBalance(ByteArray(0), it) }
        val c = call { Lndmobile.channelBalance(ByteArray(0), it) }
        return Balances(w.long(2), w.long(3), c.msg(3).long(1), c.msg(7).long(1))
    }

    suspend fun newAddress(): String =
        call { Lndmobile.newAddress(Pb().int(1, 4).build(), it) }.string(1)   // 4 = taproot (p2tr)

    data class Channel(
        val active: Boolean, val peer: String, val point: String, val capacity: Long,
        val local: Long, val remote: Long, val peerAlias: String,
    )

    suspend fun channels(): List<Channel> =
        call { Lndmobile.listChannels(Pb().bool(6, true).build(), it) }.msgs(11).map {
            Channel(it.bool(1), it.string(2), it.string(3), it.long(5), it.long(6), it.long(7), it.string(34))
        }

    data class Pending(val peer: String, val point: String, val capacity: Long, val local: Long, val confirmationsLeft: Int)

    suspend fun pendingOpen(): List<Pending> =
        call { Lndmobile.pendingChannels(ByteArray(0), it) }.msgs(2).map { p ->
            val ch = p.msg(1)
            Pending(ch.string(1), ch.string(2), ch.long(3), ch.long(4), p.int(7))
        }

    data class PayReq(val destination: String, val hash: String, val sats: Long, val description: String, val expiry: Long, val timestamp: Long)

    suspend fun decode(invoice: String): PayReq {
        val m = call { Lndmobile.decodePayReq(Pb().string(1, invoice.trim()).build(), it) }
        return PayReq(m.string(1), m.string(2), m.long(3), m.string(6), m.long(5), m.long(4))
    }

    // ---------------------------------------------------------------- actions

    suspend fun connect(pubkey: String, host: String) {
        runCatching {
            call { Lndmobile.connectPeer(Pb().msg(1, Pb().string(1, pubkey).string(2, host)).int(3, 30).build(), it) }
        }.onFailure { if (it.message?.contains("already connected") != true) throw it }
    }

    /** Opens a public channel of [sats] to [pubkey] from the node's on-chain coins; returns the funding txid. */
    /**
     * Channels from a phone are private (not announced): nobody routes through it, so a phone
     * that goes off mid-payment never leaves someone else's payment hanging on it (which ends
     * in a force close). Invoices then carry route hints so the channel can still receive.
     */
    suspend fun openChannel(pubkey: String, sats: Long, satPerVbyte: Long, private: Boolean = true): String {
        val m = call {
            Lndmobile.openChannelSync(Pb().string(3, pubkey).int(4, sats).int(1, satPerVbyte).bool(8, private).build(), it)
        }
        // ChannelPoint: funding_txid_bytes (1, little-endian) or funding_txid_str (2).
        val str = m.string(2)
        return if (str.isNotEmpty()) str else m.bytes(1).reversedArray().joinToString("") { "%02x".format(it) }
    }

    /** Sends the node's on-chain coins to [address]; [all] sweeps everything. Returns the txid. */
    suspend fun sendCoins(address: String, sats: Long, satPerVbyte: Long, all: Boolean = false): String =
        call {
            Lndmobile.sendCoins(Pb().string(1, address).int(2, if (all) 0 else sats).int(4, satPerVbyte).bool(6, all).build(), it)
        }.string(1)

    suspend fun addInvoice(sats: Long, memo: String, expirySeconds: Long = 3600): String =
        call {
            // private (15): route hints for the private channels, or nobody finds a way here.
            Lndmobile.addInvoice(Pb().string(1, memo).int(5, sats).int(11, expirySeconds).bool(15, true).build(), it)
        }.string(2)

    /** Pays [invoice] (routerrpc.SendPaymentV2); returns the preimage, or throws with why it failed. */
    suspend fun pay(invoice: String, feeLimitSats: Long, timeoutSeconds: Int = 60): String =
        suspendCancellableCoroutine { cont ->
            // routerrpc.SendPaymentRequest: payment_request=5, timeout_seconds=6, fee_limit_sat=7.
            val req = Pb().string(5, invoice.trim()).int(6, timeoutSeconds).int(7, feeLimitSats).build()
            stream({ Lndmobile.routerSendPaymentV2(req, it) }, onMsg = { p ->
                // lnrpc.Payment: status=10 (2 SUCCEEDED, 3 FAILED), payment_preimage=6, failure_reason=16.
                when (p.int(10)) {
                    2 -> if (cont.isActive) cont.resume(p.string(6))
                    3 -> if (cont.isActive) cont.resumeWithException(LndError(failureText(p.int(16))))
                }
            }, onEnd = { e -> if (cont.isActive) cont.resumeWithException(e ?: LndError("the payment ended without an answer")) })
        }

    private fun failureText(code: Int) = when (code) {
        1 -> "timed out without finding a route"
        2 -> "no route to the destination with enough liquidity"
        3 -> "an unexpected error"
        4 -> "the invoice was refused by the destination (wrong amount, expired or already paid)"
        5 -> "not enough balance in your channels"
        6 -> "the payment was cancelled"
        else -> "the payment failed"
    }

    /** Adds a watchtower ([pubkeyHex]@[address]); already added is fine. */
    suspend fun addTower(pubkeyHex: String, address: String) {
        val pk = pubkeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        runCatching { call { Lndmobile.watchtowerClientAddTower(Pb().bytes(1, pk).string(2, address).build(), it) } }
            .onFailure { if (it.message?.contains("already", true) != true) throw it }
    }

    /** The Static Channel Backup of every channel, to keep with the backup file. */
    suspend fun channelBackup(): ByteArray =
        call { Lndmobile.exportAllChannelBackups(ByteArray(0), it) }.msg(2).bytes(2)
}

class LndError(msg: String) : Exception(msg)
