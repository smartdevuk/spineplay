package dev.family.audiobooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class ChapterEdition(val asin: String, val region: String, val title: String, val author: String,
    val durationMs: Long, val chapters: List<Chapter>)

fun audibleId(value: String): String? {
    val input = value.trim().uppercase()
    if (input.matches(Regex("[A-Z0-9]{10}"))) return input
    return Regex("/(?:PD/)?(?:[^/]+/)?([A-Z0-9]{10})(?:[/?#]|$)").find(input)?.groupValues?.get(1)
}
private fun matchingTitle(value: String) = value.lowercase().replace("(unabridged)", "").substringBefore(':')
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
fun editionChapters(book: AudioBook, edition: ChapterEdition, adjustmentMs: Long = 0): AudioBook {
    require(matchingTitle(book.summary.title) == matchingTitle(edition.title)) { "This edition's title does not match this book." }
    require(!book.summary.hasAuthor || matchingTitle(book.summary.author) == matchingTitle(edition.author)) { "This edition's author does not match this book." }
    require(abs(book.durationMs - edition.durationMs) <= 30000) { "The running times differ by more than 30 seconds. This may be a different edition; no chapters have been changed." }
    require(adjustmentMs in -300000L..300000L) { "Use a timing adjustment between −300 and 300 seconds." }
    require(edition.chapters.size in 2..1000 && edition.chapters.all { it.startMs >= 0 && it.endMs > it.startMs } &&
        edition.chapters.zipWithNext().all { (a, b) -> a.endMs <= b.startMs } &&
        abs(edition.chapters.last().endMs - edition.durationMs) <= 1000) { "This chapter list has inconsistent timings." }
    val chapters = normalizeChapters(edition.chapters.map { it.copy(startMs = it.startMs + adjustmentMs, endMs = it.endMs + adjustmentMs) }, book.durationMs, "Audio section")
    return book.copy(chapters = chapters, chapterSource = "Audnexus · ${edition.region.uppercase()} · ${edition.asin}")
}
class ChapterLookup {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    suspend fun find(value: String, region: String): ChapterEdition = withContext(Dispatchers.IO) {
        val asin = audibleId(value) ?: error("Paste an Audible book link or its 10-character ASIN.")
        require(region in listOf("uk", "us", "au", "ca", "de", "fr", "in", "it", "jp", "es"))
        fun get(path: String): JSONObject = client.newCall(Request.Builder().url("https://api.audnex.us/books/$asin$path?region=$region").build()).execute().use {
            require(it.isSuccessful) { "No chapter data was found for that edition and region." }
            JSONObject(it.body?.string() ?: error("No chapter data was returned."))
        }
        val metadata = get("")
        val data = get("/chapters")
        require(data.optBoolean("isAccurate")) { "The provider has not marked these chapter timings as accurate." }
        ChapterEdition(asin, region, metadata.getString("title"), metadata.getJSONArray("authors").objects().joinToString(", ") { it.getString("name") },
            data.getLong("runtimeLengthMs"), data.getJSONArray("chapters").objects().map {
                Chapter(it.getString("title").take(512), it.getLong("startOffsetMs"), Math.addExact(it.getLong("startOffsetMs"), it.getLong("lengthMs")))
            })
    }
}
