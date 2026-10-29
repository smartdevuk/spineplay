package dev.family.audiobooks

import org.json.JSONArray
import org.json.JSONObject

data class Identity(val id: String, val name: String, val token: String) {
    override fun toString() = "Identity(id=$id, name=$name, token=[redacted])"
}
data class HomeProfile(val id: String, val name: String, val protected: Boolean)
data class PlexServer(val id: String, val name: String, val token: String, val connections: List<String>) {
    override fun toString() = "PlexServer(id=$id, name=$name, token=[redacted])"
}
data class Session(val profile: Identity, val serverId: String, val serverName: String,
    val serverUrl: String, val serverToken: String, val libraryId: String,
    val libraryIds: List<String> = listOf(libraryId).filter(String::isNotBlank), val libraryNames: List<String> = emptyList()) {
    val userKey get() = "$serverId/${profile.id}"
    override fun toString() = "Session(profile=${profile.id}, server=$serverId, library=$libraryId)"
    fun json() = JSONObject().put("profileId", profile.id).put("profileName", profile.name)
        .put("profileToken", profile.token).put("serverId", serverId).put("serverName", serverName)
        .put("serverUrl", serverUrl).put("serverToken", serverToken).put("libraryId", libraryId)
        .put("libraryIds", JSONArray(libraryIds)).put("libraryNames", JSONArray(libraryNames))
    companion object {
        fun parse(o: JSONObject) = Session(Identity(o.getString("profileId"), o.getString("profileName"),
            o.getString("profileToken")), o.getString("serverId"), o.getString("serverName"),
            o.getString("serverUrl"), o.getString("serverToken"), o.getString("libraryId"),
            o.optJSONArray("libraryIds")?.strings() ?: listOf(o.getString("libraryId")), o.optJSONArray("libraryNames")?.strings().orEmpty())
    }
}
data class PlexLibrary(val id: String, val name: String)
data class ServerLibraries(val session: Session, val libraries: List<PlexLibrary>)
data class BookSummary(val id: String, val title: String, val author: String, val thumb: String,
    val addedAt: Long = 0, val libraryId: String = "", val authorId: String = "", val asin: String = "",
    val series: List<SeriesRef> = emptyList()) {
    val hasAuthor get() = author.isNotBlank() && author.trim().lowercase() !in setOf("[unknown]", "unknown", "unknown artist", "various artists")
    val needsMetadata get() = !hasAuthor || thumb.isBlank() || Regex("(?i)(?:\\bcd\\s*\\d+|\\.disc$)").containsMatchIn(title)
}
fun orderedBooks(books: List<BookSummary>) = books.sortedWith(compareBy<BookSummary> { it.needsMetadata }
    .thenByDescending { it.addedAt }.thenBy { it.title.lowercase() }.thenBy { it.id })
data class SeriesRef(val name: String, val number: Double? = null, val collection: Boolean = false)
data class BookDetails(val description: String = "", val narrators: List<String> = emptyList(), val publisher: String = "", val year: String = "")
fun seriesFromPlex(moods: List<String>, sortTitle: String, collections: List<String>): List<SeriesRef> {
    val series = moods.filter { it.startsWith("Series:", true) }.mapNotNull { tag ->
        val name = tag.substringAfter(':').trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val number = Regex("^${Regex.escape(name)},\\s*Book\\s+(\\d+(?:\\.\\d+)?)\\s+-", RegexOption.IGNORE_CASE)
            .find(sortTitle)?.groupValues?.get(1)?.toDoubleOrNull()
        SeriesRef(name, number)
    }
    return (series + collections.filter(String::isNotBlank).map { SeriesRef(it, collection = true) }).distinctBy { it.name.lowercase() }
}
fun orderedSeries(books: List<BookSummary>, name: String): List<BookSummary> = books.sortedWith(
    compareBy<BookSummary> { it.series.firstOrNull { series -> series.name.equals(name, true) }?.number ?: Double.MAX_VALUE }
        .thenBy { it.title.lowercase() }.thenBy { it.id })
data class AudioTrack(val id: String, val title: String, val partKey: String, val durationMs: Long, val sizeBytes: Long = 0)
data class Chapter(val title: String, val startMs: Long, val endMs: Long, val isPart: Boolean = false) {
    val durationMs get() = (endMs - startMs).coerceAtLeast(1)
}
data class AudioBook(val summary: BookSummary, val tracks: List<AudioTrack>, val chapters: List<Chapter>,
    val remotePositionMs: Long = 0, val remoteUpdatedMs: Long = 0, val chapterSource: String = "Plex", val details: BookDetails = BookDetails()) {
    val durationMs get() = tracks.sumOf { it.durationMs }
    fun chapterAt(positionMs: Long): Chapter? = chapters.lastOrNull { positionMs >= it.startMs } ?: chapters.firstOrNull()
    fun locate(positionMs: Long): Pair<Int, Long> {
        require(tracks.isNotEmpty())
        var remaining = positionMs.coerceIn(0, durationMs)
        tracks.forEachIndexed { index, track ->
            if (remaining < track.durationMs || index == tracks.lastIndex) return index to remaining.coerceAtMost(track.durationMs)
            remaining -= track.durationMs
        }
        return 0 to 0L
    }
    fun absolute(trackIndex: Int, localMs: Long) = (tracks.take(trackIndex.coerceAtLeast(0)).sumOf { it.durationMs } + localMs)
        .coerceIn(0, durationMs)
    fun json(): String = JSONObject().put("id", summary.id).put("title", summary.title)
        .put("author", summary.author).put("thumb", summary.thumb).put("addedAt", summary.addedAt)
        .put("libraryId", summary.libraryId).put("authorId", summary.authorId).put("asin", summary.asin).put("chapterSource", chapterSource)
        .put("remotePosition", remotePositionMs).put("remoteUpdated", remoteUpdatedMs)
        .put("series", JSONArray(summary.series.map { JSONObject().put("name", it.name).put("number", it.number).put("collection", it.collection) }))
        .put("details", JSONObject().put("description", details.description).put("narrators", JSONArray(details.narrators)).put("publisher", details.publisher).put("year", details.year))
        .put("tracks", JSONArray(tracks.map { JSONObject().put("id", it.id).put("title", it.title)
            .put("partKey", it.partKey).put("duration", it.durationMs).put("size", it.sizeBytes) }))
        .put("chapters", JSONArray(chapters.map { JSONObject().put("title", it.title)
            .put("start", it.startMs).put("end", it.endMs).put("part", it.isPart) })).toString()
    companion object {
        fun parse(text: String): AudioBook {
            val o = JSONObject(text)
            return AudioBook(BookSummary(o.getString("id"), o.getString("title"), o.getString("author"), o.optString("thumb"),
                o.optLong("addedAt"), o.optString("libraryId"), o.optString("authorId"), o.optString("asin"),
                o.optJSONArray("series")?.objects()?.map { SeriesRef(it.getString("name"), if (it.has("number") && !it.isNull("number")) it.getDouble("number") else null, it.optBoolean("collection")) }.orEmpty()),
                o.getJSONArray("tracks").objects().map { AudioTrack(it.getString("id"), it.getString("title"), it.getString("partKey"), it.getLong("duration"), it.optLong("size")) },
                o.getJSONArray("chapters").objects().map { Chapter(it.getString("title"), it.getLong("start"), it.getLong("end"), it.optBoolean("part")) },
                remotePositionMs = o.optLong("remotePosition"), remoteUpdatedMs = o.optLong("remoteUpdated"), chapterSource = o.optString("chapterSource", "Plex"),
                details = o.optJSONObject("details")?.let { BookDetails(it.optString("description"), it.optJSONArray("narrators")?.strings().orEmpty(), it.optString("publisher"), it.optString("year")) } ?: BookDetails())
        }
    }
}

/** Cover gaps and reject broken chapter metadata without inventing narrative chapters. */
fun normalizeChapters(raw: List<Chapter>, durationMs: Long, fallbackTitle: String): List<Chapter> {
    if (durationMs <= 0) return emptyList()
    val result = mutableListOf<Chapter>()
    var cursor = 0L
    raw.sortedBy { it.startMs }.forEach { item ->
        var start = item.startMs.coerceIn(0, durationMs).coerceAtLeast(cursor)
        val end = item.endMs.coerceIn(0, durationMs)
        if (end > start) {
            if (start > cursor && start - cursor <= 1000) {
                if (result.isEmpty()) start = cursor else result[result.lastIndex] = result.last().copy(endMs = start)
            } else if (start > cursor) result += Chapter("$fallbackTitle · section ${result.size + 1}", cursor, start, true)
            result += item.copy(title = item.title.ifBlank { "Section ${result.size + 1}" }, startMs = start, endMs = end, isPart = item.isPart || item.title.isBlank())
            cursor = end
        }
    }
    if (cursor < durationMs && result.isNotEmpty() && durationMs - cursor <= 1000) {
        result[result.lastIndex] = result.last().copy(endMs = durationMs)
    } else if (cursor < durationMs) result += Chapter(if (result.isEmpty()) fallbackTitle else "Final section", cursor, durationMs,
        result.isNotEmpty() || !Regex("\\bchapter\\b", RegexOption.IGNORE_CASE).containsMatchIn(fallbackTitle))
    return result
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun navigationSections(durationMs: Long): List<Chapter> = (0L until durationMs step 900000L).mapIndexed { index, start ->
    Chapter("Listening section ${index + 1}", start, (start + 900000L).coerceAtMost(durationMs), true)
}
data class ListeningBook(val book: AudioBook, val progress: SavedProgress)
data class BrowseState(val query: String = "", val author: String? = null, val index: Int = 0, val offset: Int = 0,
    val downloadsOnly: Boolean = false, val status: String = "all", val series: String? = null)
enum class ReadingStatus(val label: String) { NOT_STARTED("Not started"), IN_PROGRESS("In progress"), FINISHED("Finished") }
data class ReadingState(val status: ReadingStatus = ReadingStatus.NOT_STARTED, val later: Boolean = false, val updatedMs: Long = 0)
data class SleepSession(val positionMs: Long, val createdMs: Long, val endedMs: Long = 0)
fun sameAudio(left: List<AudioTrack>, right: List<AudioTrack>) = left.map { Triple(it.id, it.partKey, it.durationMs) } == right.map { Triple(it.id, it.partKey, it.durationMs) }
fun readingStatus(position: Long, duration: Long, explicit: String?): ReadingStatus =
    runCatching { ReadingStatus.valueOf(explicit.orEmpty()) }.getOrNull() ?: when {
        duration > 0 && position >= duration - 1000 -> ReadingStatus.FINISHED
        position > 0 -> ReadingStatus.IN_PROGRESS
        else -> ReadingStatus.NOT_STARTED
    }
fun matchesStatus(filter: String, state: ReadingState): Boolean = when (filter) {
    "later" -> state.later
    "all" -> true
    else -> state.status.name == filter
}
fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}
fun friendlyTime(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
fun preciseTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) friendlyTime(ms) else "${seconds / 60}m ${seconds % 60}s"
}
fun parsePosition(text: String): Long? {
    val fields = text.trim().split(':')
    if (fields.size !in 2..3 || fields.any { it.isBlank() || it.any { character -> !character.isDigit() } }) return null
    val numbers = fields.map { it.toLongOrNull() ?: return null }
    if (numbers.last() >= 60 || fields.size == 3 && numbers[1] >= 60) return null
    return runCatching {
        numbers.fold(0L) { seconds, value -> Math.addExact(Math.multiplyExact(seconds, 60), value) }.let { Math.multiplyExact(it, 1000) }
    }.getOrNull()
}
fun safeServerAddress(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    val host = uri.host?.lowercase() ?: return false
    if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/") || uri.port > 65535) return false
    if (uri.scheme.equals("https", true)) return true
    if (!uri.scheme.equals("http", true)) return false
    if (host == "localhost") return true
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return false
    val octets = parts.map { it.toInt() }
    octets[0] == 127 || octets[0] == 10 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31
}.getOrDefault(false)
data class SavedProgress(val positionMs: Long, val updatedMs: Long, val speed: Float)
data class Place(val id: Long, val positionMs: Long, val timestamp: Long, val reason: String)
data class PlaybackState(val book: AudioBook? = null, val positionMs: Long = 0, val playing: Boolean = false,
    val buffering: Boolean = false, val speed: Float = 1f, val undoMs: Long? = null,
    val sleepDeadline: Long? = null, val sleepAtChapterEnd: Boolean = false, val error: String? = null,
    val sleepSession: SleepSession? = null)
