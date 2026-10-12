package com.kilombino.pyblockwatch.ln

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The LN tab's side of the node: whether Lightning is on, bringing [Lnd] up (start, create or
 * unlock its wallet, add the watchtower, reach Kilombino's node), and a status the screen
 * draws, refreshed while it is shown.
 */
object Lightning {
    /** Kilombino's node: offered for the first channel, never required. */
    const val KILOMBINO_NODE = "0222d36d7b634ead665c982145f6e4507ad65c4f369902ab3ca11e379d2139dc60"
    const val KILOMBINO_HOST = "nodoblake2b.airdns.org:50002"
    /** Kilombino's watchtower (onion: reached when Tor is on). */
    const val TOWER = "033f366c8e43b040dbc27648d68e123650ec1f23176de092f39b9e0a7fb4f2a95d"
    const val TOWER_HOST = "b44c2u3dar4fdhb4m4vsp3v6kzohh2cqrggk2io74v4olr4nn5nytyad.onion:9911"

    private const val PREFS = "lightning"

    sealed class Status {
        object Off : Status()
        data class Starting(val step: String) : Status()
        data class Running(
            val info: Lnd.Info, val balances: Lnd.Balances?, val channels: List<Lnd.Channel>,
            val pending: List<Lnd.Pending>, val tip: Int,
        ) : Status()
        data class Failed(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var poller: Job? = null
    /** The chain tip the wallet's own server reports, to show how far the node has synced. */
    @Volatile var chainTip: Int = 0

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun peers(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("peers", null)
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: Lnd.DEFAULT_PEERS

    fun setPeers(ctx: Context, peers: List<String>) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("peers", peers.joinToString("\n")).apply()

    /**
     * Turns Lightning on for the first time: the node's wallet is made from [bip39Seed] (the
     * recovery words, unlocked once by the caller) and the words never reach it otherwise.
     */
    suspend fun enable(ctx: Context, bip39Seed: ByteArray) = lock.withLock {
        val app = ctx.applicationContext
        try {
            bringUp(app) { state ->
                if (state == Lnd.WalletState.NON_EXISTING) {
                    _status.value = Status.Starting("creating the Lightning wallet")
                    val birthday = System.currentTimeMillis() / 1000 - 86400
                    Lnd.initWallet(LnSeed.password(app), LnSeed.masterXprv(bip39Seed), birthday)
                }
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: "Lightning did not start"); throw e
        } finally { bip39Seed.fill(0) }
    }

    /** Brings an enabled node up (once per process); a no-op when it already runs. */
    fun resume(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) { _status.value = Status.Off; return }
        scope.launch {
            lock.withLock {
                if (Lnd.running && _status.value is Status.Running) return@withLock
                runCatching { bringUp(app) {} }.onFailure { _status.value = Status.Failed(it.message ?: "Lightning did not start") }
            }
        }
    }

    private suspend fun bringUp(app: Context, onState: suspend (Lnd.WalletState) -> Unit) {
        _status.value = Status.Starting("starting the node")
        val tor = if (com.kilombino.pyblockwatch.chain.Tor.available) {
            com.kilombino.pyblockwatch.chain.Tor.start(app)
            (com.kilombino.pyblockwatch.chain.Tor.proxy(60_000)?.address() as? java.net.InetSocketAddress)?.port
        } else null
        if (!Lnd.running) {
            Lnd.writeConfig(app, peers(app), tor)
            Lnd.start(app)
        }
        // The wallet RPC comes first; the main RPC once the wallet is open.
        var state = Lnd.state()
        repeat(30) { if (state == Lnd.WalletState.UNKNOWN || state == Lnd.WalletState.WAITING_TO_START) { delay(1000); state = Lnd.state() } }
        if (state == Lnd.WalletState.UNKNOWN) {
            Lnd.markStopped()
            throw LndError("The node stopped: " + (Lnd.lastError(app) ?: "no answer from it"))
        }
        onState(state)
        if (state == Lnd.WalletState.LOCKED) {
            _status.value = Status.Starting("opening the Lightning wallet")
            Lnd.unlock(LnSeed.password(app))
        }
        torOn = tor != null; linked = false; towerAdded = false
        _status.value = Status.Starting("catching up with the chain")
        refresh()
    }

    @Volatile private var torOn = false
    @Volatile private var linked = false
    @Volatile private var towerAdded = false

    /**
     * Once the node answers (its RPC only does after the chain backend has caught up), best
     * effort: the watchtower (onion, needs Tor) and a link to Kilombino's node. Retried on
     * each refresh until they take.
     */
    private suspend fun link() {
        if (torOn && !towerAdded) runCatching { Lnd.addTower(TOWER, TOWER_HOST) }.onSuccess { towerAdded = true }
        if (!linked) runCatching { Lnd.connect(KILOMBINO_NODE, KILOMBINO_HOST) }.onSuccess { linked = true }
    }

    /** Re-reads everything the screen shows. */
    suspend fun refresh() {
        if (!Lnd.running) return
        runCatching {
            val info = Lnd.info()
            link()
            val b = runCatching { Lnd.balances() }.getOrNull()
            val ch = runCatching { Lnd.channels() }.getOrDefault(emptyList())
            val p = runCatching { Lnd.pendingOpen() }.getOrDefault(emptyList())
            _status.value = Status.Running(info, b, ch, p, chainTip)
        }.onFailure { if (_status.value !is Status.Running) _status.value = Status.Starting("catching up with the chain") }
    }

    /** Refreshes every few seconds while the LN tab is on screen. */
    fun watch(on: Boolean) {
        poller?.cancel(); poller = null
        if (!on) return
        poller = scope.launch {
            while (isActive) { refresh(); delay(5_000) }
        }
    }
}
