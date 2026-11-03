package it.spesami.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** One HTTP exchange. Implemented with HttpURLConnection on Android and by a fake Drive in tests. */
data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray? = null)

data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface HttpTransport {
    /** Throws [java.io.IOException] when there is no connection. */
    fun send(request: HttpRequest): HttpResponse
}

/** Access tokens come from Google Play services on Android; the app never stores them itself. */
interface TokenProvider {
    /** A valid access token, or throws [DriveException] (e.g. [DriveException.Kind.UNAUTHORIZED]). */
    fun token(): String

    /** Called when Google rejected [token], before asking for a new one. */
    fun invalidate(token: String)
}

class DriveException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** No connection: try again later, nothing to do for the user. */
        OFFLINE,

        /** Access was removed or must be confirmed again: the user has to tap "Connetti". */
        UNAUTHORIZED,

        /** Google is busy or failing (429, 5xx): try again later. */
        TEMPORARY,

        /** Anything else. */
        FAILED,
    }
}

data class DriveFile(val id: String, val name: String)

/**
 * The few Drive REST v3 calls the sync needs, all inside the hidden app folder (`appDataFolder`, scope
 * `drive.appdata`): Spesami cannot see or touch any other file in the user's Drive. Every request goes to
 * [HOST] and nowhere else.
 */
class DriveClient(private val http: HttpTransport, private val tokens: TokenProvider) {
    /** Google's clock, from the `Date` header of the last response (epoch ms). */
    var serverTime: Long? = null
        private set

    fun list(): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val query = buildString {
                append("spaces=appDataFolder&pageSize=100&fields=")
                append(encode("nextPageToken,files(id,name)"))
                if (pageToken != null) append("&pageToken=").append(encode(pageToken))
            }
            val json = parse(call("GET", "$HOST/drive/v3/files?$query"))
            json["files"]?.jsonArray?.forEach { f ->
                val o = f.jsonObject
                files += DriveFile(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content)
            }
            pageToken = json["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return files
    }

    /** The file's text, or null if it was deleted meanwhile. */
    fun download(id: String): String? {
        val response = request("GET", "$HOST/drive/v3/files/${encode(id)}?alt=media")
        if (response.status == 404) return null
        check(response)
        return response.body
    }

    fun create(name: String, content: String): String {
        val boundary = "spesami-${System.nanoTime()}"
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", "application/json")
            putJsonArray("parents") { add(kotlinx.serialization.json.JsonPrimitive("appDataFolder")) }
        }
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$content\r\n--$boundary--\r\n"
        val json = parse(call("POST", "$HOST/upload/drive/v3/files?uploadType=multipart&fields=id", body.toByteArray(), "multipart/related; boundary=$boundary"))
        return json.getValue("id").jsonPrimitive.content
    }

    fun update(id: String, content: String) {
        call("PATCH", "$HOST/upload/drive/v3/files/${encode(id)}?uploadType=media&fields=id", content.toByteArray(), "application/json; charset=UTF-8")
    }

    private fun call(method: String, url: String, body: ByteArray? = null, contentType: String? = null): HttpResponse =
        request(method, url, body, contentType).also(::check)

    /** Sends with a token; a 401 gets a fresh token and one retry. */
    private fun request(method: String, url: String, body: ByteArray? = null, contentType: String? = null): HttpResponse {
        require(url.startsWith("$HOST/")) { "Spesami only talks to $HOST" }
        var token = tokens.token()
        repeat(2) { attempt ->
            val headers = buildMap {
                put("Authorization", "Bearer $token")
                if (contentType != null) put("Content-Type", contentType)
            }
            val response = try {
                http.send(HttpRequest(method, url, headers, body))
            } catch (e: java.io.IOException) {
                throw DriveException(DriveException.Kind.OFFLINE, "Nessuna connessione", e)
            }
            response.header("Date")?.let(::parseDate)?.let { serverTime = it }
            if (response.status != 401 || attempt == 1) return response
            tokens.invalidate(token)
            token = tokens.token()
        }
        error("unreachable")
    }

    private fun check(response: HttpResponse) {
        when {
            response.status in 200..299 -> Unit
            response.status == 401 -> throw DriveException(DriveException.Kind.UNAUTHORIZED, "Accesso a Google Drive non più valido")
            response.status == 429 || response.status >= 500 || (response.status == 403 && "rateLimit" in response.body) ->
                throw DriveException(DriveException.Kind.TEMPORARY, "Google Drive non risponde (${response.status})")
            response.status == 403 && "storageQuotaExceeded" in response.body ->
                throw DriveException(DriveException.Kind.FAILED, "Lo spazio di Google Drive è pieno")
            else -> throw DriveException(DriveException.Kind.FAILED, "Google Drive ha rifiutato la richiesta (${response.status})")
        }
    }

    private fun parse(response: HttpResponse): JsonObject = try {
        Json.parseToJsonElement(response.body).jsonObject
    } catch (e: Exception) {
        throw DriveException(DriveException.Kind.FAILED, "Risposta di Google Drive non valida", e)
    }

    companion object {
        const val HOST = "https://www.googleapis.com"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        private fun parseDate(value: String): Long? = try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/** Where the device files are exchanged: Google Drive in the app, a map in tests. Blocking: call off the main thread. */
fun interface SyncExchange {
    fun sync(local: LocalSync, current: Snapshot, deviceName: String, appVersion: String, now: Long): SyncResult
}

/**
 * One sync through Drive: read every other device's file, merge ([Sync.run]), write this device's file.
 * Each device writes only `device-<itsId>.json`; it never modifies or deletes another device's file.
 */
class DriveSync(private val drive: DriveClient) : SyncExchange {
    override fun sync(local: LocalSync, current: Snapshot, deviceName: String, appVersion: String, now: Long): SyncResult {
        val ownName = SyncFile.fileName(local.deviceId)
        val files = drive.list()
        val peers = files.filter { it.name != ownName && SyncFile.deviceIdOf(it.name) != null }
            .mapNotNull { drive.download(it.id) }
        val result = Sync.run(local, current, peers, deviceName, appVersion, now, drive.serverTime)
        val own = files.filter { it.name == ownName }.minByOrNull { it.id }
        if (own != null) drive.update(own.id, result.ownFile) else drive.create(ownName, result.ownFile)
        return result
    }
}
