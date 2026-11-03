package it.spesami.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * An in-memory Google Drive that answers the REST v3 calls the way the real one does for the hidden app folder:
 * list with paging, download (alt=media), multipart create, media update, 401 for a bad token, and a `Date`
 * header. Failures can be injected. Only https://www.googleapis.com is served.
 */
class FakeDrive(var pageSize: Int = 100) : HttpTransport {
    data class File(val id: String, val name: String, var content: String, val parents: List<String>)

    val files = LinkedHashMap<String, File>()
    val requests = mutableListOf<HttpRequest>()
    val validTokens = mutableSetOf("token-1")
    var now: Long = 1_793_700_000_000L
    var offline = false
    /** Statuses returned (once each) instead of serving the next requests. */
    val failNext = ArrayDeque<Int>()
    private var nextId = 1

    fun put(name: String, content: String): String {
        val id = "f${nextId++}"
        files[id] = File(id, name, content, listOf("appDataFolder"))
        return id
    }

    fun content(name: String): String? = files.values.singleOrNull { it.name == name }?.content

    override fun send(request: HttpRequest): HttpResponse {
        if (offline) throw java.io.IOException("offline")
        requests += request
        val uri = URI(request.url)
        require(uri.scheme == "https" && uri.host == "www.googleapis.com") { "unexpected host ${uri.host}" }
        val date = mapOf("Date" to DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC)))
        failNext.removeFirstOrNull()?.let { return HttpResponse(it, """{"error":{"code":$it}}""", date) }
        val token = request.headers["Authorization"]?.removePrefix("Bearer ")
        if (token !in validTokens) return HttpResponse(401, """{"error":{"code":401,"status":"UNAUTHENTICATED"}}""", date)
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val path = uri.path
        return when {
            request.method == "GET" && path == "/drive/v3/files" -> {
                require(query["spaces"] == "appDataFolder") { "must list only the app folder" }
                val all = files.values.filter { "appDataFolder" in it.parents }
                val start = query["pageToken"]?.toInt() ?: 0
                val page = all.drop(start).take(minOf(pageSize, query["pageSize"]?.toInt() ?: 100))
                val body = buildJsonObject {
                    put("files", buildJsonArray { page.forEach { f -> add(buildJsonObject { put("id", f.id); put("name", f.name) }) } })
                    if (start + page.size < all.size) put("nextPageToken", (start + page.size).toString())
                }
                HttpResponse(200, body.toString(), date)
            }
            request.method == "GET" && path.startsWith("/drive/v3/files/") && query["alt"] == "media" -> {
                val file = files[path.removePrefix("/drive/v3/files/")] ?: return HttpResponse(404, """{"error":{"code":404}}""", date)
                HttpResponse(200, file.content, date)
            }
            request.method == "POST" && path == "/upload/drive/v3/files" && query["uploadType"] == "multipart" -> {
                val boundary = request.headers.getValue("Content-Type").substringAfter("boundary=")
                val parts = String(request.body!!).split("--$boundary").map { it.trim() }.filter { it.isNotEmpty() && it != "--" }
                val metadata = Json.parseToJsonElement(parts[0].substringAfter("\r\n\r\n")).jsonObject
                val content = parts[1].substringAfter("\r\n\r\n")
                val parents = metadata["parents"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                require(parents == listOf("appDataFolder")) { "must create in the app folder" }
                val id = "f${nextId++}"
                files[id] = File(id, metadata.getValue("name").jsonPrimitive.content, content, parents)
                HttpResponse(200, buildJsonObject { put("id", JsonPrimitive(id)) }.toString(), date)
            }
            request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") && query["uploadType"] == "media" -> {
                val file = files[path.removePrefix("/upload/drive/v3/files/")] ?: return HttpResponse(404, "{}", date)
                file.content = String(request.body!!)
                HttpResponse(200, """{"id":"${file.id}"}""", date)
            }
            else -> HttpResponse(400, """{"error":"unsupported ${request.method} $path"}""", date)
        }
    }
}

/** Tokens as Play services would hand them out: a new one after the old was invalidated. */
class FakeTokens(private val drive: FakeDrive) : TokenProvider {
    var current = "token-1"
    var revoked = false
    /** False: Google keeps rejecting even the renewed token. */
    var renewalAccepted = true
    val invalidated = mutableListOf<String>()

    override fun token(): String {
        if (revoked) throw DriveException(DriveException.Kind.UNAUTHORIZED, "revoked")
        return current
    }

    override fun invalidate(token: String) {
        invalidated += token
        current = "token-${invalidated.size + 1}"
        if (renewalAccepted) drive.validTokens += current
    }
}
