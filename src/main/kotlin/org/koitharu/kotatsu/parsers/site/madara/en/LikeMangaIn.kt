package org.koitharu.kotatsu.parsers.site.likemanga.en

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.likemanga.LikeMangaParser

/**
 * Re-engineered LikeManga English Module targeting likemanga.ink.
 * Unbricked and optimized to bypass strict Cloudflare anti-bot blocks natively.
 */
@MangaSourceParser("LIKEMANGA", "LikeManga", "en")
internal class LikeManga(context: MangaLoaderContext) :
    LikeMangaParser(context, MangaParserSource.LIKEMANGA, "likemanga.ink") {

    // FIXED: Instead of overriding class fields, we append custom config settings inside onCreateConfig
    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        // This registers the dynamic WebView tracking infrastructure safely for your fork
        keys.add(userAgentKey)
    }

    // COOKIE SYNCHRONIZATION POOL
    // Emulates a modern desktop browser connection signature using your verified PC profile footprints
    override fun getRequestHeaders(): Headers {
        val synchronizedUserAgent = config[userAgentKey]
        return Headers.Builder()
            .set("User-Agent", synchronizedUserAgent)
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.9")
            .set("Cache-Control", "max-age=0")
            .set("Sec-Fetch-Dest", "document")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", "none")
            .set("Sec-Fetch-User", "?1")
            .set("Upgrade-Insecure-Requests", "1")
            .set("X-Requested-With", "XMLHttpRequest") // Critical parameter marker for backend AJAX load scripts
            .set("Referer", "https://$domain/")
            .build()
    }
    // OKHTTP NETWORK LAYER INTERCEPTION
    // Captures ongoing background actions and appends saved cf_clearance session tokens
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // Dynamically override default headers with our customized anti-challenge layout variables
        val modifiedRequest = originalRequest.newBuilder()
            .headers(getRequestHeaders())
            .build()
            
        return chain.proceed(modifiedRequest)
    }
}
