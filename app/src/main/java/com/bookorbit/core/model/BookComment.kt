package com.bookorbit.core.model

import kotlinx.serialization.Serializable

@Serializable
data class BookComment(
    val id: Int,
    val bookId: Int,
    val userId: Int,
    val authorName: String,
    val parentId: Int? = null,
    val threadId: Int? = null,
    val scope: String,
    val bookFileId: Int? = null,
    val chapterHref: String? = null,
    val chapterTitle: String? = null,
    val cfi: String? = null,
    val quote: String? = null,
    val body: String,
    val createdAt: String,
    val updatedAt: String,
    val deleted: Boolean = false,
    val canDelete: Boolean = false,
    val deleteMode: String? = null,
)

@Serializable data class BookCommentPage(val items: List<BookComment>, val nextCursor: Int? = null)

@Serializable
data class CreateBookComment(
    val clientId: String,
    val body: String,
    val parentId: Int? = null,
    val scope: String = "review",
    val bookFileId: Int? = null,
    val chapterHref: String? = null,
    val chapterTitle: String? = null,
    val cfi: String? = null,
    val quote: String? = null,
)
