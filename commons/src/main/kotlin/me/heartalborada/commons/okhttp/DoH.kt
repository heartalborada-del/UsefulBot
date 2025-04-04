package me.heartalborada.commons.okhttp

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class DoH(private val dohUrl: String = "https://cloudflare-dns.com/dns-query") : Dns {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun lookup(hostname: String): List<InetAddress> {
        val request = Request.Builder()
            .url("$dohUrl?name=$hostname&type=A")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UnknownHostException("Failed to resolve $hostname")

            val responseBody = response.body?.string() ?: throw UnknownHostException("Empty response for $hostname")

            val dohResponse = gson.fromJson(responseBody, DoHResponse::class.java)
            val answers = dohResponse.answer ?: throw UnknownHostException("No addresses found for $hostname")

            val addresses = mutableListOf<InetAddress>()
            for (answer in answers) {
                val ip = answer.data
                if (ip.isNotEmpty()) {
                    addresses.add(InetAddress.getByName(ip))
                }
            }

            if (addresses.isEmpty()) throw UnknownHostException("No addresses found for $hostname")
            return addresses
        }
    }

    private data class DoHResponse(
        @SerializedName("Answer")
        val answer: List<Answer>?
    )

    private data class Answer(
        val data: String
    )
}