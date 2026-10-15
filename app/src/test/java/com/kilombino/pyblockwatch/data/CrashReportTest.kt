package com.kilombino.pyblockwatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tombstone built field by field as tombstone.proto lays it out. */
class CrashReportTest {
    private fun varint(v: Long): ByteArray { var x = v; val o = ArrayList<Byte>()
        while (x >= 0x80) { o += ((x and 0x7f) or 0x80).toByte(); x = x ushr 7 }; o += x.toByte(); return o.toByteArray() }
    private fun str(field: Int, s: String) = bytes(field, s.toByteArray())
    private fun bytes(field: Int, b: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(b.size.toLong()) + b
    private fun num(field: Int, v: Long) = varint(field.toLong() shl 3) + varint(v)

    @Test fun readsAbortSignalCauseAndLogs() {
        val signal = num(1, 6) + str(2, "SIGABRT") + num(3, -6 and 0xff) + str(4, "SI_TKILL")
        val log = { tag: String, msg: String -> bytes(2, str(1, "10-15 00:19:09") + num(2, 4242) + num(4, 6) + str(5, tag) + str(6, msg)) }
        val buffer = str(1, "main") + log("Go", "fatal error: unexpected signal during runtime execution") + log("Go", "goroutine 17 [running]:")
        val tomb = num(5, 4242) + bytes(10, signal) + str(14, "hardened_malloc: fatal allocator error: invalid free") +
            bytes(15, str(1, "[MTE]: Use After Free")) + bytes(18, buffer) + str(19, "/data/user/10/x/lnd.log")
        val out = CrashReport.summarize(tomb)
        assertEquals("Abort message: hardened_malloc: fatal allocator error: invalid free", out[0])
        assertTrue(out[1], out[1].contains("SIGABRT") && out[1].contains("SI_TKILL"))
        assertEquals("Cause: [MTE]: Use After Free", out[2])
        assertTrue(out.contains("Go: fatal error: unexpected signal during runtime execution"))
        assertTrue(out.last() == "Go: goroutine 17 [running]:")
    }

    @Test fun anythingUnreadableStillGivesTheTellingStrings() {
        val junk = byteArrayOf(0x7f) + "....hardened_malloc: fatal allocator error....".toByteArray()
        assertTrue(CrashReport.summarize(junk).any { it.contains("fatal allocator error") })
    }
}
