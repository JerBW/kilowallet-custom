package com.kilombino.pyblockwatch.ark

import android.content.Context
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.HeaderChain
import com.kilombino.pyblockwatch.data.Scanner
import com.kilombino.pyblockwatch.data.Store

/**
 * The Ark engine learns the chain from one Esplora server (mempool.kilombino.com); the wallet
 * has its own BTC Electrum server and the verified header chain. This holds the first against
 * the second, so a chain source that is behind, or hides or makes up transactions, shows:
 *
 *  - its tip against the Electrum server's, verified;
 *  - every on-chain transaction the engine calls confirmed (the deposit's, withdrawals'):
 *    the Electrum server must prove it into the verified block of that height.
 *
 * Nothing is blocked (a renewal or an exit must never wait on this); it is said on the Ark
 * screen and, in the background, in a notification.
 */
object ArkCrossCheck {
    /** Blocks the two tips may differ by: one or two blocks of propagation. */
    private const val TIP_SLACK = 3
    /** On-chain transactions checked per run, newest first: a proof is cached once made. */
    private const val MAX_TXS = 30

    /** What disagrees, in words; null when everything agrees or nothing could be compared. */
    fun check(ctx: Context): String? {
        val engineTip = Ark.tip() ?: return null
        val store = Store(ctx)
        val endpoint = store.endpoint(Chain.BLAKE2B)
        val client = ElectrumClient(endpoint, store.pinnedFingerprint(endpoint))
        return try {
            client.connect()
            val tip = if (client.isOwnNode) client.blockHeight() else HeaderChain.sync(client)
                ?: return "Your BTC server's block headers don't verify, so the Ark engine's chain data can't be checked against it."
            if (kotlin.math.abs(engineTip - tip) > TIP_SLACK)
                return "The Ark engine's chain source (block $engineTip) and your BTC server (block $tip) disagree. One of them " +
                    "is behind or not telling the whole story: wait before moving Ark funds, and check your node."
            val confirmed = Ark.depositTxs().filter { it.height != null }.map { it.txid to it.height!! }.take(MAX_TXS)
            for ((txid, height) in confirmed) {
                if (height > tip) continue
                val ok = HeaderChain.proves(client, txid, height, Scanner.SPV_FLOOR_BITS)
                if (ok != true) return "The Ark engine says transaction ${txid.take(8)}…${txid.takeLast(6)} confirmed in block " +
                    "$height, but your BTC server ${if (ok == false) "proves otherwise" else "can't prove it"}. Don't count on that " +
                    "movement, and wait before moving Ark funds."
            }
            null
        } catch (e: Exception) {
            null // the Electrum server is unreachable: nothing to compare with, not a disagreement
        } finally {
            client.close()
        }
    }
}
