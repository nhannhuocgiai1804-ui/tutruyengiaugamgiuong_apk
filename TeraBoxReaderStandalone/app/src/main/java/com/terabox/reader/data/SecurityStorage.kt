package com.terabox.reader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class SecurityStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "terabox_standalone_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences("terabox_standalone_prefs_fallback", Context.MODE_PRIVATE)
    }

    // Quản lý Profiles & Cookie ndus
    fun saveProfile(name: String, cookie: String) {
        val profs = getProfiles().toMutableMap()
        profs[name] = cookie.trim()
        val json = JSONObject(profs as Map<*, *>).toString()
        prefs.edit().putString("key_profiles_json", json).apply()
        if (getActiveProfile().isEmpty()) {
            setActiveProfile(name)
        }
    }

    fun deleteProfile(name: String) {
        val profs = getProfiles().toMutableMap()
        profs.remove(name)
        val json = JSONObject(profs as Map<*, *>).toString()
        prefs.edit().putString("key_profiles_json", json).apply()
        if (getActiveProfile() == name) {
            setActiveProfile(profs.keys.firstOrNull() ?: "")
        }
    }

    fun getProfiles(): Map<String, String> {
        val raw = prefs.getString("key_profiles_json", "{}") ?: "{}"
        val map = mutableMapOf<String, String>()
        val json = JSONObject(raw)
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = json.optString(k, "")
        }
        return map
    }

    fun getActiveProfile(): String = prefs.getString("key_active_profile", "") ?: ""
    fun setActiveProfile(name: String) = prefs.edit().putString("key_active_profile", name).apply()

    fun getCurrentCookie(): String {
        val act = getActiveProfile()
        return getProfiles()[act] ?: ""
    }

    // Lưu Lịch sử đọc (save_progress)
    fun saveProgress(item: ReadHistoryItem) {
        val list = getHistory().toMutableList()
        val idx = list.indexOfFirst { it.fsId == item.fsId }
        if (idx >= 0) {
            list[idx] = item
        } else {
            list.add(0, item)
        }
        val array = JSONArray()
        for (h in list.take(100)) {
            val o = JSONObject()
            o.put("fsId", h.fsId)
            o.put("name", h.name)
            o.put("path", h.path)
            o.put("url", h.url)
            o.put("lastRead", h.lastRead)
            o.put("progress", h.progress)
            o.put("total", h.total)
            array.put(o)
        }
        prefs.edit().putString("key_history_json", array.toString()).apply()
    }

    fun getHistory(): List<ReadHistoryItem> {
        val raw = prefs.getString("key_history_json", "[]") ?: "[]"
        val array = JSONArray(raw)
        val list = mutableListOf<ReadHistoryItem>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(
                ReadHistoryItem(
                    fsId = o.optLong("fsId", 0L),
                    name = o.optString("name", ""),
                    path = o.optString("path", ""),
                    url = o.optString("url", ""),
                    lastRead = o.optLong("lastRead", 0L),
                    progress = o.optInt("progress", 0),
                    total = o.optInt("total", 1)
                )
            )
        }
        return list
    }

    fun clearHistory() = prefs.edit().remove("key_history_json").apply()

    // Cài đặt đọc sách
    fun saveSettings(s: ReaderSettings) {
        val o = JSONObject().apply {
            put("fontFamily", s.fontFamily)
            put("fontSize", s.fontSize)
            put("lineHeight", s.lineHeight.toDouble())
            put("textColor", s.textColor)
            put("bgColor", s.bgColor)
            put("pageMode", s.pageMode)
            put("theme", s.theme)
            put("maxWidth", s.maxWidth)
            put("volumeKeyFlip", s.volumeKeyFlip)
            put("keepScreenOn", s.keepScreenOn)
            put("lockOrientation", s.lockOrientation)
            put("maxCacheMb", s.maxCacheMb)
        }
        prefs.edit().putString("key_settings_json", o.toString()).apply()
    }

    fun getSettings(): ReaderSettings {
        val raw = prefs.getString("key_settings_json", "")
        if (raw.isNullOrEmpty()) return ReaderSettings()
        val o = JSONObject(raw)
        return ReaderSettings(
            fontFamily = o.optString("fontFamily", "Georgia, serif"),
            fontSize = o.optInt("fontSize", 18),
            lineHeight = o.optDouble("lineHeight", 1.8).toFloat(),
            textColor = o.optString("textColor", "#e8e6e3"),
            bgColor = o.optString("bgColor", "#141518"),
            pageMode = o.optString("pageMode", "scroll"),
            theme = o.optString("theme", "dark"),
            maxWidth = o.optInt("maxWidth", 820),
            volumeKeyFlip = o.optBoolean("volumeKeyFlip", true),
            keepScreenOn = o.optBoolean("keepScreenOn", true),
            lockOrientation = o.optBoolean("lockOrientation", false),
            maxCacheMb = o.optInt("maxCacheMb", 500)
        )
    }
}
