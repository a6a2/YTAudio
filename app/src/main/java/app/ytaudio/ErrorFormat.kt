package app.ytaudio

/** Builds the "last problem" report users can copy into a bug report. Pure, so it is testable. */
object ErrorFormat {
    /** Links can carry tokens or IP addresses, so they never go into a report. */
    fun sanitize(s: String) = s.replace(Regex("""https?://\S+"""), "<link>")

    fun format(time: String, app: String, os: String, device: String, what: String, t: Throwable?): String = buildString {
        appendLine("YT Audio $app")
        appendLine("Android $os, $device")
        appendLine("Time: $time")
        appendLine("Problem: ${sanitize(what)}")
        var cur = t
        var depth = 0
        while (cur != null && depth < 3) {
            appendLine(if (depth == 0) "Error:" else "Caused by:")
            appendLine("${cur.javaClass.name}: ${sanitize(cur.message.orEmpty()).take(300)}")
            cur.stackTrace.take(if (depth == 0) 8 else 3).forEach { appendLine("  at $it") }
            cur = cur.cause
            depth++
        }
    }.trimEnd()
}
