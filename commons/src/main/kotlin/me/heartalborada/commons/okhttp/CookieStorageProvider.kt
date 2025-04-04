package me.heartalborada.commons.okhttp

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class CookieStorageProvider: CookieJar {
    private val cookieStore = HashMap<String, List<Cookie>>()
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!cookieStore.containsKey("${url.host}:${url.port}")) return ArrayList()
        return cookieStore["${url.host}:${url.port}"]!!
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookieStore["${url.host}:${url.port}"] = cookies
    }
}