package com.loopin.player2.core.sync

import com.loopin.player2.core.cache.NormalMediaContent
import com.loopin.player2.core.model.MediaType
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.*

class AuthenticatedRemoteSourcesTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterTest fun close() = servers.forEach { it.stop(0) }

    @Test fun `200 decodes schema2 and sends bearer plus etag`() {
        var authorization: String? = null; var condition: String? = null
        val endpoint = server { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            condition = exchange.requestHeaders.getFirst("If-None-Match")
            exchange.responseHeaders.add("ETag", "\"${"a".repeat(64)}\"")
            respond(exchange, 200, manifestJson())
        }
        val active = com.loopin.player2.core.cache.PublishedVersionRef("p", 1, "b".repeat(64), "c".repeat(64), 1)
        val result = AuthenticatedRemoteManifestSource(endpoint, credential = { "device-secret" }).fetch(active)
        assertIs<RemoteManifestResult.Available>(result)
        assertEquals("Bearer device-secret", authorization)
        assertEquals("\"${"b".repeat(64)}\"", condition)
    }

    @Test fun `status mappings preserve remote meaning`() {
        listOf(204 to RemoteManifestResult.NoAssignment, 304 to RemoteManifestResult.Unchanged,
            401 to RemoteManifestResult.AuthenticationFailed).forEach { (status, expected) ->
            val result = AuthenticatedRemoteManifestSource(server { respond(it, status, null) }, credential = { "secret" }).fetch(null)
            assertEquals(expected, result)
        }
    }

    @Test fun `409 is structural and 500 retryable`() {
        val conflict = AuthenticatedRemoteManifestSource(server { respond(it, 409, "{}") }, credential = { "secret" }).fetch(null)
        assertFalse((conflict as RemoteManifestResult.Failed).retryable)
        val unavailable = AuthenticatedRemoteManifestSource(server { respond(it, 503, "{}") }, credential = { "secret" }).fetch(null)
        assertTrue((unavailable as RemoteManifestResult.Failed).retryable)
    }

    @Test fun `invalid schema2 is non retryable`() {
        val endpoint = server { it.responseHeaders.add("ETag", "\"${"a".repeat(64)}\""); respond(it, 200, "{}") }
        val result = AuthenticatedRemoteManifestSource(endpoint, credential = { "secret" }).fetch(null)
        assertFalse((result as RemoteManifestResult.Failed).retryable)
    }

    @Test fun `weak gateway etag is accepted`() {
        val endpoint = server { it.responseHeaders.add("ETag", "W/\"${"a".repeat(64)}\""); respond(it, 200, manifestJson()) }
        assertIs<RemoteManifestResult.Available>(AuthenticatedRemoteManifestSource(endpoint, credential = { "secret" }).fetch(null))
    }

    @Test fun `media authorization resolves fresh url and streams bytes`() {
        var bearer: String? = null; var requested = ""
        lateinit var download: String
        val media = server { exchange -> bearer=exchange.requestHeaders.getFirst("Authorization");requested=exchange.requestBody.bufferedReader().readText();respond(exchange,200,"{\"download_url\":\"$download\",\"expires_in\":900}") }
        download = server { respond(it, 200, "payload") }
        val item = NormalMediaContent(MediaType.VIDEO, "00000000-0000-4000-8000-000000000001", null, 7, "a".repeat(64), "video/mp4")
        val bytes = AuthenticatedRemoteMediaSourceFactory(media, credential = { "device-secret" }).sourceFor(item)!!.open().use { it.readBytes() }
        assertEquals("payload", bytes.toString(Charsets.UTF_8)); assertEquals("Bearer device-secret", bearer); assertTrue(requested.contains(item.assetId))
    }

    private fun server(handler: (HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { createContext("/", handler); start() }
        servers += server
        return "http://127.0.0.1:${server.address.port}/"
    }
    private fun respond(exchange: HttpExchange, status: Int, body: String?) {
        val bytes = body?.toByteArray() ?: ByteArray(0); exchange.sendResponseHeaders(status, if (body == null) -1 else bytes.size.toLong())
        if (body != null) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }
    private fun manifestJson() = """{"schemaVersion":2,"playlistId":"p","playlistVersion":1,"generatedAtEpochMs":1,"items":[{"id":"w","order":0,"kind":"DYNAMIC","dynamicType":"WEATHER","durationMs":1000,"configuration":{"city":"X","lat":"0","lon":"0"}}]}"""
}
