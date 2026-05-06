package website.msdnna.budget_app.data.preferences

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.model.Category

/**
 * Per-device "last used at" map for category names, scoped by section. Updated
 * whenever the user picks a category for a created/edited record. Used to surface
 * recently-used categories at the top of selectors and filters; never-used
 * categories fall back to alphabetical, default-first order.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
object CategoryUsage {
    private val KEY = stringPreferencesKey("category_usage_v1")
    private val store get() = AppContainer.appContext.dataStore

    private val _state = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val usage: StateFlow<Map<String, Map<String, Long>>> = _state.asStateFlow()

    init {
        GlobalScope.launch {
            val raw = store.data.first()[KEY]
            if (raw != null) _state.value = parse(raw)
        }
    }

    fun recordUse(section: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = _state.value
        val sectionMap = (current[section] ?: emptyMap()).toMutableMap()
        sectionMap[trimmed] = System.currentTimeMillis()
        val next = current.toMutableMap().apply { put(section, sectionMap) }
        _state.value = next
        GlobalScope.launch {
            store.edit { it[KEY] = serialize(next) }
        }
    }

    private fun parse(raw: String): Map<String, Map<String, Long>> {
        return try {
            val root = JSONObject(raw)
            val out = mutableMapOf<String, Map<String, Long>>()
            for (section in root.keys()) {
                val obj = root.getJSONObject(section)
                val inner = mutableMapOf<String, Long>()
                for (name in obj.keys()) inner[name] = obj.getLong(name)
                out[section] = inner
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serialize(map: Map<String, Map<String, Long>>): String {
        val root = JSONObject()
        for ((section, inner) in map) {
            val obj = JSONObject()
            for ((name, ts) in inner) obj.put(name, ts)
            root.put(section, obj)
        }
        return root.toString()
    }
}

/**
 * Sort categories so most-recently-used are first, never-used fall back to
 * default-first then alphabetical (the previous static order).
 */
fun List<Category>.sortedByRecentUse(usageForSection: Map<String, Long>): List<Category> =
    sortedWith(compareByDescending<Category> { usageForSection[it.name] ?: 0L }
        .thenByDescending { it.isDefault }
        .thenBy { it.name.lowercase() })
