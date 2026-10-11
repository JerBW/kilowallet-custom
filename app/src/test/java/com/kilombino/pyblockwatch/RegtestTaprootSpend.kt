package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual check against a BLAKE2b regtest node: with REGTEST_TR="txid:vout:sats" (a coin on
 * m/86'/0'/0'/0/0 of the "abandon … about" seed) and REGTEST_WPKH (one on m/84'/0'/0'/0/0), prints
 * signed transactions for `testmempoolaccept`. Skipped when the variables are not set.
 */
class RegtestTaprootSpend {
    @Test
    fun printSpends() {
        val tr = System.getenv("REGTEST_TR"); val wp = System.getenv("REGTEST_WPKH")
        assumeTrue(tr != null && wp != null)
        val master = Bip32Priv.fromSeed(Bip39.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "), ""))
        fun input(spec: String, path: String, type: ScriptType): TxBuilder.Input {
            val (txid, vout, sats) = spec.split(":")
            val n = Bip32Priv.derivePath(master, path)
            return TxBuilder.Input(txid, vout.toInt(), sats.toLong(), n.key, n.publicKey(), 0xfffffffdL, type)
        }
        val a = input(tr!!, "m/86'/0'/0'/0/0", ScriptType.P2TR)
        val b = input(wp!!, "m/84'/0'/0'/0/0", ScriptType.P2WPKH)
        val both = listOf(a, b)
        println("MIXED_UNIFIED " + TxBuilder.build(both, listOf(TxBuilder.Output(a.spentScript, a.value + b.value - 1000)), unified = true).rawHex)
        println("TR_UNIFIED " + TxBuilder.build(listOf(a), listOf(TxBuilder.Output(b.spentScript, a.value - 500)), unified = true).rawHex)
        println("TR_DEFAULT " + TxBuilder.build(listOf(a), listOf(TxBuilder.Output(b.spentScript, a.value - 400)), unified = false).rawHex)
    }
}
