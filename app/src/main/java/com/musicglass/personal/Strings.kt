package com.musicglass.personal

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Where every sentence and label the app shows or speaks comes from (Q13): res/values/strings.xml, read through
 * the application's resources so that a change of language reaches text made outside a screen as well - a
 * notification, an error raised by a repository, a queue subtitle.
 *
 * Words the code compares or looks things up by ("Songs", "Play next") stay literals; [keyLabel] gives the
 * text to show for one.
 */
internal object AppStrings {
    @Volatile private var resources: Resources? = null
    fun install(source: Resources) { resources = source }

    fun text(@StringRes id: Int, args: Array<out Any?>): String {
        val source = resources ?: return DefaultStrings.text(id, args)
        return if (args.isEmpty()) source.getString(id) else source.getString(id, *args)
    }
    fun quantity(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String {
        val source = resources ?: return DefaultStrings.quantity(id, count, args)
        return source.getQuantityString(id, count, *args)
    }
}

internal fun string(@StringRes id: Int, vararg args: Any?): String = AppStrings.text(id, args)
/** "1 song", "3 songs": [count] chooses the form and is the first thing the text can show. */
internal fun quantity(@PluralsRes id: Int, count: Int, vararg args: Any?): String = AppStrings.quantity(id, count, arrayOf(count, *args))

class RiffApplication : Application() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(base); AppStrings.install(resources) }
}

/**
 * The default text, read from strings.xml itself. Only a test that runs without Android reaches this: the
 * file is on the test classpath and never in the app. StringResourcesTest holds it to what Android returns.
 */
internal object DefaultStrings {
    private val names: Map<Int, String> by lazy {
        (R.string::class.java.fields.asSequence() + R.plurals::class.java.fields.asSequence()).associate { it.getInt(null) to it.name }
    }
    private val entries: Map<String, Map<String, String>> by lazy {
        val stream = checkNotNull(DefaultStrings::class.java.classLoader?.getResourceAsStream("strings.xml")) { "Strings were asked for before the application started" }
        val root = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement }
        val nodes = root.childNodes
        buildMap {
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as? Element ?: continue
                when (node.tagName) {
                    "string" -> put(node.getAttribute("name"), mapOf("other" to unescape(node.textContent)))
                    "plurals" -> put(node.getAttribute("name"), buildMap {
                        val items = node.getElementsByTagName("item")
                        for (j in 0 until items.length) (items.item(j) as Element).let { put(it.getAttribute("quantity"), unescape(it.textContent)) }
                    })
                }
            }
        }
    }
    private fun raw(id: Int, quantity: String): String {
        val forms = entries.getValue(names.getValue(id))
        return forms[quantity] ?: forms.getValue("other")
    }
    fun text(id: Int, args: Array<out Any?>): String = raw(id, "other").let { if (args.isEmpty()) it else String.format(Locale.getDefault(), it, *args) }
    fun quantity(id: Int, count: Int, args: Array<out Any?>): String = String.format(Locale.getDefault(), raw(id, if (count == 1) "one" else "other"), *args)

    /** What the resource compiler does to the text between the tags, for the forms this file uses. */
    private fun unescape(text: String): String {
        val body = if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text.substring(1, text.length - 1) else text
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (val next = body[i + 1]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t')
                    'u' -> { out.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> out.append(next)
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
