package com.slava.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("chystyi_launcher_prefs", Context.MODE_PRIVATE)

    companion object {
        const val MAX_FAVORITES = 8
        const val SCRIM_LIGHT = 0
        const val SCRIM_MEDIUM = 1
        const val SCRIM_DEEP = 2
        const val SCRIM_AMOLED = 3

        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2
    }

    var isInitialized: Boolean
        get() = sp.getBoolean("initialized", false)
        set(value) = sp.edit().putBoolean("initialized", value).apply()

    fun getFavoriteKeys(): List<String> {
        val raw = sp.getString("favorites_ordered", null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    fun setFavoriteKeys(keys: List<String>) {
        sp.edit().putString("favorites_ordered", keys.take(MAX_FAVORITES).joinToString("|")).apply()
    }

    fun addFavorite(key: String): Boolean {
        val current = getFavoriteKeys().toMutableList()
        if (current.contains(key)) return true
        if (current.size >= MAX_FAVORITES) return false
        current.add(key)
        setFavoriteKeys(current)
        return true
    }

    fun removeFavorite(key: String) {
        val current = getFavoriteKeys().toMutableList()
        if (current.remove(key)) {
            setFavoriteKeys(current)
        }
    }

    fun moveFavorite(key: String, delta: Int) {
        val current = getFavoriteKeys().toMutableList()
        val idx = current.indexOf(key)
        if (idx < 0) return
        val newIdx = (idx + delta).coerceIn(0, current.lastIndex)
        if (newIdx == idx) return
        val item = current.removeAt(idx)
        current.add(newIdx, item)
        setFavoriteKeys(current)
    }

    fun replaceFavorite(oldKey: String, newKey: String) {
        val current = getFavoriteKeys().toMutableList()
        val idx = current.indexOf(oldKey)
        if (idx >= 0) {
            current.remove(newKey)
            val insertIdx = idx.coerceAtMost(current.size)
            if (insertIdx < current.size) {
                current[insertIdx] = newKey
            } else {
                current.add(newKey)
            }
            setFavoriteKeys(current)
        }
    }

    fun getHiddenKeys(): Set<String> {
        return sp.getStringSet("hidden_keys", emptySet())?.toSet() ?: emptySet()
    }

    fun setHidden(key: String, hidden: Boolean) {
        val next = getHiddenKeys().toMutableSet()
        if (hidden) next.add(key) else next.remove(key)
        sp.edit().putStringSet("hidden_keys", next).apply()
    }

    fun getCustomLabel(key: String): String? {
        return sp.getString("label_$key", null)?.takeIf { it.isNotBlank() }
    }

    fun setCustomLabel(key: String, label: String?) {
        val editor = sp.edit()
        if (label.isNullOrBlank()) {
            editor.remove("label_$key")
        } else {
            editor.putString("label_$key", label.trim())
        }
        editor.apply()
    }

    var swipeLeftKey: String?
        get() = sp.getString("swipe_left_key", null)
        set(value) = sp.edit().putString("swipe_left_key", value).apply()

    var swipeRightKey: String?
        get() = sp.getString("swipe_right_key", null)
        set(value) = sp.edit().putString("swipe_right_key", value).apply()

    var clockTapKey: String?
        get() = sp.getString("clock_tap_key", null)
        set(value) = sp.edit().putString("clock_tap_key", value).apply()

    var dateTapKey: String?
        get() = sp.getString("date_tap_key", null)
        set(value) = sp.edit().putString("date_tap_key", value).apply()

    var scrimMode: Int
        get() = sp.getInt("scrim_mode", SCRIM_MEDIUM)
        set(value) = sp.edit().putInt("scrim_mode", value).apply()

    var alignment: Int
        get() = sp.getInt("alignment", ALIGN_LEFT)
        set(value) = sp.edit().putInt("alignment", value).apply()

    var autoLaunchSingle: Boolean
        get() = sp.getBoolean("auto_launch_single", true)
        set(value) = sp.edit().putBoolean("auto_launch_single", value).apply()

    var showSwipeHint: Boolean
        get() = sp.getBoolean("show_swipe_hint", true)
        set(value) = sp.edit().putBoolean("show_swipe_hint", value).apply()

    fun ensureSmartDefaults(allApps: List<AppEntry>) {
        if (allApps.isEmpty()) return

        if (!isInitialized) {
            val chosen = ArrayList<String>()

            fun pickFirst(predicate: (AppEntry) -> Boolean) {
                if (chosen.size >= 6) return
                val match = allApps.firstOrNull { !chosen.contains(it.componentKey) && predicate(it) }
                if (match != null) chosen.add(match.componentKey)
            }

            // 1. «Стан» якщо встановлений
            pickFirst {
                it.packageName.contains("stan", ignoreCase = true) ||
                    it.originalLabel.equals("Стан", ignoreCase = true)
            }
            // 2. Telegram
            pickFirst {
                it.packageName.contains("telegram", ignoreCase = true) ||
                    it.originalLabel.contains("Telegram", ignoreCase = true)
            }
            // 3. Телефон
            pickFirst {
                it.packageName in setOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer") ||
                    it.originalLabel.lowercase(Locale.ROOT) in setOf("телефон", "phone")
            }
            // 4. Браузер (Chrome / Samsung Internet)
            pickFirst {
                it.packageName in setOf("com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox")
            }
            // 5. Камера
            pickFirst {
                it.packageName in setOf("com.sec.android.app.camera", "com.android.camera", "com.android.camera2") ||
                    it.originalLabel.lowercase(Locale.ROOT) in setOf("камера", "camera")
            }
            // 6. YouTube / PDF Watcher / Налаштування
            pickFirst {
                it.packageName.contains("youtube", ignoreCase = true) ||
                    it.packageName == "com.slava.pdfwatcher"
            }
            pickFirst {
                it.packageName == "com.android.settings" ||
                    it.originalLabel.lowercase(Locale.ROOT) in setOf("налаштування", "settings")
            }

            for (app in allApps) {
                if (chosen.size >= 6) break
                if (!chosen.contains(app.componentKey)) {
                    chosen.add(app.componentKey)
                }
            }

            setFavoriteKeys(chosen)

            // Дефолт для свайпу вліво → Камера
            if (swipeLeftKey == null) {
                val camera = allApps.firstOrNull {
                    it.packageName in setOf("com.sec.android.app.camera", "com.android.camera", "com.android.camera2") ||
                        it.originalLabel.lowercase(Locale.ROOT) in setOf("камера", "camera")
                }
                if (camera != null) swipeLeftKey = camera.componentKey
            }

            // Дефолт для свайпу вправо → «Стан» або Телефон
            if (swipeRightKey == null) {
                val rightApp = allApps.firstOrNull {
                    it.packageName.contains("stan", ignoreCase = true) ||
                        it.originalLabel.equals("Стан", ignoreCase = true)
                } ?: allApps.firstOrNull {
                    it.packageName in setOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer") ||
                        it.originalLabel.lowercase(Locale.ROOT) in setOf("телефон", "phone")
                }
                if (rightApp != null) swipeRightKey = rightApp.componentKey
            }

            isInitialized = true
        }
    }
}
