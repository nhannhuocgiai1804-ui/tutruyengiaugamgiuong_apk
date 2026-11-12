package com.terabox.reader.network

import android.util.Base64
import com.terabox.reader.data.TeraBoxFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Port toàn bộ các hàm API TeraBox từ app.py
 */
object TeraBoxApi {

    private val bdstokenCache = ConcurrentHashMap<String, String>()
    private val dlinkCache = ConcurrentHashMap<String, String>()
    private val shortUrlInfoCache = ConcurrentHashMap<String, JSONObject>()

    // Port từ app.py: extract_surl(url)
    fun extractSurl(url: String): String {
        val m = Pattern.compile("/s/([A-Za-z0-9_\\-]+)").matcher(url)
        if (m.find()) {
            return m.group(1) ?: ""
        }
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        for (k in listOf("surl", "short")) {
            val v = httpUrl.queryParameter(k)
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    private fun findBetween(s: String, start: String, end: String): String {
        val i = s.indexOf(start)
        if (i == -1) return ""
        val startPos = i + start.length
        val j = s.indexOf(end, startPos)
        if (j == -1) return ""
        return s.substring(startPos, j)
    }

    // Port từ app.py: get_bdstoken(sess)
    suspend fun getBdstoken(client: OkHttpClient, cookieStr: String): String = withContext(Dispatchers.IO) {
        bdstokenCache[cookieStr]?.let { return@withContext it }

        for (host in TeraBoxSession.orderedHosts()) {
            try {
                val url = "https://$host/api/gettemplatevariable?clienttype=0&app_id=250528&web=1&fields=["bdstoken","uk","timestamp","tpltype","token"]"
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", "https://$host/")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    val result = json.optJSONObject("result")
                    val bt = result?.optString("bdstoken", "") ?: ""
                    if (bt.isNotEmpty()) {
                        bdstokenCache[cookieStr] = bt
                        TeraBoxSession.markHostOk(host)
                        return@withContext bt
                    }
                }
            } catch (_: Exception) {}

            try {
                val req2 = Request.Builder()
                    .url("https://$host/main")
                    .header("Referer", "https://$host/")
                    .build()
                val resp2 = client.newCall(req2).execute()
                val html = resp2.body?.string() ?: ""
                var bt = findBetween(html, ""bdstoken":"", """)
                if (bt.isEmpty()) {
                    bt = findBetween(html, "'bdstoken':'", "'")
                }
                if (bt.length >= 16) {
                    bdstokenCache[cookieStr] = bt
                    TeraBoxSession.markHostOk(host)
                    return@withContext bt
                }
            } catch (_: Exception) {}
        }
        return@withContext ""
    }

    // Port từ app.py: api_shorturlinfo(sess, surl)
    suspend fun apiShortUrlInfo(client: OkHttpClient, surl: String): Result<Pair<JSONObject, String>> = withContext(Dispatchers.IO) {
        for (host in TeraBoxSession.orderedHosts()) {
            try {
                val url = "https://$host/api/shorturlinfo?app_id=250528&shorturl=$surl&root=1&web=1&channel=dubox&clienttype=0"
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", "https://$host/")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (body.startsWith("{")) {
                    val d = JSONObject(body)
                    if (d.optInt("errno", -1) == 0) {
                        TeraBoxSession.markHostOk(host)
                        return@withContext Result.success(Pair(d, host))
                    }
                }
            } catch (_: Exception) {}
        }
        return@withContext Result.failure(Exception("Không lấy được thông tin Link Share. Kiểm tra link hoặc mạng."))
    }

    // Port từ app.py: api_list_files(sess, surl, host, info, dir_path="/", fs_id=None)
    suspend fun apiListFiles(
        client: OkHttpClient,
        cookieStr: String,
        surl: String,
        host: String,
        info: JSONObject,
        dirPath: String = "/"
    ): Result<List<TeraBoxFile>> = withContext(Dispatchers.IO) {
        val uk = info.optString("uk", "")
        val shareid = info.optString("shareid", "")
        val sign = info.optString("sign", "")
        val ts = info.optString("timestamp", "")
        val isRoot = dirPath == "/" || dirPath.isEmpty()

        for (h in TeraBoxSession.orderedHosts()) {
            try {
                val urlBuilder = "https://$h/share/list".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("app_id", "250528")
                    .addQueryParameter("web", "1")
                    .addQueryParameter("channel", "dubox")
                    .addQueryParameter("clienttype", "0")
                    .addQueryParameter("uk", uk)
                    .addQueryParameter("shareid", shareid)
                    .addQueryParameter("sign", sign)
                    .addQueryParameter("timestamp", ts)
                    .addQueryParameter("shorturl", surl)
                    .addQueryParameter("order", "name")
                    .addQueryParameter("desc", "0")
                    .addQueryParameter("num", "500")
                    .addQueryParameter("page", "1")
                    .addQueryParameter("root", if (isRoot) "1" else "0")
                    .addQueryParameter("dir", if (isRoot) "/" else dirPath)

                val req = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Referer", "https://$h/sharing/link?surl=$surl")
                    .build()

                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (body.startsWith("{")) {
                    val d = JSONObject(body)
                    if (d.optInt("errno", -1) == 0) {
                        TeraBoxSession.markHostOk(h)
                        val list = parseFileList(d)
                        return@withContext Result.success(list)
                    }
                    if (d.optInt("errno", -1) == 105) break
                }
            } catch (_: Exception) {}
        }

        // Fallback: /api/list với bdstoken
        val bdstoken = info.optString("bdstoken").ifEmpty { getBdstoken(client, cookieStr) }
        val realPath = if (!isRoot) dirPath else info.optString("real_path", "/")

        for (h in TeraBoxSession.orderedHosts()) {
            try {
                val urlBuilder = "https://$h/api/list".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("app_id", "250528")
                    .addQueryParameter("web", "1")
                    .addQueryParameter("channel", "dubox")
                    .addQueryParameter("clienttype", "0")
                    .addQueryParameter("order", "name")
                    .addQueryParameter("desc", "0")
                    .addQueryParameter("showempty", "0")
                    .addQueryParameter("num", "500")
                    .addQueryParameter("page", "1")
                    .addQueryParameter("dir", realPath)
                    .addQueryParameter("dlink", "1")

                if (bdstoken.isNotEmpty()) {
                    urlBuilder.addQueryParameter("bdstoken", bdstoken)
                }

                val req = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Referer", "https://$h/main")
                    .build()

                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (body.startsWith("{")) {
                    val d = JSONObject(body)
                    if (d.optInt("errno", -1) == 0) {
                        TeraBoxSession.markHostOk(h)
                        val list = parseFileList(d)
                        return@withContext Result.success(list)
                    }
                }
            } catch (_: Exception) {}
        }

        return@withContext Result.failure(Exception("Không lấy được danh sách file từ link chia sẻ."))
    }

    // Port từ app.py: api_list_own(sess, dir_path="/")
    suspend fun apiListOwn(
        client: OkHttpClient,
        cookieStr: String,
        dirPath: String = "/"
    ): Result<List<TeraBoxFile>> = withContext(Dispatchers.IO) {
        val bdstoken = getBdstoken(client, cookieStr)
        val realPath = if (dirPath.isEmpty()) "/" else dirPath

        val orders = listOf("name" to "0", "time" to "1")
        for (h in TeraBoxSession.orderedHosts()) {
            for ((ord, desc) in orders) {
                try {
                    val urlBuilder = "https://$h/api/list".toHttpUrlOrNull()!!.newBuilder()
                        .addQueryParameter("app_id", "250528")
                        .addQueryParameter("web", "1")
                        .addQueryParameter("channel", "dubox")
                        .addQueryParameter("clienttype", "0")
                        .addQueryParameter("order", ord)
                        .addQueryParameter("desc", desc)
                        .addQueryParameter("showempty", "0")
                        .addQueryParameter("num", "500")
                        .addQueryParameter("page", "1")
                        .addQueryParameter("dir", realPath)
                        .addQueryParameter("dlink", "1")

                    if (bdstoken.isNotEmpty()) {
                        urlBuilder.addQueryParameter("bdstoken", bdstoken)
                    }

                    val req = Request.Builder()
                        .url(urlBuilder.build())
                        .header("Referer", "https://$h/main")
                        .header("Origin", "https://$h")
                        .build()

                    val resp = client.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    if (body.startsWith("{")) {
                        val d = JSONObject(body)
                        if (d.optInt("errno", -1) == 0) {
                            TeraBoxSession.markHostOk(h)
                            val list = parseFileList(d)
                            return@withContext Result.success(list)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return@withContext Result.failure(Exception("Không lấy được Ổ Đĩa Riêng. Cookie ndus có thể đã hết hạn."))
    }

    private fun parseFileList(json: JSONObject): List<TeraBoxFile> {
        val raw = json.optJSONArray("list") ?: JSONArray()
        val items = mutableListOf<TeraBoxFile>()
        for (i in 0 until raw.length()) {
            val f = raw.optJSONObject(i) ?: continue
            val fsid = f.optLong("fs_id", 0L)
            val dlink = f.optString("dlink", "")
            if (dlink.isNotEmpty() && fsid != 0L) {
                dlinkCache[fsid.toString()] = dlink
            }
            val name = f.optString("server_filename").ifEmpty { f.optString("filename", "") }
            val path = f.optString("path", "")
            val size = f.optLong("size", 0L)
            val isDir = f.optInt("isdir", 0) == 1

            items.add(TeraBoxFile(fsId = fsid, name = name, path = path, size = size, isDir = isDir))
        }
        return items
    }

    // Port từ app.py: _terabox_rc4_sign(s1, s2)
    fun teraboxRc4Sign(s1: String, s2: String): String {
        val v = s1.length
        val a = IntArray(256) { q -> s1[q % v].code }
        val p = IntArray(256) { it }
        var u = 0
        for (q in 0 until 256) {
            u = (u + p[q] + a[q]) % 256
            val temp = p[q]
            p[q] = p[u]
            p[u] = temp
        }
        val out = ByteArray(s2.length)
        var i = 0
        u = 0
        for (q in s2.indices) {
            i = (i + 1) % 256
            u = (u + p[i]) % 256
            val temp = p[i]
            p[i] = p[u]
            p[u] = temp
            val k = p[(p[i] + p[u]) % 256]
            out[q] = (s2[q].code xor k).toByte()
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    // Port từ app.py: get_terabox_download_sign(sess)
    suspend fun getTeraboxDownloadSign(client: OkHttpClient): String? = withContext(Dispatchers.IO) {
        for (h in TeraBoxSession.orderedHosts()) {
            try {
                val url = "https://$h/api/home/info?app_id=250528&web=1&channel=dubox&clienttype=0"
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", "https://$h/main")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (body.startsWith("{")) {
                    val d = JSONObject(body)
                    if (d.optInt("errno", -1) == 0) {
                        val data = d.optJSONObject("data")
                        val s1 = data?.optString("sign1", "") ?: ""
                        val s3 = data?.optString("sign3", "") ?: ""
                        if (s1.isNotEmpty() && s3.isNotEmpty()) {
                            return@withContext teraboxRc4Sign(s3, s1)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return@withContext null
    }

    // Port từ app.py: api_download_own(sess, fs_id, file_path, bdstoken)
    suspend fun apiDownloadOwn(
        client: OkHttpClient,
        fsId: Long,
        filePath: String,
        bdstoken: String
    ): String? = withContext(Dispatchers.IO) {
        if (bdstoken.isEmpty()) return@withContext null
        val signStr = getTeraboxDownloadSign(client)

        for (h in TeraBoxSession.orderedHosts()) {
            for (withPath in if (filePath.isNotEmpty()) listOf(false, true) else listOf(false)) {
                try {
                    val urlBuilder = "https://$h/api/download".toHttpUrlOrNull()!!.newBuilder()
                        .addQueryParameter("type", "dlink")
                        .addQueryParameter("app_id", "250528")
                        .addQueryParameter("web", "1")
                        .addQueryParameter("channel", "dubox")
                        .addQueryParameter("clienttype", "0")
                        .addQueryParameter("vip", "2")
                        .addQueryParameter("fidlist", "[$fsId]")
                        .addQueryParameter("bdstoken", bdstoken)
                        .addQueryParameter("timestamp", (System.currentTimeMillis() / 1000).toString())

                    if (!signStr.isNullOrEmpty()) {
                        urlBuilder.addQueryParameter("sign", signStr)
                    }
                    if (withPath) {
                        urlBuilder.addQueryParameter("path", filePath)
                    }

                    val req = Request.Builder()
                        .url(urlBuilder.build())
                        .header("Referer", "https://$h/main")
                        .build()

                    val resp = client.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    if (body.startsWith("{")) {
                        val d = JSONObject(body)
                        if (d.optInt("errno", -1) == 0) {
                            val dl = d.optJSONArray("dlink")
                            var link = ""
                            if (dl != null && dl.length() > 0) {
                                link = dl.getJSONObject(0).optString("dlink", "")
                            } else {
                                val listArr = d.optJSONArray("list")
                                if (listArr != null && listArr.length() > 0) {
                                    link = listArr.getJSONObject(0).optString("dlink", "")
                                }
                            }
                            if (link.isNotEmpty()) {
                                TeraBoxSession.markHostOk(h)
                                return@withContext link
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return@withContext null
    }

    // Port từ app.py: api_filemetas_dlink(sess, fs_id, file_path, bdstoken)
    suspend fun apiFilemetasDlink(
        client: OkHttpClient,
        fsId: Long,
        filePath: String,
        bdstoken: String
    ): String? = withContext(Dispatchers.IO) {
        if (bdstoken.isEmpty() || filePath.isEmpty()) return@withContext null
        for (h in TeraBoxSession.orderedHosts()) {
            try {
                val urlBuilder = "https://$h/api/filemetas".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("app_id", "250528")
                    .addQueryParameter("web", "1")
                    .addQueryParameter("channel", "dubox")
                    .addQueryParameter("clienttype", "0")
                    .addQueryParameter("dlink", "1")
                    .addQueryParameter("origin", "dlna")
                    .addQueryParameter("target", "["$filePath"]")
                    .addQueryParameter("bdstoken", bdstoken)

                val req = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Referer", "https://$h/main")
                    .build()

                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (body.startsWith("{")) {
                    val d = JSONObject(body)
                    if (d.optInt("errno", -1) == 0) {
                        val infoArr = d.optJSONArray("info")
                        if (infoArr != null && infoArr.length() > 0) {
                            val link = infoArr.getJSONObject(0).optString("dlink", "")
                            if (link.isNotEmpty()) {
                                TeraBoxSession.markHostOk(h)
                                return@withContext link
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return@withContext null
    }

    // Port từ app.py: get_dlink(sess, share_url, fs_id, file_path)
    suspend fun getDlink(
        client: OkHttpClient,
        cookieStr: String,
        fsId: Long,
        filePath: String
    ): String? = withContext(Dispatchers.IO) {
        val cached = dlinkCache[fsId.toString()]
        if (!cached.isNullOrEmpty()) return@withContext cached

        val bdstoken = getBdstoken(client, cookieStr)
        var dlink = apiDownloadOwn(client, fsId, filePath, bdstoken)
        if (dlink.isNullOrEmpty()) {
            dlink = apiFilemetasDlink(client, fsId, filePath, bdstoken)
        }
        if (!dlink.isNullOrEmpty()) {
            dlinkCache[fsId.toString()] = dlink
            return@withContext dlink
        }
        return@withContext null
    }
}
