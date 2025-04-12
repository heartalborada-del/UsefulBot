package me.heartalborada.commons.okhttp

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class CookieStorageProvider : CookieJar {
    private val cookieStore = HashMap<String, MutableList<Cookie>>()
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!cookieStore.containsKey("${url.host}:${url.port}")) return ArrayList()
        return cookieStore["${url.host}:${url.port}"]!!
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookieStore["${url.host}:${url.port}"] == null || !cookieStore.containsKey("${url.host}:${url.port}")) {
            cookieStore["${url.host}:${url.port}"] = cookies.toMutableList()
        } else {
            cookieStore["${url.host}:${url.port}"]?.addAll(cookies)
        }
    }
}