package com.bookorbit.feature.reader

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.network.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ReaderPreferenceResponse(
    val settings: JsonObject? = null,
    val isCustomized: Boolean = false,
)

@Serializable data class ReaderPreferencePatch(val set: JsonObject)

/** Only the fields understood by both readers cross the wire. Device controls remain local. */
object ReaderPreferenceCodec {
    private val shared =
        setOf(
            "themeName",
            "isDark",
            "fontFamily",
            "fontWeight",
            "fontSize",
            "lineHeight",
            "paragraphSpacing",
            "letterSpacing",
            "maxColumnCount",
            "gap",
            "maxInlineSize",
            "maxBlockSize",
            "justify",
            "hyphenate",
            "flow",
        )

    fun encode(settings: ReaderSettings): JsonObject {
        val source = ReaderBridge.json.encodeToJsonElement(settings).jsonObject
        return JsonObject(
            source.filterKeys { it in shared } +
                ("textIndent" to if (settings.paragraphIndent) JsonPrimitive(2) else JsonNull)
        )
    }

    fun merge(base: ReaderSettings, fields: JsonObject): ReaderSettings {
        val converted = fields.filterKeys { it in shared }.toMutableMap()
        fields["fontSize"]?.jsonPrimitive?.doubleOrNull?.let {
            converted["fontSize"] = JsonPrimitive(clampFontSize(it.roundToInt()))
        }
        if (converted["letterSpacing"] == JsonNull) converted["letterSpacing"] = JsonPrimitive(0.0)
        fields["textIndent"]?.let {
            converted["paragraphIndent"] = JsonPrimitive((it.jsonPrimitive.doubleOrNull ?: 0.0) > 0)
        }
        val json = ReaderBridge.json
        return json.decodeFromJsonElement(
            JsonObject(json.encodeToJsonElement(base).jsonObject + converted)
        )
    }

    fun changed(before: ReaderSettings, after: ReaderSettings) =
        JsonObject(encode(after).filter { (key, value) -> encode(before)[key] != value })
}

private val Context.readerProfiles by preferencesDataStore("reader_profiles")

/** Account-scoped cache and durable PATCH journal. Failed writes are retried on reopening. */
@Singleton
class ReaderPreferenceSync
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val session: SessionManager,
    private val json: Json,
) {
    data class Profile(
        val settings: ReaderSettings,
        val perBook: Boolean,
        val sync: Boolean,
        val pending: Boolean,
    )

    private val syncMutex = Mutex()
    private val writes = Mutex()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun owner() = fontDigest("${session.serverUrl}|${session.currentUser?.id}")

    private fun key(owner: String, name: String) = stringPreferencesKey("$owner-$name")

    private suspend fun objectAt(owner: String, name: String): JsonObject =
        context.readerProfiles.data.first()[key(owner, name)]?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        } ?: JsonObject(emptyMap())

    private suspend fun write(owner: String, name: String, value: JsonObject) {
        context.readerProfiles.edit { it[key(owner, name)] = value.toString() }
    }

    private suspend fun local(
        owner: String,
        fileId: Int,
        fallback: ReaderSettings,
    ): ReaderSettings {
        val global = objectAt(owner, "local-global")
        val book = objectAt(owner, "local-$fileId")
        return json.decodeFromJsonElement(
            JsonObject(ReaderBridge.json.encodeToJsonElement(fallback).jsonObject + global + book)
        )
    }

    suspend fun load(fileId: Int, fallback: ReaderSettings, supported: Boolean = true): Profile {
        writes.withLock {}
        val owner = owner()
        var sync = supported && session.currentUser?.settings?.syncReaderPreferences == true
        var pending = false
        var settings = local(owner, fileId, fallback)
        var perBook = objectAt(owner, "local-$fileId").isNotEmpty()
        if (supported) {
            try {
                withTimeout(8_000) {
                    val user = api.getMe()
                    if (owner != owner()) return@withTimeout
                    session.accessToken?.let { session.signIn(it, user) }
                    sync = user.settings.syncReaderPreferences
                    if (!sync) return@withTimeout
                    pending = !flush()
                    val defaults = api.readerDefaults()["epub"] ?: JsonObject(emptyMap())
                    val book = api.readerPreference(fileId).settings ?: JsonObject(emptyMap())
                    // Preserve local changes which are still awaiting acknowledgement.
                    val globalQueue = objectAt(owner, "pending-global")
                    val bookQueue = objectAt(owner, "pending-$fileId")
                    val resetting =
                        context.readerProfiles.data.first()[key(owner, "reset-$fileId")] != null
                    settings =
                        ReaderPreferenceCodec.merge(settings, JsonObject(defaults + globalQueue))
                    if (!resetting)
                        settings =
                            ReaderPreferenceCodec.merge(settings, JsonObject(book + bookQueue))
                    val localBookControls =
                        objectAt(owner, "local-$fileId").filterKeys {
                            it !in ReaderPreferenceCodec.encode(fallback).keys &&
                                it != "paragraphIndent"
                        }
                    perBook =
                        (!resetting && book.isNotEmpty()) ||
                            bookQueue.isNotEmpty() ||
                            localBookControls.isNotEmpty()
                    val bookFields = JsonObject(book + bookQueue)
                    val bookSettings =
                        ReaderBridge.json
                            .encodeToJsonElement(ReaderPreferenceCodec.merge(fallback, bookFields))
                            .jsonObject
                    val bookLocal =
                        objectAt(owner, "local-$fileId").filterKeys {
                            it !in ReaderPreferenceCodec.encode(fallback).keys &&
                                it != "paragraphIndent"
                        }
                    val androidBookDelta =
                        bookSettings.filter { (key, _) ->
                            key in bookFields ||
                                key == "paragraphIndent" && "textIndent" in bookFields
                        }
                    val globalSettings =
                        ReaderPreferenceCodec.merge(fallback, JsonObject(defaults + globalQueue))
                    write(
                        owner,
                        "local-global",
                        JsonObject(
                            objectAt(owner, "local-global") +
                                ReaderPreferenceCodec.encode(globalSettings) +
                                ("paragraphIndent" to JsonPrimitive(globalSettings.paragraphIndent))
                        ),
                    )
                    if (perBook)
                        write(owner, "local-$fileId", JsonObject(bookLocal + androidBookDelta))
                    else write(owner, "local-$fileId", JsonObject(emptyMap()))
                }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) pending = sync
                else throw e
            } catch (_: Exception) {
                pending = sync
            }
        }
        return Profile(settings, perBook, sync, pending)
    }

    /** Capture ownership before launching, and finish local persistence when the reader closes. */
    fun enqueueSave(
        fileId: Int,
        before: ReaderSettings,
        after: ReaderSettings,
        perBook: Boolean,
        sync: Boolean,
    ) {
        val owner = owner()
        writeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock { saveAt(owner, fileId, before, after, perBook, sync) }
        }
    }

    fun flushOnClose() {
        writeScope.launch {
            writes.withLock {}
            try {
                withTimeout(10_000) { flush() }
            } catch (_: Exception) {
                /* Journal remains for next open. */
            }
        }
    }

    suspend fun save(
        fileId: Int,
        before: ReaderSettings,
        after: ReaderSettings,
        perBook: Boolean,
        sync: Boolean,
    ) {
        writes.withLock { saveAt(owner(), fileId, before, after, perBook, sync) }
    }

    private suspend fun saveAt(
        owner: String,
        fileId: Int,
        before: ReaderSettings,
        after: ReaderSettings,
        perBook: Boolean,
        sync: Boolean,
    ) {
        val target = if (perBook) fileId.toString() else "global"
        val changes = ReaderPreferenceCodec.changed(before, after)
        val beforeLocal = ReaderBridge.json.encodeToJsonElement(before).jsonObject
        val afterLocal = ReaderBridge.json.encodeToJsonElement(after).jsonObject
        val localChanges = afterLocal.filter { (k, v) -> beforeLocal[k] != v }
        context.readerProfiles.edit {
            val name = key(owner, "local-$target")
            val current = it[name]?.let { raw -> json.parseToJsonElement(raw).jsonObject }.orEmpty()
            it[name] = JsonObject(current + localChanges).toString()
            if (sync && changes.isNotEmpty()) {
                val queueKey = key(owner, "pending-$target")
                val queue =
                    it[queueKey]?.let { raw -> json.parseToJsonElement(raw).jsonObject }.orEmpty()
                it[queueKey] = JsonObject(queue + changes).toString()
            }
        }
    }

    suspend fun useDefaults(fileId: Int, sync: Boolean) {
        writes.withLock {}
        val owner = owner()
        context.readerProfiles.edit {
            it.remove(key(owner, "local-$fileId"))
            it.remove(key(owner, "pending-$fileId"))
            if (sync) it[key(owner, "reset-$fileId")] = "true"
        }
    }

    suspend fun setSync(enabled: Boolean) {
        api.readerStorageMode(buildJsonObject { put("sync", enabled) })
        val user = session.currentUser ?: return
        session.accessToken?.let {
            session.signIn(
                it,
                user.copy(settings = user.settings.copy(syncReaderPreferences = enabled)),
            )
        }
    }

    suspend fun flush(): Boolean =
        syncMutex.withLock {
            val owner = owner()
            if (session.currentUser?.settings?.syncReaderPreferences != true) return@withLock true
            try {
                val snapshot =
                    context.readerProfiles.data.first().asMap().filterKeys {
                        it.name.startsWith("$owner-pending-") || it.name.startsWith("$owner-reset-")
                    }
                // Deletes precede new deltas for the same file.
                for ((pref, value) in
                    snapshot.entries.sortedBy { !it.key.name.startsWith("$owner-reset-") }) {
                    if (owner != owner()) return@withLock false
                    val reset = pref.name.startsWith("$owner-reset-")
                    val target =
                        pref.name.substringAfter(if (reset) "$owner-reset-" else "$owner-pending-")
                    if (reset) api.deleteReaderPreference(target.toInt())
                    else {
                        val patch =
                            ReaderPreferencePatch(
                                json.parseToJsonElement(value as String).jsonObject
                            )
                        if (target == "global") api.patchReaderDefault(patch)
                        else api.patchReaderPreference(target.toInt(), patch)
                    }
                    context.readerProfiles.edit { prefs ->
                        val typed = stringPreferencesKey(pref.name)
                        if (prefs[typed] == value) prefs.remove(typed)
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
}
