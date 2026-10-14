package com.kilombino.pyblockwatch.chain

import com.kilombino.pyblockwatch.crypto.BlockHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The verified header chain against the real BTC chain, as Kilombino's node holds it (14 Oct 2026). */
class HeaderChainTest {
    private val real: List<String> =
        javaClass.classLoader!!.getResource("headers_blake2b_975745.txt")!!.readText().lines().filter { it.isNotBlank() }

    @Before fun fresh() = HeaderChain.resetForTest()

    @Test fun theCheckpointIsTheRealBlock() {
        assertEquals(HeaderChain.CHECKPOINT_HASH, HeaderChain.hashAt(HeaderChain.CHECKPOINT_HEIGHT))
        assertEquals(HeaderChain.CHECKPOINT_HEIGHT, HeaderChain.tip)
    }

    @Test fun theRealChainVerifies() {
        assertTrue(HeaderChain.appendForTest(real))
        assertEquals(HeaderChain.CHECKPOINT_HEIGHT + real.size, HeaderChain.tip)
    }

    @Test fun aHeaderThatDoesNotLinkIsRefused() {
        assertFalse(HeaderChain.appendForTest(listOf(real[1])))      // skips block 975745
        assertEquals(HeaderChain.CHECKPOINT_HEIGHT, HeaderChain.tip)
    }

    @Test fun anyChangedByteIsRefused() {
        // The merkle root (a made-up block's contents), the bits, the embedded height, a nonce.
        for (offset in listOf(40, 72, 128, 76)) {
            HeaderChain.resetForTest()
            val raw = real[0]
            val i = offset * 2
            val flipped = raw.substring(0, i) + (if (raw[i] == '0') '1' else '0') + raw.substring(i + 1)
            assertFalse("byte $offset", HeaderChain.appendForTest(listOf(flipped)))
        }
    }

    @Test fun anEasierDifficultyIsRefusedEvenWithItsWork() {
        // Same header, bits made easier: its hash may well meet the easier target, but the
        // difficulty is not the one the rules give that height.
        val raw = real[0]
        val easier = raw.substring(0, 144) + "ffff0020" + raw.substring(152)
        assertFalse(HeaderChain.appendForTest(listOf(easier)))
    }

    @Test fun retargetsAsTheNetworkDid() {
        // Block 975 744: 2016 blocks from 973 728 (time 1790130520) to 975 743 (time 1791230414) at 0x1900edba.
        assertEquals(0x1900d82aL, HeaderChain.expectedBits(975_744, 0x1900edbaL, 1791230414L) { if (it == 973_728) 1790130520L else null })
        // Block 973 728: from 971 712 (1789236249) to 973 727 (1790129981) at 0x190141c0.
        assertEquals(0x1900edbaL, HeaderChain.expectedBits(973_728, 0x190141c0L, 1790129981L) { if (it == 971_712) 1789236249L else null })
        // Inside a period nothing changes.
        assertEquals(0x1900d82aL, HeaderChain.expectedBits(975_745, 0x1900d82aL, 0L) { null })
    }

    @Test fun compactRoundTrips() {
        for (bits in listOf(0x1900d82aL, 0x1900edbaL, 0x1a008d4fL, 0x1702353dL, 0x1d00ffffL))
            assertEquals(bits, HeaderChain.compact(BlockHeader.target(bits)))
    }
}
