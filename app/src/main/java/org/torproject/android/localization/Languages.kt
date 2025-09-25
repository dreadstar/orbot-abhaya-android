package org.torproject.android.localization

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Resources
import android.text.TextUtils
import android.util.DisplayMetrics
import java.util.*

class Languages private constructor(activity: Activity) {
    /**
     * Return an array of the names of all the supported languages, sorted to
     * match what is returned by [Languages.supportedLocales].
     *
     * @return
     */
    val allNames: Array<String>
        get() = nameMap.values.toTypedArray()

    val supportedLocales: Array<String>
        get() {
            val keys = nameMap.keys
            return keys.toTypedArray()
        }

    companion object {
        private var defaultLocale: Locale? = null
        val TIBETAN = Locale.forLanguageTag("bo")
        val localesToTest = arrayOf(
            Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN,
            Locale.ITALIAN, Locale.JAPANESE, Locale.KOREAN,
            Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE,
            TIBETAN, Locale.forLanguageTag("af"), Locale.forLanguageTag("am"),
            Locale.forLanguageTag("ar"), Locale.forLanguageTag("ay"), Locale.forLanguageTag("az"),
            Locale.forLanguageTag("bg"), Locale.forLanguageTag("be"), Locale.forLanguageTag("bn-BD"),
            Locale.forLanguageTag("bn-IN"),
            Locale.forLanguageTag("bn"), Locale.forLanguageTag("ca"), Locale.forLanguageTag("cs"),
            Locale.forLanguageTag("da"), Locale.forLanguageTag("el"), Locale.forLanguageTag("es"),
            Locale.forLanguageTag("es-MX"),
            Locale.forLanguageTag("es-CU"),

            Locale.forLanguageTag("es-AR"),
            Locale.forLanguageTag("en-GB"),
            Locale.forLanguageTag("eo"),
            Locale.forLanguageTag("et"), Locale.forLanguageTag("eu"), Locale.forLanguageTag("fa"),
            Locale.forLanguageTag("fr"),
            Locale.forLanguageTag("fi"), Locale.forLanguageTag("gl"),
            Locale.forLanguageTag("gu"),
            Locale.forLanguageTag("guc"),
            Locale.forLanguageTag("gum"),
            Locale.forLanguageTag("nah"),
            Locale.forLanguageTag("hi"),
            Locale.forLanguageTag("hr"), Locale.forLanguageTag("hu"), Locale.forLanguageTag("hy-AM"),
            Locale.forLanguageTag("ia"),
            Locale.forLanguageTag("in"), Locale.forLanguageTag("hy"), Locale.forLanguageTag("in"),
            Locale.forLanguageTag("is"), Locale.forLanguageTag("it"), Locale.forLanguageTag("iw"),
            Locale.forLanguageTag("ka"), Locale.forLanguageTag("kk"), Locale.forLanguageTag("km"),
            Locale.forLanguageTag("kn"), Locale.forLanguageTag("ky"), Locale.forLanguageTag("lo"),
            Locale.forLanguageTag("lt"), Locale.forLanguageTag("lv"), Locale.forLanguageTag("mk"),
            Locale.forLanguageTag("ml"), Locale.forLanguageTag("mn"), Locale.forLanguageTag("mr"),
            Locale.forLanguageTag("ms"), Locale.forLanguageTag("my"), Locale.forLanguageTag("nb"),
            Locale.forLanguageTag("ne"), Locale.forLanguageTag("nl"),
            Locale.forLanguageTag("pa"),
            Locale.forLanguageTag("pbb"),

            Locale.forLanguageTag("pl"),
            Locale.forLanguageTag("pt-BR"),
            Locale.forLanguageTag("pt"), Locale.forLanguageTag("rm"), Locale.forLanguageTag("ro"),
            Locale.forLanguageTag("ru"), Locale.forLanguageTag("si-LK"), Locale.forLanguageTag("sk"),
            Locale.forLanguageTag("sl"), Locale.forLanguageTag("sn"), Locale.forLanguageTag("sq"), Locale.forLanguageTag("sr"),
            Locale.forLanguageTag("sv"), Locale.forLanguageTag("sw"), Locale.forLanguageTag("ta"),
            Locale.forLanguageTag("te"), Locale.forLanguageTag("th"), Locale.forLanguageTag("tl"),
            Locale.forLanguageTag("tr"), Locale.forLanguageTag("uk"), Locale.forLanguageTag("ur"),
            Locale.forLanguageTag("uz"), Locale.forLanguageTag("vi"), Locale.forLanguageTag("zu")
        )
        private const val USE_SYSTEM_DEFAULT = ""
        private const val DEFAULT_STRING = "Use System Default"
        private var locale: Locale? = null
        private var singleton: Languages? = null
        private var clazz: Class<*>? = null
        private var resId = 0
        private val tmpMap: MutableMap<String, String> = TreeMap()
        private lateinit var nameMap: Map<String, String>

        /**
         * Get the instance of [Languages] to work with, providing the
         * [Activity] that is will be working as part of, as well as the
         * `resId` that has the exact string "Use System Default",
         * i.e. `R.string.use_system_default`.
         *
         *
         * That string resource `resId` is also used to find the supported
         * translations: if an included translation has a translated string that
         * matches that `resId`, then that language will be included as a
         * supported language.
         *
         * @param clazz the [Class] of the default `Activity`,
         * usually the main `Activity` from where the
         * Settings is launched from.
         * @param resId the string resource ID to for the string "Use System Default",
         * e.g. `R.string.use_system_default`
         * @return
         */
        @JvmStatic
        fun setup(clazz: Class<*>?, resId: Int) {
            defaultLocale = Locale.getDefault()
            if (Companion.clazz == null) {
                Companion.clazz = clazz
                Companion.resId = resId
            } else {
                throw RuntimeException("Languages singleton was already initialized, duplicate call to Languages.setup()!")
            }
        }

        /**
         * Get the singleton to work with.
         *
         * @param activity the [Activity] this is working as part of
         * @return
         */
        @JvmStatic
        operator fun get(activity: Activity): Languages? {
            if (singleton == null) {
                singleton = Languages(activity)
            }
            return singleton
        }

        @JvmStatic
        @SuppressLint("NewApi")
        fun setLanguage(contextWrapper: ContextWrapper, language: String?, refresh: Boolean) {
            locale =
                if (locale != null && TextUtils.equals(locale!!.language, language) && !refresh) {
                    return  // already configured
                } else if (language == null || language === USE_SYSTEM_DEFAULT) {
                    defaultLocale
                } else {
                    /* handle locales with the country in it, i.e. zh_CN, zh_TW, etc */
                    val localeSplit = language.split("_".toRegex()).toTypedArray()
                    if (localeSplit.size > 1) {
                        // language_country format like zh_CN -> use language-tag with hyphen
                        Locale.forLanguageTag(localeSplit[0] + "-" + localeSplit[1])
                    } else {
                        Locale.forLanguageTag(language)
                    }
                }
            setLocale(contextWrapper, locale)
        }

        private fun setLocale(contextWrapper: ContextWrapper, locale: Locale?) {
            val resources = contextWrapper.resources
            val configuration = resources.configuration
            configuration.setLocale(locale)
            contextWrapper.applicationContext.createConfigurationContext(configuration)
        }
    }

    init {
        val assets = activity.assets
        val config = activity.resources.configuration
        // Resources() requires DisplayMetrics, but they are only needed for drawables
        val ignored = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(ignored)
        var resources: Resources
        val localeSet: MutableSet<Locale> = LinkedHashSet()
        for (locale in localesToTest) {
            resources = Resources(assets, ignored, config)
            if (!TextUtils.equals(DEFAULT_STRING, resources.getString(resId))
                || locale == Locale.ENGLISH
            ) localeSet.add(locale)
        }
        for (locale in localeSet) {
            if (locale == TIBETAN) {
                // include English name for devices without Tibetan font support
                tmpMap[TIBETAN.toString()] = "Tibetan བོད་སྐད།" // Tibetan
            } else if (locale == Locale.SIMPLIFIED_CHINESE) {
                tmpMap[Locale.SIMPLIFIED_CHINESE.toString()] = "中文 (中国)" // Chinese (China)
            } else if (locale == Locale.TRADITIONAL_CHINESE) {
                tmpMap[Locale.TRADITIONAL_CHINESE.toString()] = "中文 (台灣)" // Chinese (Taiwan)
            } else if (locale.language.equals("pbb")) {
                tmpMap["pbb"] = "Páez"
            } else if (locale.language.equals("gum")) {
                tmpMap["gum"] = "Guambiano"
            } else if (locale.language.equals("guc")) {
                tmpMap["guc"] = "Wayuu"
            } else if (locale.language.equals("nah")) {
                tmpMap["nah"] = "Nahuatl"
            } else if (locale.country.equals("cu", true)) {
                tmpMap[locale.toString()] = "Español Cubano"
            } else {
                val displayLang = locale.getDisplayLanguage(locale).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase()
                    else it.toString()
                }
                tmpMap[locale.toString()] = "$displayLang ${locale.getDisplayCountry(locale)}"
            }
        }

        /* USE_SYSTEM_DEFAULT is a fake one for displaying in a chooser menu. */
        // localeSet.add(null);
        // tmpMap.put(USE_SYSTEM_DEFAULT, activity.getString(resId));
        nameMap = Collections.unmodifiableMap(tmpMap)
    }
}