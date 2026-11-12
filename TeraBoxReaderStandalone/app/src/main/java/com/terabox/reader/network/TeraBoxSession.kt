package com.terabox.reader.network

import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Port từ app.py:
 * - UA: Chrome User Agent
 * - API_HOSTS: danh sách host API TeraBox
 * - mark_host_ok: đưa host phản hồi thành công lên đầu danh sách
 * - make_session: tạo OkHttpClient với CookieJar cho tất cả domain TeraBox
 */
object TeraBoxSession {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Port từ app.py: API_HOSTS = ["dm.terabox.com","www.1024terabox.com","www.terabox.com","www.teraboxapp.com"]
    private val apiHosts = mutableListOf(
        "dm.terabox.com",
        "www.1024terabox.com",
        "www.terabox.com",
        "www.teraboxapp.com"
    )
    private val hostLock = Any()

    fun orderedHosts(): List<String> {
        synchronized(hostLock) {
            return apiHosts.toList()
        }
    }

    // Port từ app.py: mark_host_ok(host)
    fun markHostOk(host: String) {
        synchronized(hostLock) {
            if (apiHosts.contains(host) && apiHosts[0] != host) {
                apiHosts.remove(host)
                apiHosts.add(0, host)
            }
        }
    }

    private val sessionCache = ConcurrentHashMap<String, OkHttpClient>()

    // Port từ app.py: make_session(cookie_str)
    fun makeSession(cookieStr: String): OkHttpClient {
        val key = cookieStr.trim()
        sessionCache[key]?.let { return it }

        var formattedCookie = key
        if (formattedCookie.isNotEmpty() && !formattedCookie.contains("=")) {
            formattedCookie = "ndus=$formattedCookie"
        }

        // Parse cookie jar map
        val cookieMap = mutableMapOf<String, String>()
        for (part in formattedCookie.split(";")) {
            val tokens = part.trim().split("=", limit = 2)
            if (tokens.isNotEmpty() && tokens[0].isNotBlank()) {
                val v = if (tokens.size > 1) tokens[1] else ""
                cookieMap[tokens[0].trim()] = v.trim()
            }
        }

        val targetDomains = listOf(
            "terabox.com", "dm.terabox.com", "1024terabox.com",
            "teraboxapp.com", "www.terabox.com", "www.1024terabox.com"
        )

        val customCookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                // Tự động lưu cookie mới từ server
                for (c in cookies) {
                    cookieMap[c.name] = c.value
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val list = mutableListOf<Cookie>()
                for ((name, value) in cookieMap) {
                    if (value.isNotEmpty()) {
                        try {
                            val builder = Cookie.Builder()
                                .name(name)
                                .value(value)
                                .domain(url.host)
                                .path("/")
                            list.add(builder.build())
                        } catch (_: Exception) {}
                    }
                }
                return list
            }
        }

        // Connection pool tương đương pool_connections=20, pool_maxsize=40
        val connectionPool = ConnectionPool(20, 5, TimeUnit.MINUTES)

        val client = OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .cookieJar(customCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val orig = chain.request()
                val reqBuilder = orig.newBuilder()
                    .header("User-Agent", UA)
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
                    .header("Connection", "keep-alive")
                
                // Gắn raw cookie header trực tiếp phòng khi CookieJar bị bỏ qua
                if (formattedCookie.isNotEmpty()) {
                    reqBuilder.header("Cookie", formattedCookie)
                }
                chain.proceed(reqBuilder.build())
            }
            .build()

        sessionCache[key] = client
        return client
    }
}
