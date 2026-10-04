package app.line.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** Interface language: Russian, English or Kazakh, chosen in the profile; defaults to the device language. */
object Locales {
    val supported = listOf("ru", "en", "kk")

    fun language(context: Context): String {
        val saved = context.getSharedPreferences("line-ui", Context.MODE_PRIVATE).getString("language", null)
        if (saved in supported) return saved!!
        val system = Locale.getDefault().language
        return if (system in supported) system else "en"
    }

    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(language(context)))
        return context.createConfigurationContext(config)
    }

    fun set(context: Context, language: String) {
        require(language in supported)
        context.getSharedPreferences("line-ui", Context.MODE_PRIVATE).edit().putString("language", language).apply()
    }
}
