package com.kilombino.pyblockwatch.ln

import android.content.Context
import java.io.File
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
    const val KILOMBINO_ONION = "eu2pntt4zzsvm2fkcn5uaqxac2mj5t2fk5ecwyhhejp3deu7env2h2ad.onion:9735"
    /** Kilombino's watchtower (onion: reached when Tor is on). */
    const val TOWER = "033f366c8e43b040dbc27648d68e123650ec1f23176de092f39b9e0a7fb4f2a95d"
    const val TOWER_HOST = "b44c2u3dar4fdhb4m4vsp3v6kzohh2cqrggk2io74v4olr4nn5nytyad.onion:9911"

    private const val PREFS = "lightning"
    /** 2026-09-01 00:00 UTC, around the BLAKE2b activation (block 961640): no node of these words is older. */
    private const val ACTIVATION_TIME = 1788220800L
    /** A channel backup from a restored backup file, handed to the node when its wallet is made. */
    private const val RESTORE_SCB = "lnd-restore.backup"

    /**
     * What the backup file keeps of Lightning: the wallet's birthday and the static channel
     * backup (LND keeps it current in channel.backup, encrypted with the node's keys). With it
     * and the words, a new phone can ask each peer to close the channels and get the money back.
     */
    fun exportBackup(ctx: Context): org.json.JSONObject? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val birthday = prefs.getLong("birthday", 0L)
        if (birthday == 0L) return null
        val o = org.json.JSONObject().put("birthday", birthday)
        channelBackupFile(ctx).takeIf { it.exists() }?.let {
            o.put("scb", android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP))
        }
        return o
    }

    /** LND's own channel backup file, kept current by the node (encrypted with its keys). */
    fun channelBackupFile(ctx: Context) = File(Lnd.dir(ctx), "data/chain/bitcoin/mainnet/channel.backup")

    /** A channel backup file from the old phone, handed to the node when its wallet is made here. */
    fun stageScb(ctx: Context, bytes: ByteArray) = File(ctx.noBackupFilesDir, RESTORE_SCB).writeBytes(bytes)
    fun hasStagedScb(ctx: Context) = File(ctx.noBackupFilesDir, RESTORE_SCB).exists()

    /** Keeps what [exportBackup] saved for the next time Lightning is turned on here. */
    fun stageRestore(ctx: Context, o: org.json.JSONObject) {
        o.optLong("birthday").takeIf { it > 0 }?.let {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("birthday", it).apply()
        }
        o.optString("scb").takeIf { it.isNotEmpty() }?.let {
            File(ctx.noBackupFilesDir, RESTORE_SCB).writeBytes(android.util.Base64.decode(it, android.util.Base64.NO_WRAP))
        }
    }

    sealed class Status {
        object Off : Status()
        /** [height] is how far the chain sync has got, when known (shown against the tip). */
        data class Starting(val step: String, val height: Int? = null) : Status()
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

    /** This phone's node was moved to another phone ([LnMove]). */
    fun movedAway(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("moved_away", false)

    fun markOff() { _status.value = Status.Off }

    /** Stops and deletes an empty node (the screen checks it holds nothing). */
    suspend fun deleteNode(ctx: Context) {
        val app = ctx.applicationContext
        watch(false)
        Lnd.stop()
        kotlinx.coroutines.delay(6_000)
        Lnd.dir(app).deleteRecursively()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        markOff()
    }

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
                    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    // The same words may have run a node before (another phone, a reinstall):
                    // without a birthday from a backup file, the wallet looks from the chain's
                    // activation, so no coin of that node is missed.
                    val birthday = prefs.getLong("birthday", 0L).takeIf { it > 0 } ?: ACTIVATION_TIME
                    val scb = File(app.noBackupFilesDir, RESTORE_SCB).takeIf { it.exists() }?.readBytes()
                    Lnd.initWallet(LnSeed.password(app), LnSeed.masterXprv(bip39Seed), birthday, scb)
                    prefs.edit().putLong("birthday", birthday).apply()
                    File(app.noBackupFilesDir, RESTORE_SCB).delete()
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

    @Volatile private var appCtx: Context? = null

    private suspend fun bringUp(app: Context, onState: suspend (Lnd.WalletState) -> Unit) {
        appCtx = app
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
        repeat(90) { if (state == Lnd.WalletState.UNKNOWN || state == Lnd.WalletState.WAITING_TO_START) { delay(1000); state = Lnd.state() } }
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
    private suspend fun link(peers: Int) {
        if (torOn && !towerAdded) runCatching { Lnd.addTower(TOWER, TOWER_HOST) }.onSuccess { towerAdded = true }
        // With no peer at all (a fresh or moved node, or one that lost its connection), reach
        // Kilombino's node again every half minute. The name is resolved here, not by LND: with
        // Tor on, LND resolves names through Tor, which fails until Tor is up. Its onion is tried too.
        if (peers > 0) { linked = true; return }
        val now = System.currentTimeMillis()
        if (now - lastLink < 30_000) return
        lastLink = now
        val host = KILOMBINO_HOST.substringBefore(':'); val port = KILOMBINO_HOST.substringAfter(':')
        val ips = runCatching { java.net.InetAddress.getAllByName(host).map { it.hostAddress } }.getOrDefault(emptyList())
        val targets = ips.map { if (':' in it) "[$it]:$port" else "$it:$port" } + KILOMBINO_HOST +
            (if (torOn) listOf(KILOMBINO_ONION) else emptyList())
        val errors = ArrayList<String>()
        for (t in targets) {
            val r = runCatching { Lnd.connect(KILOMBINO_NODE, t) }
            if (r.isSuccess) { linked = true; linkError = null; return }
            errors += "$t: ${r.exceptionOrNull()?.message}"
        }
        linkError = if (targets.isEmpty()) "could not resolve $host" else errors.joinToString("\n")
    }

    /** Why the last attempt to reach Kilombino's node failed, to show under the channel. */
    @Volatile var linkError: String? = null
        private set
    @Volatile private var lastLink = 0L

    /** Re-reads everything the screen shows. */
    suspend fun refresh(): Unit = kotlinx.coroutines.withContext(Dispatchers.IO) { refreshHere() }

    private suspend fun refreshHere() {
        if (!Lnd.running) return
        runCatching {
            val info = Lnd.info()
            link(info.peers)
            val b = runCatching { Lnd.balances() }.getOrNull()
            val ch = runCatching { Lnd.channels() }.getOrDefault(emptyList())
            val p = runCatching { Lnd.pendingOpen() }.getOrDefault(emptyList())
            _status.value = Status.Running(info, b, ch, p, chainTip)
        }.onFailure {
            // The wallet RPC can answer before LND has looked at its database, so the state read
            // at start may not be the real one: a locked wallet found here is opened here.
            appCtx?.let { app ->
                if (Lnd.state() == Lnd.WalletState.LOCKED) runCatching { Lnd.unlock(LnSeed.password(app)) }
            }
            if (_status.value !is Status.Running)
                _status.value = Status.Starting("catching up with the chain", appCtx?.let { Lnd.syncHeight(it) })
        }
    }

    /** Refreshes every few seconds while the LN tab is on screen. */
    fun watch(on: Boolean) {
        poller?.cancel(); poller = null
        if (!on) return
        poller = scope.launch {
            // One failed look must not end the loop (the screen would wait for good).
            while (isActive) { runCatching { refresh() }; delay(5_000) }
        }
    }
}
