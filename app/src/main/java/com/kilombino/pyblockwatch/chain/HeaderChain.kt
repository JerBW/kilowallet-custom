package com.kilombino.pyblockwatch.chain

import com.kilombino.pyblockwatch.crypto.BlockHeader
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.io.File
import java.math.BigInteger

/**
 * The BTC (BLAKE2b) chain's block headers, verified on this phone from a checkpoint built into
 * the app, so a payment can be proven against blocks the server cannot make up (SPV, as
 * Electrum or Sparrow do on a desktop).
 *
 * Every header the server hands over must link to the one before it, carry the height it claims,
 * have exactly the difficulty the consensus rules give it (Bitcoin's retarget every 2016 blocks,
 * at most ×4 either way) and meet its own target. Faking one block costs mining a real one at
 * the network's difficulty. A transaction is then proven by its merkle branch against the
 * verified header of its block.
 *
 * Kept in the scan cache directory; a reorganisation the server shows is followed only when its
 * branch is valid and longer than the one held.
 */
object HeaderChain {
    /** A difficulty period's first block, checked against Kilombino's node: 14 Oct 2026. */
    const val CHECKPOINT_HEIGHT = 975_744
    const val CHECKPOINT_HASH = "00000000000000007f85bb387c9128bc606a483ad7a7784701517effadca9c34"
    private const val CHECKPOINT_RAW =
        "000000a0914d7dc839e917db1d227113b0517f95ac01a8369f244b8b0000000000000000a02efc7e74d0a8eb4a6264d4369f994a" +
        "c640b7348885453174553d282bc55030d801c46a2ad80019b3a4d855126bb672d801c46a00000000b18cf0180c00000000000000" +
        "000000000b00042fb22c6a4c4863dfc52519e00a20f73e0080e30e000000000000000000000000000000000000000000000000" +
        "000000000000000000"

    const val INTERVAL = 2016
    private const val TIMESPAN = 14L * 24 * 60 * 60
    private val POW_LIMIT = BigInteger("00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16)
    private const val SIZE = 164
    /** How far back a reorganisation is looked for before the server is called wrong. */
    private const val MAX_REORG = 144

    @Volatile private var dir: File? = null
    private val chain = ArrayList<BlockHeader.Parsed>()
    private val hashes = ArrayList<String>()
    /** txid@blockhash proven so far: a block's contents never change. */
    private val proven = HashSet<String>()

    /** The highest verified height. */
    val tip: Int @Synchronized get() { ensure(); return CHECKPOINT_HEIGHT + chain.size - 1 }

    /** The checkpoint alone, when no directory was set (a process that never looked at a chain). */
    private fun ensure() {
        if (chain.isNotEmpty()) return
        val cp = BlockHeader.parse(CHECKPOINT_RAW)
        chain += cp; hashes += BlockHeader.blockHash(cp)
    }

    @Synchronized
    fun useDir(d: File) {
        if (dir == d && chain.isNotEmpty()) return
        dir = d
        chain.clear(); hashes.clear(); proven.clear()
        val cp = BlockHeader.parse(CHECKPOINT_RAW)
        check(BlockHeader.blockHash(cp) == CHECKPOINT_HASH) { "the built-in checkpoint does not hash to its block" }
        chain += cp; hashes += CHECKPOINT_HASH
        // Headers verified on an earlier run; anything that does not start from the checkpoint is dropped.
        runCatching {
            val f = File(d, FILE)
            if (f.exists()) {
                val b = f.readBytes()
                if (b.size >= SIZE && b.size % SIZE == 0 && b.copyOfRange(0, SIZE).contentEquals(cp.raw)) {
                    for (o in SIZE until b.size step SIZE) {
                        val h = BlockHeader.Parsed(b.copyOfRange(o, o + SIZE))
                        val hash = BlockHeader.blockHash(h)
                        if (prevHash(h) != hashes.last()) break
                        chain += h; hashes += hash
                    }
                }
            }
        }
        runCatching { File(d, PROVEN).takeIf { it.exists() }?.readLines()?.let { proven.addAll(it.filter(String::isNotBlank)) } }
    }

    /** The verified hash of block [height], or null outside what is verified. */
    @Synchronized
    fun hashAt(height: Int): String? { ensure(); return hashes.getOrNull(height - CHECKPOINT_HEIGHT) }

    /**
     * Brings the verified chain up to the server's tip. Returns the verified tip, or null when
     * the server's headers do not verify (it is lying, or on another chain): nothing it said is
     * kept then. A server that is merely behind leaves the verified chain as it is.
     */
    @Synchronized
    fun sync(client: ElectrumClient): Int? {
        if (client.isOwnNode) return null
        ensure()
        val serverTip = client.blockHeight()
        if (serverTip <= tip) {
            // Behind or level: its header at its tip must be ours, or it shows another branch.
            if (serverTip < CHECKPOINT_HEIGHT) return null
            val theirs = BlockHeader.blockHash(BlockHeader.parse(client.headers(serverTip, 1).first()))
            return if (theirs == hashAt(serverTip)) tip else null
        }
        var next = tip + 1
        var reorgChecked = false
        while (next <= serverTip) {
            val batch = client.headers(next, minOf(INTERVAL, serverTip - next + 1)).map(BlockHeader::parse)
            if (batch.isEmpty()) break
            if (prevHash(batch.first()) != hashes.last()) {
                // The server's branch leaves ours somewhere below: find where, then take it only
                // if it verifies all the way and ends higher than ours (checked below by height).
                if (reorgChecked) return null
                reorgChecked = true
                val fork = forkPoint(client) ?: return null
                truncateTo(fork)
                next = fork + 1
                continue
            }
            for ((k, h) in batch.withIndex()) {
                val height = next + k
                if (!valid(h, height)) { save(); return null }
                chain += h; hashes += BlockHeader.blockHash(h)
            }
            next += batch.size
        }
        save()
        return tip
    }

    /**
     * Whether [txid] is in block [height], by its merkle branch against the verified header:
     * true or false, or null when the block is not verified yet or the server gave no branch.
     * Below the checkpoint, where no header is held, the header the server gives must meet its
     * own target at no less than [floorBits].
     */
    @Synchronized
    fun proves(client: ElectrumClient, txid: String, height: Int, floorBits: Long): Boolean? {
        if (client.isOwnNode) return true // your own node validated the block itself
        ensure()
        if (height < CHECKPOINT_HEIGHT) return runCatching {
            val header = BlockHeader.parse(client.blockHeader(height))
            val (branch, pos) = client.merkle(txid, height)
            BlockHeader.inBlock(txid, branch, pos, header) && BlockHeader.meetsItsTarget(header) &&
                BlockHeader.target(header.bits) <= BlockHeader.target(floorBits)
        }.getOrNull()
        val header = chain.getOrNull(height - CHECKPOINT_HEIGHT) ?: return null
        val key = "$txid@${hashes[height - CHECKPOINT_HEIGHT]}"
        if (key in proven) return true
        val (branch, pos) = runCatching { client.merkle(txid, height) }.getOrNull() ?: return null
        val ok = BlockHeader.inBlock(txid, branch, pos, header)
        if (ok) {
            proven += key
            runCatching { dir?.let { File(it, PROVEN).appendText(key + "\n") } }
        }
        return ok
    }

    // ------------------------------------------------------------------------------- rules

    /** Header [h] at [height], on top of the verified chain's last header. */
    private fun valid(h: BlockHeader.Parsed, height: Int): Boolean {
        if (h.raw.size != SIZE || !h.extended) return false
        val prev = chain.last()
        if (prevHash(h) != hashes.last()) return false
        if (embeddedHeight(h) != height.toLong()) return false
        if (h.bits != expectedBits(height, prev.bits, prev.time) { chain.getOrNull(it - CHECKPOINT_HEIGHT)?.time }) return false
        return BlockHeader.meetsItsTarget(h)
    }

    /**
     * The difficulty block [height] must have: the previous block's, except on a period's first
     * block, where it is retargeted from how long the last 2016 blocks took (clamped to ×4).
     * [timeAt] gives a verified block's time.
     */
    internal fun expectedBits(height: Int, prevBits: Long, prevTime: Long, timeAt: (Int) -> Long?): Long? {
        if (height % INTERVAL != 0) return prevBits
        val first = timeAt(height - INTERVAL) ?: return null
        val span = (prevTime - first).coerceIn(TIMESPAN / 4, TIMESPAN * 4)
        val t = BlockHeader.target(prevBits).multiply(BigInteger.valueOf(span)).divide(BigInteger.valueOf(TIMESPAN))
        return compact(t.min(POW_LIMIT))
    }

    /** The compact ("bits") encoding of a target, as Bitcoin's GetCompact. */
    internal fun compact(target: BigInteger): Long {
        var n = (target.bitLength() + 7) / 8
        var c = if (n <= 3) target.shiftLeft(8 * (3 - n)).toLong() else target.shiftRight(8 * (n - 3)).toLong()
        c = c and 0xffffff
        if (c and 0x800000L != 0L) { c = c ushr 8; n++ }
        return c or (n.toLong() shl 24)
    }

    private fun prevHash(h: BlockHeader.Parsed): String = h.raw.copyOfRange(4, 36).reversedArray().toHex()
    private fun embeddedHeight(h: BlockHeader.Parsed): Long =
        (0 until 4).fold(0L) { a, i -> a or ((h.raw[128 + i].toLong() and 0xff) shl (8 * i)) }
    private val BlockHeader.Parsed.time: Long
        get() = (0 until 4).fold(0L) { a, i -> a or ((raw[68 + i].toLong() and 0xff) shl (8 * i)) }

    /** The highest height at which the server's branch and ours agree, within [MAX_REORG]. */
    private fun forkPoint(client: ElectrumClient): Int? {
        val low = maxOf(CHECKPOINT_HEIGHT, tip - MAX_REORG)
        val theirs = runCatching { client.headers(low, tip - low + 1) }.getOrNull() ?: return null
        var fork: Int? = null
        for ((k, hex) in theirs.withIndex()) {
            if (BlockHeader.blockHash(BlockHeader.parse(hex)) == hashAt(low + k)) fork = low + k else break
        }
        return fork
    }

    private fun truncateTo(height: Int) {
        val keep = height - CHECKPOINT_HEIGHT + 1
        while (chain.size > keep) { chain.removeAt(chain.size - 1); hashes.removeAt(hashes.size - 1) }
    }

    private fun save() {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            val tmp = File(d, "$FILE.tmp")
            tmp.outputStream().use { o -> chain.forEach { o.write(it.raw) } }
            tmp.renameTo(File(d, FILE))
        }
    }

    private const val FILE = "headers-blake2b.bin"
    private const val PROVEN = "proven-blake2b.txt"

    /** For tests: start over from the checkpoint alone, without a directory. */
    @Synchronized
    internal fun resetForTest() {
        dir = null; chain.clear(); hashes.clear(); proven.clear()
        val cp = BlockHeader.parse(CHECKPOINT_RAW)
        chain += cp; hashes += BlockHeader.blockHash(cp)
    }

    /** For tests: verify and append raw headers as a server would hand them over. */
    @Synchronized
    internal fun appendForTest(raw: List<String>): Boolean {
        for (hex in raw) {
            val h = BlockHeader.parse(hex)
            if (!valid(h, tip + 1)) return false
            chain += h; hashes += BlockHeader.blockHash(h)
        }
        return true
    }
}
