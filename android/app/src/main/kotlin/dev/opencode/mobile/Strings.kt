package dev.opencode.mobile

import java.util.Locale

/** Native screens speak Russian when the device is set to Russian and English otherwise. The web UI has its own i18n. */
object Lang {
    val ru: Boolean = Locale.getDefault().language.equals("ru", ignoreCase = true)
}

fun tr(ru: String, en: String): String = if (Lang.ru) ru else en
