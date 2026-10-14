package com.kilombino.pyblockwatch.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Against the real default BTC server; runs only with KW_LIVE=1 (it needs the network). */
class HeaderChainLiveTest {
    @Test fun syncsAndProvesFromTheDefaultServer() {
        assumeTrue(System.getenv("KW_LIVE") == "1")
        HeaderChain.resetForTest()
        val c = ElectrumClient(NodeEndpoint.default(Chain.BLAKE2B), null)
        c.connect()
        try {
            val tip = HeaderChain.sync(c)
            assertNotNull("the server's headers verify", tip)
            assertEquals(c.blockHeight(), tip)
            // The last transaction of block 976 000, as Kilombino's node lists it.
            val txid = "1e0315c8575c431b38e4422e1d7c55f652065d04e3d64b4a4694bd078e48b7d6"
            assertEquals(true, HeaderChain.proves(c, txid, 976_000, 0x191b0540L))
            // Claimed in another block: the server has no branch there, or it leads elsewhere.
            assertNotEquals(true, HeaderChain.proves(c, txid, 976_001, 0x191b0540L))
            println("verified tip $tip; $txid proven in 976000")
        } finally { c.close() }
    }
}
