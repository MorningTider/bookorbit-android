package com.bookorbit.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeTest {
    @Test fun versionSevenPreservesDownloadsPositionsAndOfflineQueues() = verifyUpgrade(7)

    @Test fun versionFiveRunsTheCompleteMigrationChain() = verifyUpgrade(5)

    private fun verifyUpgrade(previousVersion: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-validation-$previousVersion-${System.nanoTime()}.db"
        val bookFile = File(context.filesDir, "$name.epub")
        val fileReference = "[{\"localPath\":\"${bookFile.absolutePath}\"}]"
        bookFile.writeText("Retained offline book bytes")
        var migrated: BookOrbitDatabase? = null
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val statements =
                    instrumentation.context.assets
                        .open("database-v7.sql")
                        .bufferedReader()
                        .use { it.readText() }
                        .split(';')
                        .map { it.trim() }
                val laterTables =
                    listOf("pending_reading_sessions", "pending_annotation_ops", "annotation_cache")
                for (statement in statements.filter { it.isNotEmpty() }) {
                    if (previousVersion == 5 && laterTables.any { statement.contains("`$it`") })
                        continue
                    old.execSQL(statement)
                }
                old.execSQL("INSERT INTO reader_progress VALUES (9,'epubcfi(saved)',21.5,1000,1)")
                old.execSQL("INSERT INTO audio_progress VALUES (3,12,42.5,12.0,1000,1)")
                old.execSQL(
                    "INSERT INTO downloads (bookId,title,authors,narrators,isAudiobook,format,sizeBytes," +
                        "downloadedAt,coverLocalPath,bookJson,filesJson,status,progress,lastError) " +
                        "VALUES (3,'Upgrade sample','[]','[]',0,'epub',26,1000,NULL,'{}',?,'completed',1.0,NULL)",
                    arrayOf(fileReference),
                )
                old.execSQL("INSERT INTO pending_ratings VALUES (3,4,1000)")
                old.execSQL("INSERT INTO pending_read_status VALUES (3,'READING',1000)")
                if (previousVersion == 7) {
                    old.execSQL(
                        "INSERT INTO pending_reading_sessions VALUES ('legacy-session',9,'READ',0,60000,60,1.0,21.5)"
                    )
                    old.execSQL(
                        "INSERT INTO pending_annotation_ops " +
                            "(bookId,kind,annotationId,cfi,text,noteSet,createdAt) " +
                            "VALUES (3,'create',-1,'epubcfi(saved)','Saved quote',0,1000)"
                    )
                    old.execSQL("INSERT INTO annotation_cache VALUES (3,'[]')")
                }
                old.version = previousVersion
            }
            migrated =
                Room.databaseBuilder(context, BookOrbitDatabase::class.java, name)
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
            val database = migrated.openHelper.writableDatabase
            assertEquals(8, database.version)
            database.query("SELECT cfi,percentage,dirty FROM reader_progress WHERE fileId=9").use {
                assertTrue(it.moveToFirst())
                assertEquals("epubcfi(saved)", it.getString(0))
                assertEquals(21.5, it.getDouble(1), 0.001)
                assertEquals(1, it.getInt(2))
            }
            database.query("SELECT filesJson,status FROM downloads WHERE bookId=3").use {
                assertTrue(it.moveToFirst())
                assertEquals(fileReference, it.getString(0))
                assertEquals("completed", it.getString(1))
            }
            for (table in listOf("audio_progress", "pending_ratings", "pending_read_status")) {
                database.query("SELECT count(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
            }
            for (table in listOf("pending_reading_sessions", "pending_annotation_ops")) {
                database.query("SELECT userId FROM $table").use {
                    assertEquals(if (previousVersion == 7) 1 else 0, it.count)
                    if (it.moveToFirst()) assertTrue(it.isNull(0))
                }
            }
            assertEquals("Retained offline book bytes", bookFile.readText())
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
            bookFile.delete()
        }
    }
}
