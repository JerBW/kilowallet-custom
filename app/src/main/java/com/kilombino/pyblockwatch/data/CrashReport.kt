package com.kilombino.pyblockwatch.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * What made the app close the last time it crashed, read from Android's own record of it
 * (ApplicationExitInfo, Android 11+): for a crash inside a native library, such as LND's, the
 * tombstone carries the abort message and the last lines the process logged, where Go writes
 * its "fatal error" or "panic". Kept so it can be copied and sent; nothing leaves the phone
 * on its own.
 */
object CrashReport {
    private const val PREFS = "pyblockwatch"

    class Report(val time: Long, val text: String)

    /** A crash newer than the last one reported, once; null when there is none. */
    fun fresh(ctx: Context): Report? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getLong("crash_seen", 0L)
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrNull() ?: return null
        val crash = info.firstOrNull {
            (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_CRASH) && it.timestamp > seen
        } ?: return null
        val text = describe(ctx, crash)
        p.edit().putLong("crash_seen", crash.timestamp).putString("crash_last", text).apply()
        return Report(crash.timestamp, text)
    }

    /** The last report kept, for settings. */
    fun last(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("crash_last", null)

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun describe(ctx: Context, e: ApplicationExitInfo): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val head = "Kilowallet $version · Android ${Build.VERSION.RELEASE} (${Build.MODEL}) · " +
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp)) +
            " · " + (if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "native crash" else "crash") +
            (e.description?.let { " · $it" } ?: "")
        // The tombstone is a protobuf; its strings (abort message, backtrace frames, logged
        // lines) are plain text inside it. Keep the ones that say what went wrong.
        val raw = runCatching { e.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
        val strings = printable(raw)
        val keys = listOf("fatal error", "panic", "Abort message", "runtime:", "goroutine", "SIGABRT", "lnd", "Exception", "Caused by")
        val wanted = strings.filter { s -> keys.any { s.contains(it, ignoreCase = true) } }.distinct().take(60)
        // What the Lightning node was doing just before (its own log, not the crash itself).
        val lnd = runCatching {
            // The path Lnd.dir uses, without touching the Lnd object (which loads the Go library).
            java.io.File(ctx.noBackupFilesDir, "lnd/logs/bitcoin/mainnet/lnd.log").readLines().takeLast(30)
        }.getOrDefault(emptyList())
        return (listOf(head) + wanted + (if (lnd.isNotEmpty()) listOf("--- lnd.log, last lines") + lnd else emptyList()))
            .joinToString("\n").take(16_000)
    }

    /** Runs of printable ASCII at least 12 long, as `strings` would list them. */
    private fun printable(b: ByteArray): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xff
            if (c in 0x20..0x7e || c == 0x09) cur.append(c.toChar())
            else { if (cur.length >= 12) out += cur.toString(); cur.setLength(0) }
        }
        if (cur.length >= 12) out += cur.toString()
        return out
    }
}
