package com.bookorbit.core.network

import com.bookorbit.core.auth.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds absolute cover/author-thumbnail image URLs for Coil. Auth is supplied by the [ImageClient]
 * OkHttp client's interceptor, so no per-request headers are needed here.
 */
@Singleton
class ImageUrls @Inject constructor(private val session: SessionManager) {
    private fun base(): String = "${session.serverUrl.orEmpty()}/api/v1"

    fun cover(bookId: Int): String = "${base()}/books/$bookId/cover"

    fun thumbnail(bookId: Int): String = "${base()}/books/$bookId/thumbnail"

    fun authorThumbnail(authorId: Int): String = "${base()}/authors/$authorId/thumbnail"

    /** One page of a comic file, rendered by the server (works for CBZ, CBR and CB7). */
    fun comicPage(fileId: Int, index: Int): String = "${base()}/cbz/files/$fileId/pages/$index"

    /**
     * External source covers are fetched through BookOrbit, so Coil sends auth only to the server.
     */
    fun sourceCover(value: String?): String? {
        val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val proxy = "${base()}/books/cover/proxy".toHttpUrl()
        val relativeProxy = "${proxy.encodedPath}?"
        if (url.startsWith(relativeProxy)) return proxy.resolve(url)?.toString()
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (
            parsed.scheme == proxy.scheme &&
                parsed.host == proxy.host &&
                parsed.port == proxy.port &&
                parsed.encodedPath == proxy.encodedPath
        )
            return parsed.toString()
        return proxy.newBuilder().addQueryParameter("url", parsed.toString()).build().toString()
    }

    fun bookDockCover(fileId: Int): String = "${base()}/book-dock/files/$fileId/cover"
}
