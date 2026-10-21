package com.example.byd.system

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.example.byd.data.AppPrefs
import java.util.Locale

/**
 * The app's own language: the phone's, Hebrew or English. Android 13+ keeps it per app (it also
 * shows in the phone's app settings); older phones keep it in our prefs and apply it to the screen.
 */
object AppLanguage {
    const val SYSTEM = ""
    const val HEBREW = "he"
    const val ENGLISH = "en"

    fun get(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) SYSTEM else normalize(locales[0].language)
    } else {
        AppPrefs(context).language
    }

    /** Switches language now; the screen re-opens in it. */
    fun set(activity: Activity, tag: String) {
        if (tag == get(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            AppPrefs(activity).language = tag
            activity.recreate()
        }
    }

    /** Before Android 13: the activity's context in the chosen language (see MainActivity). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = AppPrefs(base).language.takeIf { it != SYSTEM } ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }

    // Java reports Hebrew as "iw" on some versions.
    private fun normalize(language: String) = if (language == "iw") HEBREW else language
}
