package it.spesami.app.sync

import it.spesami.core.sync.HttpRequest
import it.spesami.core.sync.HttpResponse
import it.spesami.core.sync.HttpTransport
import java.net.HttpURLConnection
import java.net.URL

/** [HttpTransport] over the platform's HttpURLConnection: no extra HTTP library. Call from a background thread. */
class UrlConnectionTransport : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH; Google APIs accept the override header on a POST.
            if (request.method == "PATCH") {
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                connection.requestMethod = request.method
            }
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
            return HttpResponse(status, text, headers)
        } finally {
            connection.disconnect()
        }
    }
}
