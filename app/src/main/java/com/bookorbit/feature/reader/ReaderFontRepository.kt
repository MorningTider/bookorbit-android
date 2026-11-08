package com.bookorbit.feature.reader

import android.content.Context
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.network.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Singleton
class ReaderFontRepository
@Inject
constructor(
    private val api: ApiService,
    private val session: SessionManager,
    private val json: Json,
    @ApplicationContext private val context: Context,
) {
    private val downloads = Mutex()

    private fun accountKey(): String? {
        val server = session.serverUrl ?: return null
        val userId = session.currentUser?.id ?: return null
        return fontDigest("${server.trimEnd('/')}/$userId")
    }

    private fun directory(key: String) = File(context.filesDir, "reader-fonts/$key")

    suspend fun catalog(refresh: Boolean): List<ReaderFontFamily> =
        withContext(Dispatchers.IO) {
            val key = accountKey() ?: return@withContext emptyList()
            val directory = directory(key).apply { mkdirs() }
            val catalog = File(directory, "catalog.json")
            val cached =
                runCatching {
                        json.decodeFromString(
                            ListSerializer(ScopedReaderFont.serializer()),
                            catalog.readText(),
                        )
                    }
                    .getOrDefault(emptyList())
            val fonts =
                if (refresh) {
                    listOf("server", "user")
                        .flatMap { scope ->
                            try {
                                val values =
                                    if (scope == "server") api.getServerFonts()
                                    else api.getUserFonts()
                                values
                                    .filter {
                                        it.id > 0 &&
                                            it.format in FORMATS &&
                                            it.fileSize in 4..MAX_FONT_BYTES
                                    }
                                    .map { ScopedReaderFont(scope, it) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                cached.filter { it.scope == scope }
                            }
                        }
                        .also { values ->
                            check(accountKey() == key)
                            val temporary = File(directory, "catalog.json.part")
                            temporary.writeText(
                                json.encodeToString(
                                    ListSerializer(ScopedReaderFont.serializer()),
                                    values,
                                )
                            )
                            if (!temporary.renameTo(catalog))
                                throw IOException("Cannot save font catalog")
                        }
                } else cached
            fonts
                .groupBy { it.scope to it.font.familyName }
                .map { (group, values) ->
                    val faces = selectableFontFaces(values)
                    ReaderFontFamily(
                        name = group.second,
                        cssFamily = readerFontCssFamily(group.second, group.first),
                        scope = group.first,
                        faces = faces,
                        downloaded = faces.all { validFile(directory, it) },
                    )
                }
                .sortedWith(compareBy({ it.scope != "server" }, { it.name }))
        }

    suspend fun download(family: ReaderFontFamily) =
        withContext(Dispatchers.IO) {
            downloads.withLock {
                val key = accountKey() ?: throw IOException("Sign in to download fonts")
                val directory = directory(key).apply { mkdirs() }
                for (face in family.faces) {
                    if (validFile(directory, face)) continue
                    val temporary = File(directory, "${face.fileName}.part")
                    try {
                        api.serveReaderFont(
                                if (face.scope == "server") "server-fonts" else "fonts",
                                face.font.id,
                            )
                            .use { body ->
                                body.byteStream().use { input ->
                                    temporary.outputStream().use { output ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        var total = 0L
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            total += count
                                            if (
                                                total > face.font.fileSize || total > MAX_FONT_BYTES
                                            )
                                                throw IOException("Font exceeds declared size")
                                            output.write(buffer, 0, count)
                                        }
                                    }
                                }
                            }
                        if (temporary.length() != face.font.fileSize)
                            throw IOException("Incomplete font download")
                        check(accountKey() == key)
                        if (!temporary.renameTo(File(directory, face.fileName)))
                            throw IOException("Cannot save font")
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }

    suspend fun cachedFaces(family: ReaderFontFamily?): List<ReaderFontFace> =
        withContext(Dispatchers.IO) {
            val key = accountKey() ?: return@withContext emptyList()
            val selected = family ?: return@withContext emptyList()
            selected.faces
                .filter { validFile(directory(key), it) }
                .map { face ->
                    ReaderFontFace(
                        family = selected.cssFamily,
                        fileName = face.fileName,
                        format = face.font.format,
                        weight = face.font.weight.coerceIn(1, 1000),
                        weightMin = face.font.weightMin,
                        weightMax = face.font.weightMax,
                        style = if (face.font.style == "italic") "italic" else "normal",
                    )
                }
        }

    fun openCached(fileName: String): java.io.InputStream? {
        if (!SAFE_FILE.matches(fileName)) return null
        val key = accountKey() ?: return null
        return runCatching { File(directory(key), fileName).inputStream() }.getOrNull()
    }

    private fun validFile(directory: File, face: ScopedReaderFont): Boolean =
        File(directory, face.fileName).let { it.isFile && it.length() == face.font.fileSize }

    companion object {
        private val FORMATS = setOf("ttf", "otf", "woff", "woff2")
        private const val MAX_FONT_BYTES = 50L * 1024 * 1024
        private val SAFE_FILE =
            Regex("(?:server|user)-[0-9]+-[0-9]+-[a-f0-9]{12}\\.(?:ttf|otf|woff|woff2)")
    }
}
