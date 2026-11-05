package app.ytaudio

/** Chooses which audio stream to play. Generic so it is testable without NewPipe types. */
object AudioPick {
    /**
     * Prefers original-language tracks (multi-language videos also list dubs), then the highest bitrate,
     * or with [saver] the lowest known bitrate.
     */
    fun <T> choose(items: List<T>, bitrate: (T) -> Int, original: (T) -> Boolean, saver: Boolean): T? {
        val pool = items.filter(original).ifEmpty { items }
        return if (saver) pool.filter { bitrate(it) > 0 }.minByOrNull(bitrate) ?: pool.firstOrNull()
        else pool.maxByOrNull(bitrate)
    }
}
