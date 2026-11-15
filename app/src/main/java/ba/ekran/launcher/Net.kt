package ba.ekran.launcher

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mrežni zahtjevi iz HTML-a idu preko /net?u=<url>.
 * Android ih šalje sam, pa nema CORS ograničenja (RSS, M3U, TMDB, vrijeme…).
 */
object Net {
    private const val UA = "Mozilla/5.0 (Linux; Android 11; MiTV Stick) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    fun proxy(target: String): WebResourceResponse {
        if (!target.startsWith("http://") && !target.startsWith("https://")) return fail(400, "Neispravan link")
        try {
            var url = URL(target)
            var redirects = 0
            while (true) {
                val c = url.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 12000
                c.readTimeout = 25000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Language", "bs,hr;q=0.9,sr;q=0.8,en;q=0.7")
                c.setRequestProperty("Accept", "*/*")
                if (url.host.endsWith("youtube.com")) c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    redirects++
                    if (loc == null || redirects > 6) return fail(502, "Previše preusmjeravanja")
                    url = URL(url, loc)
                    continue
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                val ct = c.contentType ?: "text/plain"
                c.disconnect()
                val mime = ct.substringBefore(";").trim().ifEmpty { "text/plain" }
                val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                    .find(ct)?.groupValues?.get(1)?.trim('"') ?: "utf-8"
                val status = if (code in 200..299 || code >= 400) code else 200
                return WebResourceResponse(
                    mime, charset, status, if (status < 400) "OK" else "Error",
                    mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                    ByteArrayInputStream(bytes)
                )
            }
        } catch (e: Exception) {
            return fail(502, e.message ?: "Greška mreže")
        }
    }

    private fun fail(code: Int, msg: String) = WebResourceResponse(
        "text/plain", "utf-8", code, "Error",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(msg.toByteArray())
    )
}
