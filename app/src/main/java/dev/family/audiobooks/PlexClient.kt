package dev.family.audiobooks

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

data class SignInPin(val id: String, val code: String, val link: String)
private data class Node(val name: String, val values: Map<String, String>, val children: MutableList<Node> = mutableListOf()) {
    operator fun get(key: String) = values[key].orEmpty()
    fun long(key: String) = values[key]?.toLongOrNull() ?: 0L
    fun all(name: String): List<Node> = children.filter { it.name == name }
}

class PlexClient(private val vault: CredentialVault) {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).followRedirects(false).build()
    private suspend fun request(url: String, token: String? = null, post: Map<String, String>? = null, json: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("X-Plex-Product", "Spineplay")
            .header("X-Plex-Client-Identifier", vault.clientId).header("X-Plex-Version", BuildConfig.VERSION_NAME)
            .header("Accept", if (json) "application/json" else "application/xml")
        if (token != null) builder.header("X-Plex-Token", token)
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        if (post != null) builder.post(FormBody.Builder().apply { post.forEach { (key, value) -> add(key, value) } }.build())
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException(when (response.code) {
                401, 403 -> "Plex denied access. Check your sign-in, Home PIN, and library access."
                404 -> "That item is no longer available on Plex."
                else -> "Plex could not complete this request (${response.code}). Please try again."
            })
            response.body?.string() ?: error("Plex returned an empty response.")
        }
    }
    private fun xml(text: String): Node {
        val parser = Xml.newPullParser().apply { setInput(text.reader()) }
        val stack = mutableListOf<Node>()
        var root: Node? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL -> error("Unsupported Plex XML declaration.")
                XmlPullParser.START_TAG -> {
                    val node = Node(parser.name, (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
                    if (stack.isEmpty()) root = node else stack.last().children += node
                    stack += node
                }
                XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            }
            event = parser.nextToken()
        }
        return root ?: error("Plex returned invalid metadata.")
    }
    suspend fun createPin(): SignInPin {
        val response = JSONObject(request("https://plex.tv/api/v2/pins", post = mapOf("strong" to "true"), json = true))
        val id = response.get("id").toString()
        val code = response.getString("code")
        val params = "clientID=${Uri.encode(vault.clientId)}&code=${Uri.encode(code)}&context%5Bdevice%5D%5Bproduct%5D=Spineplay"
        return SignInPin(id, code, "https://app.plex.tv/auth#?$params")
    }
    suspend fun checkPin(pin: SignInPin): Identity? {
        val result = JSONObject(request("https://plex.tv/api/v2/pins/${pin.id}", json = true))
        val token = result.optString("authToken").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val user = JSONObject(request("https://plex.tv/api/v2/user", token, json = true))
        return Identity(user.get("id").toString(), user.optString("title").ifBlank { user.optString("username", "Plex user") }, token)
    }
    suspend fun profiles(owner: Identity): List<HomeProfile> {
        val result = xml(request("https://plex.tv/api/home/users", owner.token))
        return result.all("User").map { HomeProfile(it["id"], it["title"].ifBlank { it["username"] }, it["protected"] == "1") }
            .ifEmpty { listOf(HomeProfile(owner.id, owner.name, false)) }
    }
    suspend fun switchProfile(owner: Identity, profile: HomeProfile, pin: String): Identity {
        if (profile.id == owner.id && !profile.protected) return owner
        val url = Uri.parse("https://plex.tv/api/home/users/${Uri.encode(profile.id)}/switch").buildUpon()
            .apply { if (pin.isNotEmpty()) appendQueryParameter("pin", pin) }.build().toString()
        val result = xml(request(url, owner.token, post = emptyMap()))
        val token = result["authenticationToken"].ifBlank { result["authToken"] }
        require(token.isNotEmpty()) { "Plex did not authorise this profile. Check its PIN." }
        return Identity(profile.id, profile.name, token)
    }
    suspend fun servers(profile: Identity): List<PlexServer> = JSONArray(request(
        "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", profile.token, json = true)).objects()
        .filter { it.optString("provides").split(',').contains("server") }
        .map { resource -> PlexServer(resource.getString("clientIdentifier"), resource.optString("name", "Plex server"), resource.getString("accessToken"),
            resource.getJSONArray("connections").objects().sortedWith(compareBy<JSONObject> { it.optBoolean("relay") }.thenBy { !it.optBoolean("local") })
                .map { it.getString("uri").trimEnd('/') }.filter(::allowedServerUrl)) }
    suspend fun discoverLibraries(profile: Identity, server: PlexServer): ServerLibraries {
        for (connection in server.connections) {
            try {
                val sections = xml(request("$connection/library/sections", server.token))
                val libraries = sections.all("Directory").filter { it["type"] == "artist" }.map { PlexLibrary(it["key"], it["title"]) }
                require(libraries.isNotEmpty())
                return ServerLibraries(Session(profile, server.id, server.name, connection, server.token, ""), libraries)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Try only server connections explicitly supplied by Plex. */ }
        }
        error("Could not reach this server's audio libraries. Check Wi-Fi, remote access, and this profile's library permissions.")
    }
    fun url(session: Session, relative: String): String {
        require(relative.startsWith('/') && !relative.startsWith("//")) { "Invalid Plex media path." }
        require(allowedServerUrl(session.serverUrl)) { "Remote Plex connections must use HTTPS." }
        return session.serverUrl.trimEnd('/') + relative
    }
    suspend fun books(session: Session): List<BookSummary> {
        val result = mutableListOf<BookSummary>()
        session.libraryIds.forEach { libraryId ->
        var start = 0
        while (true) {
            val response = xml(request(url(session, "/library/sections/${Uri.encode(libraryId)}/all?type=9&sort=addedAt:desc&includeCollections=1&includeGuids=1"), session.serverToken,
                extraHeaders = mapOf("X-Plex-Container-Start" to start.toString(), "X-Plex-Container-Size" to "200")))
            val page = response.all("Directory")
            result += page.map { BookSummary(it["ratingKey"], it["title"], it["parentTitle"], it["thumb"], it.long("addedAt"), libraryId, it["parentRatingKey"],
                Regex("(?i)(?:audible|audnexus)[^A-Za-z0-9]+(B[A-Z0-9]{9})").find(it["guid"] + " " + it.all("Guid").joinToString(" ") { guid -> guid["id"] })?.groupValues?.get(1).orEmpty().uppercase(),
                seriesFromPlex(it.all("Mood").map { mood -> mood["tag"] }, it["titleSort"], it.all("Collection").map { collection -> collection["tag"] })) }
            start += page.size
            if (page.isEmpty() || start >= response.long("totalSize").takeIf { it > 0L }.let { it ?: Long.MAX_VALUE } || page.size < 200) break
        }
        }
        return orderedBooks(result.distinctBy { it.id })
    }
    suspend fun book(session: Session, summary: BookSummary): AudioBook {
        val metadata = xml(request(url(session, "/library/metadata/${Uri.encode(summary.id)}"), session.serverToken)).all("Directory").firstOrNull()
        val series = metadata?.let { seriesFromPlex(it.all("Mood").map { tag -> tag["tag"] }, it["titleSort"], it.all("Collection").map { tag -> tag["tag"] }) }.orEmpty()
        val enriched = summary.copy(series = series.ifEmpty { summary.series })
        val details = metadata?.let {
            val audnexus = it["guid"].contains("audnexus", true) || it.all("Mood").any { tag -> tag["tag"].startsWith("Series:", true) }
            val narrators = it.all("Role").filter { role -> role["role"].equals("Narrator", true) }.map { role -> role["tag"] } +
                if (audnexus) it.all("Style").map { style -> style["tag"] } else emptyList()
            BookDetails(android.text.Html.fromHtml(it["summary"].ifBlank { it.all("Review").joinToString("\n") { review -> review["text"] } }, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim(),
                narrators.distinct(), it["studio"], it["originallyAvailableAt"].take(4).ifBlank { it["year"] })
        } ?: BookDetails()
        val response = xml(request(url(session, "/library/metadata/${Uri.encode(summary.id)}/children"), session.serverToken))
        val nodes = response.all("Track").sortedWith(compareBy<Node> { it.long("parentIndex") }.thenBy {
            it.long("index").takeIf { index -> index > 0 } ?: Regex("(\\d+)\\s*$").find(it["title"])?.value?.trim()?.toLongOrNull() ?: Long.MAX_VALUE
        })
        val tracks = mutableListOf<AudioTrack>()
        val chapters = mutableListOf<Chapter>()
        var offset = 0L
        var remotePosition = 0L
        var remoteUpdated = 0L
        nodes.forEachIndexed { index, original ->
            val node = if (original["chapterSource"].isNotBlank() || nodes.size == 1) xml(request(url(session, original["key"] + "?includeChapters=1"), session.serverToken)).all("Track").firstOrNull() ?: original else original
            val part = node.all("Media").firstOrNull()?.all("Part")?.firstOrNull() ?: error("A file for this book is unavailable.")
            val duration = node.long("duration")
            require(duration > 0 && part["key"].isNotEmpty()) { "This book contains an unreadable audio file." }
            tracks += AudioTrack(node["ratingKey"], node["title"], part["key"], duration, part.long("size"))
            val cleaned = node["title"].removePrefix(summary.title).trim(' ', '-', '–', ':')
            val fallback = if (cleaned.isBlank() || cleaned.all { it.isDigit() } || cleaned.matches(Regex(".*\\(Unabridged\\)\\s*\\d+"))) "Part ${index + 1}" else cleaned
            val raw = node.all("Chapter").map { Chapter(it["tag"].ifBlank { it["title"] }, it.long("startTimeOffset"), it.long("endTimeOffset")) }
            chapters += normalizeChapters(raw, duration, fallback).map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
            if (node.long("lastViewedAt") * 1000 > remoteUpdated) {
                remoteUpdated = node.long("lastViewedAt") * 1000
                remotePosition = offset + node.long("viewOffset").coerceIn(0, duration)
            }
            offset += duration
        }
        require(tracks.isNotEmpty()) { "Plex returned no audio files for this book." }
        val navigation = chapters.size == 1 && offset > 2700000
        return AudioBook(enriched, tracks, if (navigation) navigationSections(offset) else chapters, remotePosition, remoteUpdated,
            if (navigation) "15-minute listening sections" else "Plex", details)
    }
    suspend fun remoteProgress(session: Session, book: AudioBook): RemoteProgress {
        val nodes = xml(request(url(session, "/library/metadata/${Uri.encode(book.summary.id)}/children"), session.serverToken)).all("Track").associateBy { it["ratingKey"] }
        var offset = 0L
        var result = RemoteProgress(0, 0)
        book.tracks.forEach { track ->
            val node = nodes[track.id]
            if (node != null && node.long("lastViewedAt") * 1000 > result.updatedMs) {
                val position = if (node["viewOffset"].isBlank() && node.long("viewCount") > 0) track.durationMs else node.long("viewOffset").coerceIn(0, track.durationMs)
                result = RemoteProgress(offset + position, node.long("lastViewedAt") * 1000)
            }
            offset += track.durationMs
        }
        return result
    }
    suspend fun saveCover(session: Session, summary: BookSummary, destination: java.io.File) = withContext(Dispatchers.IO) {
        if (summary.thumb.isBlank() || summary.needsMetadata) return@withContext
        val request = Request.Builder().url(url(session, summary.thumb)).header("X-Plex-Token", session.serverToken).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext
            response.body?.byteStream()?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 5 * 1024 * 1024) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                if (output.size() <= 5 * 1024 * 1024) destination.writeBytes(output.toByteArray())
            }
        }
    }
    suspend fun timeline(session: Session, book: AudioBook, position: Long, playing: Boolean) {
        val (index, local) = book.locate(position)
        val track = book.tracks[index]
        val query = Uri.Builder().appendQueryParameter("ratingKey", track.id)
            .appendQueryParameter("key", "/library/metadata/${track.id}")
            .appendQueryParameter("state", if (playing) "playing" else "paused")
            .appendQueryParameter("time", local.toString()).appendQueryParameter("duration", track.durationMs.toString()).build().encodedQuery
        request(url(session, "/:/timeline?$query"), session.serverToken)
    }
    companion object {
        fun allowedServerUrl(value: String) = safeServerAddress(value)
    }
}
