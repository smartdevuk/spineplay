package dev.family.audiobooks

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupFolderTest {
    class FolderProvider(private val directory: File) : DocumentsProvider() {
        val files = ConcurrentHashMap<String, Pair<String, File>>()
        @Volatile var failReads = false
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID)).apply { addRow(arrayOf("root", "root")) }
        private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
            return MatrixCursor(columns).apply { ids.forEach { id ->
                val entry = files[id]
                addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry?.first ?: "Backups"
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == "root") DocumentsContract.Document.MIME_TYPE_DIR else "application/json"
                    DocumentsContract.Document.COLUMN_FLAGS -> if (id == "root") DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                    DocumentsContract.Document.COLUMN_SIZE -> entry?.second?.length() ?: 0
                    else -> null
                } }.toTypedArray())
            } }
        }
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(listOf(documentId), projection)
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = rows(files.keys.toList(), projection)
        override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root" && (documentId == "root" || files.containsKey(documentId))
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val id = UUID.randomUUID().toString(); val file = File(directory, id); file.createNewFile(); files[id] = displayName to file; return id
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            if (failReads && mode == "r") throw FileNotFoundException("Simulated provider readback failure")
            return ParcelFileDescriptor.open(files[documentId]?.second ?: throw FileNotFoundException(), ParcelFileDescriptor.parseMode(mode))
        }
        override fun deleteDocument(documentId: String) { files.remove(documentId)?.second?.delete() }
    }
    @Test fun `automatic backups keep three verified versions per profile and retain good files after a failed write`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("listening.db")
        val directory = File(context.cacheDir, "backup-provider-${UUID.randomUUID()}").apply { mkdirs() }
        val provider = FolderProvider(directory)
        val authority = "dev.family.audiobooks.backuptest"
        provider.attachInfo(context, ProviderInfo().apply {
            this.authority = authority; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
            applicationInfo = context.applicationInfo
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val unrelated = provider.createDocument("root", "text/plain", "family-notes.json").also { provider.files[it]!!.second.writeText("Keep this file") }
        val alice = Session(Identity("alice", "Alice", "test"), "server", "Server", "http://127.0.0.1", "test", "4")
        val bob = alice.copy(profile = Identity("bob", "Bob", "test"))
        var current = alice
        val credentials = object : SessionCredentials {
            override val clientId = "test"
            override fun session() = current
            override fun remembered(userKey: String) = current.takeIf { it.userKey == userKey }
        }
        val book = AudioBook(BookSummary("book", "Book", "Author", ""), listOf(AudioTrack("track", "Audio", "/audio", 600000)), listOf(Chapter("One", 0, 600000)))
        val preferences = AppPreferences(context).apply { backupFolder = null }
        val store = ListeningStore(context)
        val backup = ProgressBackup(context, credentials, store, preferences)
        suspend fun waitFor(condition: () -> Boolean) { withTimeout(10000) { while (!condition()) delay(10) } }
        fun archives() = provider.files.values.filter { it.first.startsWith("Spineplay-") }
        try {
            store.save(alice.userKey, book, 10000, 1f)
            backup.configure(DocumentsContract.buildTreeDocumentUri(authority, "root").toString())
            waitFor { backup.state.value.lastSavedMs > 0 && !backup.state.value.saving }
            repeat(4) { index ->
                val previous = backup.state.value.lastSavedMs
                delay(5); store.save(alice.userKey, book, 20000L + index * 1000, 1f); backup.request(true)
                waitFor { backup.state.value.lastSavedMs > previous && !backup.state.value.saving }
            }
            assertEquals(3, archives().size)
            archives().forEach { ProgressArchive.parse(it.second.readText(), alice.userKey) }
            val goodFiles = archives().map { it.first }.toSet()
            provider.failReads = true
            store.save(alice.userKey, book, 50000, 1f); backup.request(true)
            waitFor { backup.state.value.error != null && !backup.state.value.saving }
            assertEquals(goodFiles, archives().map { it.first }.toSet())
            assertNotNull(runCatching { backup.backupNow() }.exceptionOrNull())
            assertFalse(backup.state.value.saving)
            provider.failReads = false; current = bob
            backup.refreshProfile()
            assertEquals(0L, backup.state.value.lastSavedMs)
            val previous = backup.state.value.lastSavedMs
            store.save(bob.userKey, book, 90000, 1f); backup.request(true)
            waitFor { backup.state.value.lastSavedMs > previous && !backup.state.value.saving }
            assertEquals(4, archives().size)
            val users = archives().map { JSONObject(JSONObject(it.second.readText()).getString("payload")).getString("user") }
            assertEquals(3, users.count { it == alice.userKey }); assertEquals(1, users.count { it == bob.userKey })
            assertEquals("Keep this file", provider.files[unrelated]!!.second.readText())
            backup.configure(null)
            assertFalse(backup.state.value.configured); assertFalse(backup.state.value.saving)
        } finally { backup.release(); store.close() }
    }
}
