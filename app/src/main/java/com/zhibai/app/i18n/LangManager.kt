package com.zhibai.app.i18n

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

/**
 * Request shown when the SYSTEM language changes: the prompt is bilingual
 * (old language + new language) and asks whether to switch the app language.
 */
data class LangSwitchRequest(
    val fromCode: String,
    val toCode: String,
    /** True when a locale table exists for [toCode] (otherwise strings fall back). */
    val toAvailable: Boolean
)

/**
 * Language system groundwork.
 *
 * - Strings live in `assets/locales/<code>.json` (flat key → value).
 * - `zh-cn.json` is the default table and the fallback for every lookup, so an
 *   unsupported language simply renders the default Chinese strings.
 * - First install follows the system language when a table exists (Chinese
 *   system → zh-cn), otherwise the default.
 * - When the system language changes later, a bilingual prompt asks whether to
 *   switch; the choice is persisted.
 * - More tables (en-us.json …) are dropped in later — the lookup already
 *   resolves them the moment they appear.
 */
object LangManager {
    const val DEFAULT = "zh-cn"

    private var fallbackTable: Map<String, String> = emptyMap()
    private var table: Map<String, String> = emptyMap()
    private var available: Set<String> = emptySet()
    private var prefs: android.content.SharedPreferences? = null

    private val _current = MutableStateFlow(DEFAULT)
    val current: StateFlow<String> = _current.asStateFlow()

    private val _switchRequest = MutableStateFlow<LangSwitchRequest?>(null)
    val switchRequest: StateFlow<LangSwitchRequest?> = _switchRequest.asStateFlow()

    private var lastSystemTag: String = ""

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("app_lang", Context.MODE_PRIVATE)
        prefs = p
        fallbackTable = loadTable(context, DEFAULT)
        table = fallbackTable
        available = listLocales(context)

        val nowTag = systemTag()
        val savedSystem = p.getString("last_system", null)
        lastSystemTag = nowTag
        if (savedSystem == null) {
            p.edit().putString("last_system", nowTag).apply()
        } else if (savedSystem != nowTag) {
            // System language changed while the app was away — raise the prompt.
            p.edit().putString("last_system", nowTag).apply()
            val target = normalize(nowTag)
            if (target != _current.value) {
                _switchRequest.value = LangSwitchRequest(
                    fromCode = p.getString("app_lang", DEFAULT) ?: DEFAULT,
                    toCode = target,
                    toAvailable = target in available
                )
            }
        }

        // First launch: follow the system language when supported, else default.
        val saved = p.getString("app_lang", null)
        val start = if (saved != null) {
            saved
        } else {
            val sys = normalize(nowTag)
            if (sys in available) sys else DEFAULT
        }
        applyLocale(context, start)

        // Live detection for ROMs that deliver locale changes without recreation.
        runCatching {
            context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    onSystemLanguageMaybeChanged(context.applicationContext)
                }
                override fun onLowMemory() {}
            })
        }
    }

    /** Lookup with fallback chain: current table → zh-cn → [fallbackText]. */
    fun t(key: String, fallbackText: String = key): String =
        table[key] ?: fallbackTable[key] ?: fallbackText

    val currentCode: String get() = _current.value

    fun availableLocales(): Set<String> = available

    /** Human-readable, native language name for a locale code. */
    fun languageName(code: String): String {
        val exact = NAMES[code]
        if (exact != null) return exact
        val lang = code.substringBefore('-')
        return NAMES[lang] ?: code.uppercase(Locale.ROOT)
    }

    /** One-liner in the target language for the bilingual prompt. */
    fun promptLineIn(code: String): String {
        val name = languageName(code)
        val lang = code.substringBefore('-')
        val fn = PROMPTS[code] ?: PROMPTS[lang]
        return fn?.invoke(name) ?: "Switch app language to $name?"
    }

    /** Compare the system language with the recorded one; raise the prompt on change. */
    fun onSystemLanguageMaybeChanged(context: Context) {
        val now = systemTag()
        if (now == lastSystemTag) return
        lastSystemTag = now
        prefs?.edit()?.putString("last_system", now)?.apply()
        val target = normalize(now)
        if (target == _current.value) return
        _switchRequest.value = LangSwitchRequest(
            fromCode = _current.value,
            toCode = target,
            toAvailable = target in available
        )
    }

    /** Persist + apply a new app language. Missing tables fall back to zh-cn. */
    fun switchTo(context: Context, code: String) {
        prefs?.edit()?.putString("app_lang", code)?.apply()
        applyLocale(context, code)
        _switchRequest.value = null
    }

    fun dismissSwitch() {
        _switchRequest.value = null
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun applyLocale(context: Context, code: String) {
        _current.value = code
        table = if (code == DEFAULT) fallbackTable else loadTable(context, code)
    }

    private fun systemTag(): String = Locale.getDefault().toLanguageTag()

    private fun normalize(tag: String): String =
        tag.lowercase(Locale.ROOT).replace('_', '-')

    private fun listLocales(context: Context): Set<String> = try {
        context.assets.list("locales")
            ?.filter { it.endsWith(".json") }
            ?.map { it.removeSuffix(".json") }
            ?.toSet()
            ?: setOf(DEFAULT)
    } catch (_: Exception) {
        setOf(DEFAULT)
    }

    private fun loadTable(context: Context, code: String): Map<String, String> {
        if (code == DEFAULT && fallbackTable.isNotEmpty()) return fallbackTable
        return try {
            val raw = context.assets.open("locales/$code.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(raw)
            buildMap {
                for (k in obj.keys()) put(k, obj.optString(k))
            }
        } catch (_: Exception) {
            fallbackTable
        }
    }

    /** Native names for the languages a system switch may land on. */
    private val NAMES = mapOf(
        "zh-cn" to "简体中文", "zh" to "中文", "zh-tw" to "繁體中文", "zh-hk" to "繁體中文",
        "en" to "English", "ja" to "日本語", "ko" to "한국어",
        "fr" to "Français", "de" to "Deutsch", "es" to "Español",
        "ru" to "Русский", "it" to "Italiano", "pt" to "Português",
        "th" to "ไทย", "vi" to "Tiếng Việt", "ar" to "العربية"
    )

    /** The prompt sentence itself, spoken in the TARGET language (bilingual half). */
    private val PROMPTS = mapOf<String, (String) -> String>(
        "zh-cn" to { "系统语言已更改为 $it，是否切换 App 语言？" },
        "zh" to { "系統語言已更改為 $it，是否切換 App 語言？" },
        "zh-tw" to { "系統語言已更改為 $it，是否切換 App 語言？" },
        "en" to { "The system language changed to $it. Switch the app language?" },
        "ja" to { "システム言語が $it に変わりました。アプリの言語を切り替えますか？" },
        "ko" to { "시스템 언어가 $it(으)로 변경되었습니다. 앱 언어를 전환할까요?" },
        "fr" to { "La langue du système est passée à $it. Changer la langue de l’app ?" },
        "de" to { "Die Systemsprache wurde auf $it geändert. App-Sprache wechseln?" },
        "es" to { "El idioma del sistema cambió a $it. ¿Cambiar el idioma de la app?" },
        "ru" to { "Язык системы изменён на $it. Сменить язык приложения?" },
        "it" to { "La lingua di sistema è cambiata in $it. Cambiare la lingua dell’app?" },
        "pt" to { "O idioma do sistema mudou para $it. Trocar o idioma do app?" },
        "th" to { "ภาษาของระบบเปลี่ยนเป็น $it ต้องการเปลี่ยนภาษาของแอปหรือไม่?" },
        "vi" to { "Ngôn ngữ hệ thống đã đổi sang $it. Đổi ngôn ngữ ứng dụng?" },
        "ar" to { "تم تغيير لغة النظام إلى $it. هل تريد تغيير لغة التطبيق؟" }
    )
}
