package com.bookorbit.feature.sources

import kotlinx.serialization.Serializable

@Serializable
data class SourceAvailability(val enabled: Boolean = false, val configured: Boolean = false)

@Serializable
data class SourceStatus(
    val fanqie: SourceAvailability = SourceAvailability(),
    val qidian: SourceAvailability = SourceAvailability(),
) {
    fun enabled() =
        listOf("fanqie" to fanqie, "qidian" to qidian)
            .filter { it.second.enabled && it.second.configured }
            .map { it.first }
}

@Serializable
data class SourceBook(
    val provider: String,
    val providerId: String,
    val title: String = "",
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val coverUrl: String? = null,
    val isbn10: String? = null,
    val isbn13: String? = null,
) {
    val key
        get() = "$provider:$providerId"
}

@Serializable
data class SourceProgress(
    val completedChapters: Int? = null,
    val totalChapters: Int? = null,
    val phase: String = "preparing",
)

@Serializable
data class SourceJob(
    val id: Int,
    val libraryId: Int,
    val title: String,
    val source: String? = null,
    val status: String,
    val retryable: Boolean = false,
    val progress: SourceProgress? = null,
    val cancelRequested: Boolean = false,
    val resultBookId: Int? = null,
    val reviewId: String? = null,
    val error: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val active
        get() = status in setOf("queued", "downloading", "importing")
}

@Serializable
data class SourceJobsPage(val items: List<SourceJob> = emptyList(), val nextCursor: Int? = null)

@Serializable data class ImportFile(val id: Int, val filename: String, val format: String? = null)

@Serializable
data class ImportMatch(
    val bookId: Int,
    val libraryId: Int,
    val libraryName: String = "",
    val title: String,
    val authors: List<String> = emptyList(),
    val canEdit: Boolean = false,
    val files: List<ImportFile> = emptyList(),
    val reasons: List<String> = emptyList(),
)

@Serializable data class ImportReview(val id: String, val matches: List<ImportMatch> = emptyList())

@Serializable data class SourceOrigin(val provider: String, val id: String)

@Serializable
data class ImportIdentity(
    val title: String,
    val authors: List<String>,
    val origin: SourceOrigin,
    val format: String = "epub",
    val isbn10: String? = null,
    val isbn13: String? = null,
)

@Serializable data class ImportCandidate(val key: String, val identity: ImportIdentity)

@Serializable data class ImportCheck(val libraryId: Int, val candidates: List<ImportCandidate>)

@Serializable
data class ImportCheckResult(val key: String, val matches: List<ImportMatch> = emptyList())

@Serializable
data class ImportResolution(
    val action: String,
    val bookId: Int? = null,
    val fileId: Int? = null,
    val expectedSha256: String? = null,
)

@Serializable
data class SourceAcquisition(
    val bookId: String,
    val importResolution: ImportResolution? = null,
    val folderId: Int? = null,
)

@Serializable data class FileVersion(val sha256: String)

@Serializable data class SourceDestination(val libraryId: Int? = null, val folderId: Int? = null)
