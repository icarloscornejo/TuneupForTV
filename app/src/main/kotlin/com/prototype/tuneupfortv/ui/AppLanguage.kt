package com.prototype.tuneupfortv.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Languages the app ships. [SYSTEM] follows the device; anything unsupported falls back to English. */
enum class AppLanguage(val tag: String, private val locale: Locale?) {
    SYSTEM("system", null),
    ENGLISH("en", Locale.forLanguageTag("en")),
    SPANISH("es", Locale.forLanguageTag("es"));

    /** A context whose resources use this language. Called from `attachBaseContext`. */
    fun wrap(base: Context): Context {
        val target = locale ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(target)
        return base.createConfigurationContext(configuration)
    }

    companion object {
        fun fromTag(tag: String): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
