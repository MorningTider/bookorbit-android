package com.bookorbit.feature.reader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.model.AuthSettings
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.network.ApiService
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(AndroidJUnit4::class)
class ReaderPreferenceSyncTest {
    @Test
    fun accountScopedOfflineJournalDefaultsBookOverridesAndReset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "reader-profile-${System.nanoTime()}"
        val storage = SecureStorage(context, name, name, "$name-owner")
        val json = Json { ignoreUnknownKeys = true }
        val session = SessionManager(storage, json)
        val server = MockWebServer()
        val online = AtomicBoolean(true)
        val defaults = mutableMapOf<String, JsonElement>()
        val book = mutableMapOf<String, JsonElement>()
        var patches = 0
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    if (path == "/auth/me")
                        return MockResponse()
                            .setBody(
                                json.encodeToString(AuthUser.serializer(), session.currentUser!!)
                            )
                    if (path == "/reader/defaults" && request.method == "GET")
                        return MockResponse()
                            .setBody(
                                buildJsonObject { put("epub", JsonObject(defaults.toMap())) }
                                    .toString()
                            )
                    if (request.method == "PATCH") {
                        val patch =
                            json
                                .parseToJsonElement(request.body.readUtf8())
                                .jsonObject["set"]!!
                                .jsonObject
                        if (path == "/reader/defaults/epub") defaults.putAll(patch)
                        else book.putAll(patch)
                        patches++
                        return MockResponse().setResponseCode(204)
                    }
                    if (request.method == "DELETE") {
                        book.clear()
                        return MockResponse().setResponseCode(204)
                    }
                    return MockResponse()
                        .setBody(
                            buildJsonObject {
                                    put(
                                        "settings",
                                        if (path.endsWith("/1")) JsonObject(book.toMap())
                                        else JsonNull,
                                    )
                                }
                                .toString()
                        )
                }
            }
        server.start()
        session.setServerUrl(server.url("/").toString())
        session.signIn("isolated", AuthUser(1, "isolated", settings = AuthSettings(true)))
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    if (!online.get()) throw IOException("isolated offline")
                    it.proceed(it.request())
                }
                .build()
        val api =
            Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ApiService::class.java)
        val profiles = ReaderPreferenceSync(context, api, session, json)
        try {
            val fallback = ReaderSettings(fontSize = 20, volumeKeyPaging = true)
            val initial = profiles.load(1, fallback)
            profiles.save(1, initial.settings, initial.settings.copy(fontSize = 24), false, true)
            online.set(false)
            assertFalse(profiles.flush())
            assertEquals(24, profiles.load(1, fallback).settings.fontSize)
            online.set(true)
            assertTrue(profiles.flush())
            assertEquals(1, patches)
            assertEquals(JsonPrimitive(24), defaults["fontSize"])
            defaults["fontSize"] = JsonPrimitive(25)
            defaults["textIndent"] = JsonPrimitive(2)
            assertTrue(profiles.load(1, fallback).settings.paragraphIndent)
            online.set(false)
            assertTrue(profiles.load(1, fallback).settings.paragraphIndent)
            online.set(true)
            defaults["textIndent"] = JsonNull
            assertFalse(profiles.load(1, fallback).settings.paragraphIndent)
            val inherited = profiles.load(1, fallback)
            assertEquals(25, inherited.settings.fontSize)
            profiles.save(1, inherited.settings, inherited.settings.copy(fontSize = 22), true, true)
            assertTrue(profiles.flush())
            assertEquals(22, profiles.load(1, fallback).settings.fontSize)
            assertEquals(25, profiles.load(2, fallback).settings.fontSize)
            profiles.useDefaults(1, true)
            assertEquals(25, profiles.load(1, fallback).settings.fontSize)
            assertTrue(book.isEmpty())
            val deviceBase = profiles.load(1, fallback)
            profiles.save(
                1,
                deviceBase.settings,
                deviceBase.settings.copy(sidePadding = 36),
                true,
                true,
            )
            assertTrue(profiles.load(1, fallback).perBook)
            assertEquals(36, profiles.load(1, fallback).settings.sidePadding)
            assertTrue(book.isEmpty())
            profiles.useDefaults(1, true)
            val current = profiles.load(1, fallback)
            profiles.save(1, current.settings, current.settings.copy(fontSize = 30), false, true)
            val previousPatches = patches
            session.signIn(
                "isolated-two",
                AuthUser(2, "isolated-two", settings = AuthSettings(true)),
            )
            assertTrue(profiles.flush())
            assertEquals(previousPatches, patches)
            defaults.clear()
            assertEquals(20, profiles.load(1, fallback).settings.fontSize)
            online.set(false)
            profiles.enqueueSave(1, fallback, fallback.copy(fontSize = 31), false, true)
            assertEquals(31, profiles.load(1, fallback).settings.fontSize)
        } finally {
            session.signOut()
            context.deleteSharedPreferences(name)
            server.shutdown()
        }
    }
}
