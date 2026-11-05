package com.kilombino.pyblockwatch.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.kilombino.pyblockwatch.chain.Chain

/**
 * Posts balance notifications, and nothing else. Pulled out of the background service so
 * the SAME wording fires whether the change was caught by the 5-minute background watcher
 * or by the 30-second refresh while the app is open — one voice, one set of channels.
 */
class Notifier(private val context: Context, private val titlePrefix: String = "") {

    private fun manager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val m = manager()
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Watching", NotificationManager.IMPORTANCE_MIN)
        )
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Balance changes", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Tapped, a payment's notification opens the wallet it is about (the other one after the fingerprint). */
    private fun tabFor(chain: Chain) = when {
        chain != Chain.BLAKE2B -> OpenTab.SPAMCOIN
        titlePrefix == WatchService.WATCH_PREFIX -> OpenTab.BTC_WATCH
        titlePrefix == WatchService.HOT_PREFIX -> OpenTab.BTC_HOT
        else -> OpenTab.BTC
    }

    /**
     * Post one alert. [id] is stable per (chain, event, txid) so re-evaluating the same
     * state updates the existing notification instead of stacking duplicates; a payment's
     * first confirmation takes the place of its mempool alert.
     */
    private fun post(id: Int, title: String, text: String, tab: String) {
        ensureChannels()
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(titlePrefix + title))
            .setContentText(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(text))
            .setStyle(Notification.BigTextStyle().bigText(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(text)))
            .setAutoCancel(true)
            .setContentIntent(OpenTab.pending(context, tab))
            .build()
        manager().notify(id, n)
    }

    /** Group sats with thin spaces (12 345 678) so an amount reads as an exact count. */
    private fun sats(v: Long): String {
        val digits = kotlin.math.abs(v).toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return (if (v < 0) "-" else "") + sb
    }

    /** The chain as notifications name it. */
    internal fun label(chain: Chain): String = if (chain == Chain.BLAKE2B) "XBT" else chain.display

    fun found(chain: Chain, confirmed: Long, unconfirmed: Long) {
        val total = confirmed + unconfirmed
        val text = if (unconfirmed != 0L)
            "${sats(total)} sats · ${sats(unconfirmed)} in the mempool (0 conf)"
        else "${sats(confirmed)} sats · confirmed"
        post(idFor(chain, "found", ""), "${label(chain)}: balance detected", text, tabFor(chain))
    }

    /** Coins arriving at the Lightning node's on-chain wallet (FUND). */
    fun lightningIn(amount: Long, txid: String, confirmed: Boolean) =
        post(("ln:" + (if (confirmed) "conf" else "mem") + ":" + txid).hashCode(),
             if (confirmed) "⚡ Lightning node: payment confirmed" else "⚡ Lightning node: incoming payment",
             "+${sats(amount)} sats on-chain in the Lightning node" + (if (confirmed) " (1 conf)" else " (0 conf)"), OpenTab.LIGHTNING)

    // A Lightning payment is one notification, keyed by its payment hash (or the offer it pays):
    // paid or not paid replaces "on its way" if that was shown.
    fun lightningPaid(amount: Long, key: String) =
        post(("lnpay:$key").hashCode(), "⚡ Lightning: paid", "−${sats(amount)} sats", OpenTab.LIGHTNING)

    fun lightningNotPaid(amount: Long, why: String, key: String) =
        post(("lnpay:$key").hashCode(), "❌ Lightning: ${sats(amount)} sats not paid", why, OpenTab.LIGHTNING)

    fun lightningReceived(amount: Long, memo: String, key: String) =
        post(("lnrecv:$key").hashCode(), "⚡ Lightning: received",
             "+${sats(amount)} sats" + (if (memo.isNotBlank()) "\n“$memo”" else ""), OpenTab.LIGHTNING)

    /**
     * Persistent dedupe across foreground/background overlap and process restarts.
     * A mempool alert and its later confirmation are deliberately separate phases.
     */
    private fun shouldSuppressTx(chain: Chain, phase: String, txid: String): Boolean {
        if (txid.isBlank()) return false
        synchronized(Notifier::class.java) {
            val prefs = context.getSharedPreferences("pyblockwatch_txid_dedupe", Context.MODE_PRIVATE)
            val key = titlePrefix + chain.id + "_" + phase + "_" + txid
            if (prefs.getBoolean(key, false)) return true
            prefs.edit().putBoolean(key, true).apply()
            return false
        }
    }

    fun mempoolIn(chain: Chain, amount: Long, txid: String) {
        if (shouldSuppressTx(chain, "mem", txid)) return
        post(idFor(chain, "mem", txid), "Received",
             "${label(chain)} · +${sats(amount)} sats · 0 conf", tabFor(chain))
    }

    fun mempoolOut(chain: Chain, amount: Long, txid: String) {
        if (shouldSuppressTx(chain, "mem", txid)) return
        post(idFor(chain, "mem", txid), "Sent",
             "${label(chain)} · −${sats(amount)} sats · 0 conf", tabFor(chain))
    }

    fun firstConfIn(chain: Chain, amount: Long, txid: String) {
        if (shouldSuppressTx(chain, "conf", txid)) return
        post(idFor(chain, "conf", txid), "Received ✅",
             "${label(chain)} · +${sats(amount)} sats · 1 conf", tabFor(chain))
    }

    /** A confirmed incoming payment whose proof (merkle + block proof of work) did not check out. */
    fun unprovenIn(chain: Chain, amount: Long, txid: String) {
        // Occupies the same confirmation phase: a tx must never produce both.
        if (shouldSuppressTx(chain, "conf", txid)) return
        post(idFor(chain, "unproven", txid), "⚠ Incoming payment NOT proven",
             "${label(chain)} · +${sats(amount)} sats: the server says it confirmed, but its proof does not check " +
                 "out. Do not count on it; check it on your own node or another server.", tabFor(chain))
    }

    fun firstConfOut(chain: Chain, amount: Long, txid: String) {
        if (shouldSuppressTx(chain, "conf", txid)) return
        post(idFor(chain, "conf", txid), "Sent ✅",
             "${label(chain)} · −${sats(amount)} sats · 1 conf", tabFor(chain))
    }

    // A change we only ever saw already-confirmed (received or spent between two checks,
    // never observed in the mempool). No txid to key on, so it collapses per chain.
    fun receivedConfirmed(chain: Chain, amount: Long) =
        post(idFor(chain, "recv", ""), "Received",
             "${label(chain)} · +${sats(amount)} sats · confirmed", tabFor(chain))

    fun receivedPending(chain: Chain, amount: Long) =
        post(idFor(chain, "recv", ""), "Received",
             "${label(chain)} · +${sats(amount)} sats · in the mempool (0 conf)", tabFor(chain))

    fun sentPending(chain: Chain, amount: Long) =
        post(idFor(chain, "sent", ""), "Sent",
             "${label(chain)} · ${sats(amount)} sats · in the mempool (0 conf)", tabFor(chain))

    fun sentConfirmed(chain: Chain, amount: Long) =
        post(idFor(chain, "sent", ""), "Sent",
             "${label(chain)} · ${sats(amount)} sats · confirmed", tabFor(chain))

    // ---------------------------------------------------------------- JoinMarket fidelity bonds

    private fun bondId(key: String): Int = ("jmbond:$key").hashCode()
    private fun day(epochSeconds: Long) = JoinMarket.date(epochSeconds)

    fun bondUnlocksSoon(amount: Long, locktime: Long, days: Int, key: String) =
        post(bondId(key), if (days == 1) "Fidelity bond unlocks tomorrow" else "Fidelity bond unlocks in $days days",
             "${sats(amount)} sats locked until ${day(locktime)}. Then spend or renew it from Jam.", OpenTab.BTC)

    fun bondUnlocked(amount: Long, locktime: Long, key: String) =
        post(bondId(key), "Fidelity bond unlocked",
             "${sats(amount)} sats, locked until ${day(locktime)}: spend or renew it from Jam.", OpenTab.BTC)

    fun bondValueZero(amount: Long, key: String) =
        post(bondId(key), "Fidelity bond no longer counts",
             "${sats(amount)} sats: its value is now zero. Renew it from Jam to keep being picked as a maker.", OpenTab.BTC)

    // ---------------------------------------------------------------- Ark

    private fun arkId(event: String, key: String): Int = ("ark:$event:$key").hashCode()

    fun arkReceived(kind: String, amount: Long, key: String) =
        post(arkId("in", key), "Ark: ${kind.lowercase()}", "+${sats(amount)} sats, spendable in Ark", OpenTab.ARK)

    fun arkBoarded(amount: Long, key: String) =
        post(arkId("board", key), "Ark: funds moved in", "+${sats(amount)} sats are now spendable in Ark", OpenTab.ARK)

    /** The Ark engine's chain source and the BTC server (verified) disagree. */
    fun arkChainMismatch(text: String) = post(arkId("xcheck", text.take(40)), "Ark: chain sources disagree", text, OpenTab.ARK)

    fun arkWithdrawn(amount: Long, key: String) =
        post(arkId("out", key), "Ark: withdrawal done", "${sats(kotlin.math.abs(amount))} sats left Ark on-chain", OpenTab.ARK)

    /** "payment" for an Ark payment, "Lightning payment" for Lightning: reads right after "Ark:". */
    private fun what(kind: String) = if (kind == "Ark payment") "payment" else kind

    fun arkBoarding(amount: Long, key: String) =
        post(arkId("boarding", key), "Ark: moving funds in",
             "${sats(kotlin.math.abs(amount))} sats on their way into Ark; spendable after 3 confirmations", OpenTab.ARK)

    fun arkSent(kind: String, amount: Long, key: String) =
        post(arkId("sent", key), "Ark: ${what(kind)} sent", "${sats(-kotlin.math.abs(amount))} sats", OpenTab.ARK)

    fun arkFailed(kind: String, amount: Long, key: String) =
        post(arkId("failed", key), "Ark: ${what(kind)} failed",
             "${sats(kotlin.math.abs(amount))} sats did not go out; the coins are still yours", OpenTab.ARK)

    fun arkRenewed(cost: Long, key: String) =
        post(arkId("renew", key), "Ark: coins renewed",
             "Good for about 30 more days" + (if (cost > 0) " · cost ${sats(cost)} sats" else ""), OpenTab.ARK)

    fun arkDeposit(amount: Long, key: String, confirmed: Boolean) =
        post(arkId(if (confirmed) "deposit-conf" else "deposit", key),
             if (confirmed) "Ark: deposit confirmed" else "Ark: deposit in the mempool",
             "+${sats(amount)} sats on-chain" + (if (confirmed) "" else " (0 conf)") +
                 ", not in Ark yet. Open the Ark tab and tap MOVE INTO ARK.", OpenTab.ARK)

    fun arkDepositSent(amount: Long, key: String, confirmed: Boolean) =
        post(arkId(if (confirmed) "dsent-conf" else "dsent", key),
             if (confirmed) "Ark: on-chain send confirmed" else "Ark: on-chain send in the mempool",
             "${sats(-kotlin.math.abs(amount))} sats from the deposit" + (if (confirmed) "" else " (0 conf)"), OpenTab.ARK)

    fun updateAvailable(version: String) =
        post(("update:$version").hashCode(), "Kilowallet $version is out",
             "Tap to update it from inside the app.", OpenTab.UPDATE)

    fun updated(version: String) =
        post(("updated:$version").hashCode(), "Kilowallet $version is installed ✓",
             "Tap to open it.", OpenTab.BTC)

    /** A restored Ark wallet's first look: what it holds in Ark and in its on-chain deposit. */
    fun arkFound(inArk: Long, deposit: Long) =
        post(arkId("found", ""), "Ark: balance found",
             if (deposit > 0) "${sats(inArk)} sats in Ark and ${sats(deposit)} sats in its on-chain deposit" else "${sats(inArk)} sats in Ark", OpenTab.ARK)

    fun arkExpiring(blocks: Int) =
        post(arkId("expiry", ""), if (blocks < 432) "Ark: a coin expires in ${blocks * 10 / 60} hours" else "Ark: a coin expires soon",
             (if (blocks < 144) "In about ${blocks * 10 / 60} hours" else "In about ${blocks * 10 / 1440} days") + " ($blocks blocks). Open the Ark tab and tap RENEW: " +
                 "once it expires, the Ark server can take it.", OpenTab.ARK)

    private fun idFor(chain: Chain, phase: String, txid: String): Int =
        (titlePrefix + chain.id + phase + txid).hashCode()

    companion object {
        const val CHANNEL_ONGOING = "watch_ongoing"
        const val CHANNEL_ALERTS = "watch_alerts_v2"
    }
}

/**
 * The balance notification engine shared by foreground refreshes and WatchService.
 *
 * Scanner supplies the exact signed wallet effect of each transaction (+ received,
 * - sent including its fee), so notifications are attributed by txid rather than by
 * dividing an aggregate balance delta.
 */
object BalanceWatch {

    /**
     * Foreground refreshes, WatchService and post-coinjoin looks can overlap. Serialize
     * them so every look sees the state left by the preceding one.
     */
    @Synchronized
    fun evaluate(
        store: Store,
        notifier: Notifier,
        chain: Chain,
        confirmed: Long,
        unconfirmed: Long,
        txs: List<TxConf>,
        /**
         * Coinjoins belonging to this phone are already described by their round
         * notifications, so the ordinary wallet watcher records them silently.
         */
        coinjoins: Set<String> = emptySet(),
    ) {
        val prevConf = store.lastNotifiedConf(chain)
        val newTotal = confirmed + unconfirmed

        val byId = txs.associateBy { it.txid }
        val currentIds = byId.keys
        val currentPending = txs.filter { it.pending }.map { it.txid }.toSet()
        val currentConfirmed = txs.filter { !it.pending }.map { it.txid }.toSet()

        val seenCoinjoins = store.coinjoinsSeen(chain)
        val currentCoinjoins = currentIds.intersect(coinjoins)
        val suppressedCoinjoins = seenCoinjoins + currentCoinjoins

        /*
         * First look, or first look after upgrading from a build without known-txid
         * tracking: seed history silently so years of transactions are not replayed.
         * A genuinely new wallet may still get the one balance-detected notification.
         */
        val known = store.knownTxids(chain)
        if (prevConf < 0 || known == null) {
            if (prevConf < 0 && newTotal > 0)
                notifier.found(chain, confirmed, unconfirmed)

            store.setPendingMap(
                chain,
                currentPending.associateWith { byId[it]?.amount ?: 0L },
            )
            store.setKnownTxids(chain, currentIds)
            store.setLastNotified(chain, confirmed, unconfirmed)

            if (currentCoinjoins.isNotEmpty())
                store.setCoinjoinsSeen(chain, seenCoinjoins + currentCoinjoins)
            return
        }

        val prevMap = store.pendingMap(chain)

        val newIds = currentIds - known
        val newPending = newIds.filter { it in currentPending }
        val directConfirmed = newIds.filter { it in currentConfirmed }
        val nowConfirmed = prevMap.keys.filter { it in currentConfirmed }

        /*
         * 1. Transactions newly seen in the mempool.
         *
         * Each TxConf carries its exact signed wallet effect. If that amount is
         * temporarily unavailable, do not mark the tx known yet: the next look retries.
         */
        newPending.forEach { txid ->
            if (txid in suppressedCoinjoins) return@forEach

            val amount = byId[txid]?.amount ?: return@forEach
            if (amount > 0)
                notifier.mempoolIn(chain, amount, txid)
            else if (amount < 0)
                notifier.mempoolOut(chain, -amount, txid)
        }

        /*
         * 2. Transactions we saw pending that have just confirmed.
         *
         * Prefer the freshly parsed amount; fall back to the exact amount remembered
         * while the tx was pending.
         */
        nowConfirmed.forEach { txid ->
            if (txid in suppressedCoinjoins) return@forEach

            val tx = byId[txid]
            val amount = tx?.amount ?: prevMap[txid] ?: 0L

            if (amount > 0 && tx?.spv == false)
                notifier.unprovenIn(chain, amount, txid)
            else if (amount > 0)
                notifier.firstConfIn(chain, amount, txid)
            else if (amount < 0)
                notifier.firstConfOut(chain, -amount, txid)
        }

        /*
         * 3. Transactions first discovered already confirmed, for example when they
         * arrived and confirmed between two background checks.
         */
        directConfirmed.forEach { txid ->
            if (txid in suppressedCoinjoins) return@forEach

            val tx = byId[txid] ?: return@forEach
            val amount = tx.amount ?: return@forEach

            if (amount > 0 && tx.spv == false)
                notifier.unprovenIn(chain, amount, txid)
            else if (amount > 0)
                notifier.firstConfIn(chain, amount, txid)
            else if (amount < 0)
                notifier.firstConfOut(chain, -amount, txid)
        }

        /*
         * Mark only successfully resolved new transactions as known. Coinjoins are
         * deliberately marked known even if their amount was unavailable: their own
         * round already owns the notification and they must not appear later as a
         * normal payment.
         */
        val resolvedNew = newIds.filter {
            byId[it]?.amount != null || it in suppressedCoinjoins
        }.toSet()
        store.setKnownTxids(chain, known + resolvedNew)

        /*
         * Carry exact pending amounts into the confirmation phase. A temporarily
         * unresolved amount remains 0 here, but because its txid was not added to
         * knownTxids it will still be retried on the next look.
         */
        val nextMap = currentPending.associateWith { txid ->
            byId[txid]?.amount ?: prevMap[txid] ?: 0L
        }
        store.setPendingMap(chain, nextMap)
        store.setLastNotified(chain, confirmed, unconfirmed)

        if (currentCoinjoins.isNotEmpty())
            store.setCoinjoinsSeen(chain, seenCoinjoins + currentCoinjoins)
    }
}
