package com.terabox.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Port từ app.py: _gist_pull, _gist_push
 * Sao lưu và khôi phục dữ liệu qua GitHub Gist (Token + Gist ID)
 */
object GistBackupManager {

    private val client = OkHttpClient()

    suspend fun pullBackup(ghToken: String, gistId: String): String? = withContext(Dispatchers.IO) {
        if (ghToken.isEmpty() || gistId.isEmpty()) return@withContext null
        try {
            val req = Request.Builder()
                .url("https://api.github.com/gists/$gistId")
                .header("Authorization", "token $ghToken")
                .header("Accept", "application/vnd.github+json")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "")
                val files = json.optJSONObject("files")
                val readerData = files?.optJSONObject("reader_data.json")
                return@withContext readerData?.optString("content", null)
            }
        } catch (_: Exception) {}
        return@withContext null
    }

    suspend fun pushBackup(ghToken: String, gistId: String, content: String): Boolean = withContext(Dispatchers.IO) {
        if (ghToken.isEmpty() || gistId.isEmpty()) return@withContext false
        try {
            val payload = JSONObject().apply {
                val files = JSONObject().apply {
                    put("reader_data.json", JSONObject().apply {
                        put("content", content)
                    })
                }
                put("files", files)
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://api.github.com/gists/$gistId")
                .patch(body)
                .header("Authorization", "token $ghToken")
                .header("Accept", "application/vnd.github+json")
                .build()
            val resp = client.newCall(req).execute()
            return@withContext resp.isSuccessful
        } catch (_: Exception) {
            return@withContext false
        }
    }
}
