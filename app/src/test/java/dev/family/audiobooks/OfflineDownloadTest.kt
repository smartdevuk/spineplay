@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.family.audiobooks

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineDownloadTest {
    @Test fun `multi-file downloads persist and play from cache with no server and isolated credentials`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("listening.db")
        val payload = ByteArray(128 * 1024) { (it % 127).toByte() }
        val tokens = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    input.readLine()
                    var token = ""
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isBlank()) break
                        if (line.startsWith("X-Plex-Token:", true)) token = line.substringAfter(':').trim()
                    }
                    tokens += token
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload); flush()
                    }
                }
            } catch (_: java.io.IOException) { if (!server.isClosed) throw AssertionError("Local test server failed") }
        }.apply { isDaemon = true; start() }
        val address = "http://127.0.0.1:${server.localPort}"
        val alice = Session(Identity("alice", "Alice", "account-test"), "server", "Local test", address, "alice-test", "4")
        val bob = alice.copy(profile = Identity("bob", "Bob", "account-test"), serverToken = "bob-test")
        val sessions = object : SessionCredentials {
            override val clientId = "test-client"
            override fun session() = alice
            override fun remembered(userKey: String) = listOf(alice, bob).firstOrNull { it.userKey == userKey }
        }
        val book = AudioBook(BookSummary("book", "Book", "Author", "", libraryId = "4"),
            listOf(AudioTrack("one", "Part 1", "/audio/one.mp3", 60000), AudioTrack("two", "Part 2", "/audio/two.mp3", 60000)),
            listOf(Chapter("One", 0, 60000), Chapter("Two", 60000, 120000)))
        val store = ListeningStore(context)
        val plex = PlexClient(CredentialVault(context))
        var downloads = OfflineDownloads(context, sessions, store, plex, AppPreferences(context))
        fun waitFor(condition: () -> Boolean) {
            val until = System.currentTimeMillis() + 10000
            while (!condition() && System.currentTimeMillis() < until) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
            assertTrue("Download did not reach the expected state", condition())
        }
        try {
            downloads.manager.requirements = Requirements(0)
            listOf(alice, bob).forEach { session ->
                store.saveOffline(session.userKey, book)
                book.tracks.forEach { track ->
                    val key = offlineKey(session.userKey, "book", track)
                    downloads.manager.addDownload(DownloadRequest.Builder(key, android.net.Uri.parse(address + track.partKey)).setCustomCacheKey(key).build())
                }
            }
            downloads.manager.resumeDownloads()
            waitFor { listOf(alice, bob).all { session -> book.tracks.all { downloads.manager.downloadIndex.getDownload(offlineKey(session.userKey, "book", it))?.state == Download.STATE_COMPLETED } } }
            assertTrue(tokens.contains("alice-test")); assertTrue(tokens.contains("bob-test"))
            assertEquals(4, tokens.size)
            downloads.release()
            server.close()
            downloads = OfflineDownloads(context, sessions, store, plex, AppPreferences(context))
            book.tracks.forEach { track ->
                val key = offlineKey(bob.userKey, "book", track)
                assertEquals(Download.STATE_COMPLETED, downloads.manager.downloadIndex.getDownload(key)?.state)
                val source = CacheDataSource.Factory().setCache(downloads.cache).createDataSource()
                source.open(DataSpec.Builder().setUri(address + track.partKey).setKey(key).build())
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                try { while (true) { val size = source.read(buffer, 0, buffer.size); if (size < 0) break; output.write(buffer, 0, size) } } finally { source.close() }
                assertArrayEquals(payload, output.toByteArray())
            }
            assertEquals(4, tokens.size)
            assertEquals(book.tracks, store.offlineBooks(bob.userKey).single().tracks)
        } finally { server.close(); downloads.release(); store.close() }
    }
}
