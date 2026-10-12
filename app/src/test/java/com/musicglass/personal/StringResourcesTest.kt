package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Q13: the text a test without Android reads straight from strings.xml (Strings.kt) is, for every string and
 * plural there is, the text Android gives for it. So what those tests assert is what the app shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StringResourcesTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val slot = Regex("%(\\d+)\\$([sd])")
    private fun ours(type: Class<*>) = type.fields.filter { it.type == Int::class.javaPrimitiveType }.map { it.name to it.getInt(null) }
    /** One argument for each numbered place, of the kind the place takes. */
    private fun arguments(pattern: String): Array<Any?> {
        val kinds = slot.findAll(pattern).associate { it.groupValues[1].toInt() to it.groupValues[2] }
        return Array((kinds.keys.maxOrNull() ?: 0)) { i -> if (kinds[i + 1] == "d") 7 + i else "<arg ${i + 1}>" }
    }

    @Test fun everyStringReadsTheSameWithoutAndroid() {
        val strings = ours(R.string::class.java)
        assertTrue("strings.xml has ${strings.size} strings", strings.size > 700)
        strings.forEach { (name, id) ->
            val args = arguments(resources.getString(id))
            assertEquals(name, resources.getString(id), DefaultStrings.text(id, emptyArray()))
            if (args.isNotEmpty()) assertEquals(name, resources.getString(id, *args), DefaultStrings.text(id, args))
        }
    }

    @Test fun everyPluralReadsTheSameWithoutAndroid() {
        val plurals = ours(R.plurals::class.java)
        assertTrue(plurals.size > 40)
        plurals.forEach { (name, id) -> for (count in listOf(0, 1, 2, 11)) {
            val args = arguments(resources.getQuantityString(id, count)).also { if (it.isNotEmpty()) it[0] = count }
            assertEquals("$name for $count", resources.getQuantityString(id, count, *args), DefaultStrings.quantity(id, count, args))
        } }
    }

    @Test fun theAppReadsItsStringsFromItsResources() {
        // RiffApplication installs them before anything else runs.
        assertEquals(resources.getString(R.string.retry), string(R.string.retry))
        assertEquals("1 song", quantity(R.plurals.n_songs, 1)); assertEquals("3 songs", quantity(R.plurals.n_songs, 3))
        assertEquals("Songs", keyLabel("Songs")); assertEquals("not a key", keyLabel("not a key"))
    }

    @Test fun aNumberedPlaceIsNeverLeftUnfilled() {
        // A string with places is always asked for with that many arguments: none shows a bare %1$s.
        val source = java.io.File("src/main/java/com/musicglass/personal").listFiles()!!.filter { it.extension == "kt" }.joinToString(" ") { it.readText() }
        ours(R.string::class.java).forEach { (name, id) ->
            val places = arguments(resources.getString(id)).size
            if (places > 0) assertFalse("$name is asked for without arguments", Regex("R\\.string\\.$name\\)").containsMatchIn(source) && !Regex("R\\.string\\.$name,").containsMatchIn(source))
        }
    }
}
