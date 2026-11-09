package com.bookorbit.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookorbit.R
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Check the installed resource overlay as well as the two launcher icon variants. */
@RunWith(AndroidJUnit4::class)
class AppBrandingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun applicationNameIsBookOrbitInEnglishAndChinese() {
        assertEquals(
            "BookOrbit",
            context.applicationInfo.loadLabel(context.packageManager).toString(),
        )
        for (tag in listOf("en-US", "zh-CN")) {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
            val localized = context.createConfigurationContext(configuration)
            assertEquals("BookOrbit", localized.getString(R.string.app_name))
        }
    }

    @Test
    fun regularAndRoundIconsUseTheBlueBackgroundAndWhiteOrbit() {
        val expected = Color.rgb(37, 99, 235)
        for ((name, drawable) in
            listOf(
                "launcher" to context.packageManager.getApplicationIcon(context.applicationInfo),
                "launcher-round" to context.getDrawable(R.mipmap.ic_launcher_round),
            )) {
            assertTrue("Adaptive icon for $name", drawable is AdaptiveIconDrawable)
            val icon = drawable as AdaptiveIconDrawable
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            icon.background.setBounds(0, 0, 192, 192)
            icon.background.draw(Canvas(bitmap))
            assertEquals("Blue background for $name", expected, bitmap.getPixel(96, 96))
            bitmap.eraseColor(Color.TRANSPARENT)
            icon.setBounds(0, 0, 192, 192)
            icon.draw(Canvas(bitmap))
            val pixels = IntArray(192 * 192)
            bitmap.getPixels(pixels, 0, 192, 0, 0, 192, 192)
            assertTrue("Blue visible in $name", pixels.count { it == expected } > 5_000)
            assertTrue("White orbit visible in $name", pixels.count { it == Color.WHITE } > 500)
            assertTrue(
                "No brown debug badge in $name",
                pixels.none { Color.alpha(it) == 255 && Color.red(it) > Color.blue(it) + 20 },
            )
            val directory =
                File(context.getExternalFilesDir(null), "branding-validation").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
