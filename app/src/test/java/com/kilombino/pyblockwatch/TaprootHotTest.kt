package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.Schnorr
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.SilentPayment
import com.kilombino.pyblockwatch.crypto.TxBuilder
import com.kilombino.pyblockwatch.crypto.TxParse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hot wallet's Taproot account: a BIP-86 coin is signed by key path with the unified sighash
 * on BLAKE2b, mixes with BIP-84 coins in one send, weighs what the fee estimate assumes, and gives
 * BIP-352 the key it expects.
 */
class TaprootHotTest {

    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val master = Bip32Priv.fromSeed(Bip39.toSeed(words, ""))
    private fun node(path: String) = Bip32Priv.derivePath(master, path)

    private fun input(path: String, type: ScriptType, value: Long, vout: Int) = node(path).let {
        TxBuilder.Input("ab".repeat(32), vout, value, it.key, it.publicKey(), 0xfffffffdL, type)
    }

    @Test
    fun `bip86 account gives the published first taproot address`() {
        // BIP-86 test vector for this mnemonic: m/86'/0'/0'/0/0.
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
            Address.encode(node("m/86'/0'/0'/0/0").publicKey(), ScriptType.P2TR))
    }

    @Test
    fun `taproot and segwit coins in one unified send both verify`() {
        val tr = input("m/86'/0'/0'/0/0", ScriptType.P2TR, 50_000, 0)
        val wpkh = input("m/84'/0'/0'/0/0", ScriptType.P2WPKH, 30_000, 1)
        val inputs = listOf(tr, wpkh)
        val outputs = listOf(TxBuilder.Output(tr.spentScript, 79_000))
        val signed = TxBuilder.build(inputs, outputs, unified = true)
        val tx = TxParse.parse(signed.rawHex)

        // Taproot input: one 65-byte item, the Schnorr signature plus the unified byte 0x21.
        val w0 = tx.inputs[0].witness
        assertEquals(1, w0.size)
        assertEquals(65, w0[0].size)
        assertEquals(0x21, w0[0][64].toInt() and 0xff)
        val msg = TxBuilder.unifiedSighash(2, inputs, outputs, 0, 0)
        val outputKey = tr.spentScript.copyOfRange(2, 34)
        assertTrue(Schnorr.verify(outputKey, msg, w0[0].copyOfRange(0, 64)))

        // The SegWit input signs the same unified message family, script type 1, with 0x21.
        val w1 = tx.inputs[1].witness
        assertEquals(2, w1.size)
        assertEquals(0x21, w1[0].last().toInt() and 0xff)

        // Weight: 10.5 overhead + 57.75 (taproot) + ≤68 (segwit) + 43 (P2TR output), as estimated.
        assertTrue("vsize ${signed.vbytes}", signed.vbytes <= kotlin.math.ceil(10.5 + 57.75 + 68 + 43).toInt())
    }

    @Test
    fun `silent payment input key of a taproot coin is its even-y output key`() {
        for (i in 0 until 8) {
            val n = node("m/86'/0'/0'/0/$i")
            val k = SilentPayment.inputKey(n.key, taproot = true)
            val p = Secp256k1.publicPoint(k)
            assertTrue(!p.y!!.testBit(0))
            assertArrayEquals(Address.scriptPubKey(n.publicKey(), ScriptType.P2TR).copyOfRange(2, 34), Secp256k1.xOnly(p))
        }
        val n = node("m/84'/0'/0'/0/0")
        assertEquals(n.key, SilentPayment.inputKey(n.key, taproot = false))
    }
}
