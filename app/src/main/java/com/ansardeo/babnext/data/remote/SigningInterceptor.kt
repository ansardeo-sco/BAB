package com.ansardeo.babnext.data.remote

import com.ansardeo.babnext.data.security.CredentialStore
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

class SigningInterceptor @Inject constructor(private val creds: CredentialStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val apiKey = creds.getDemoApiKey()
        val secret = creds.getDemoApiSecret()
        if (apiKey.isNullOrBlank() || secret.isNullOrBlank()) {
            return chain.proceed(original)
        }
        // Only sign requests that carry parameters or are trading endpoints; attach to all for simplicity.
        val url = original.url
        val newUrlBuilder = url.newBuilder()
        // Add timestamp
        val timestamp = System.currentTimeMillis()
        newUrlBuilder.addQueryParameter("timestamp", timestamp.toString())
        newUrlBuilder.addQueryParameter("recvWindow", "5000")

        // Build query string for signing (sorted by key as Binance expects original order? Binance signs the query string as sent)
        val queryParams = mutableListOf<Pair<String, String>>()
        url.queryParameterNames.forEach { name ->
            url.queryParameterValues(name).forEach { v -> queryParams.add(name to v) }
        }
        queryParams.add("timestamp" to timestamp.toString())
        queryParams.add("recvWindow" to "5000")
        val queryString = queryParams.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val signature = mac.doFinal(queryString.toByteArray()).joinToString("") { "%02x".format(it) }

        newUrlBuilder.addQueryParameter("signature", signature)

        val newRequest = original.newBuilder()
            .url(newUrlBuilder.build())
            .addHeader("X-MBX-APIKEY", apiKey)
            .build()
        return chain.proceed(newRequest)
    }
}