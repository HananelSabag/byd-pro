package com.example.byd.bus

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone's debug log: one line per step of every command (and the server / network events
 * around it), kept as a ring in memory and in a small rotating file, so "it didn't work at 8:14"
 * can be traced afterwards. Settings → Share debug log sends it. Never contains the server
 * address, the password, the VIN or a position ([Redactor]).
 */
class BusLog(
    private val dir: File,
    private val redactor: Redactor,
    private val ringSize: Int = 500,
    private val maxFileBytes: Long = 256 * 1024L,
) : BusLogger {
    private val ring = ArrayDeque<String>(ringSize)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "bus-log").apply { isDaemon = true } }
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var echo: ((String) -> Unit)? = null // logcat in debug builds

    override fun log(id: String?, message: String) {
        val line = synchronized(ring) {
            "${time.format(Date())} ${id?.let(CommandBus::short) ?: "--------"} ${redactor.clean(message)}".also {
                if (ring.size >= ringSize) ring.removeFirst()
                ring.addLast(it)
            }
        }
        echo?.invoke(line)
        writer.execute { append(line) }
    }

    /** Everything kept: the rotated file, then the current one (or the in-memory ring if the files are gone). */
    fun export(): String {
        val fromFiles = runCatching {
            writer.submit<String> { listOf(old, current).filter { it.exists() }.joinToString("") { it.readText() } }.get()
        }.getOrNull().orEmpty()
        return fromFiles.ifBlank { synchronized(ring) { ring.joinToString("\n") } }
    }

    private val current get() = File(dir, "bus.log")
    private val old get() = File(dir, "bus.1.log")

    private fun append(line: String) {
        try {
            dir.mkdirs()
            if (current.length() > maxFileBytes) {
                old.delete()
                current.renameTo(old)
            }
            current.appendText(line + "\n")
        } catch (e: Exception) {
            // A full disk must not break commands; the ring still has it.
        }
    }
}

/** Removes what must never leave the phone in a shared log. */
class Redactor(private val secrets: () -> List<String>) {
    fun clean(text: String): String {
        var out = text
        secrets().filter { it.length >= 4 }.forEach { out = out.replace(it, "<redacted>", ignoreCase = true) }
        out = BEARER.replace(out, "Bearer <redacted>")
        out = URL.replace(out, "<url>")
        out = VIN.replace(out, "<vin>")
        out = COORD.replace(out, "<pos>")
        return out
    }

    private companion object {
        val BEARER = Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE)
        val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        val VIN = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")
        val COORD = Regex("-?\\d{1,3}\\.\\d{5,}")
    }
}
