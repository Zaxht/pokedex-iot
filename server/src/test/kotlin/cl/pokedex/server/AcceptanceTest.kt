package cl.pokedex.server

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Integración real de AccountApi y SQLite en loopback. No representa teléfonos ni una red Wi-Fi. */
class AcceptanceTest {
    @get:Rule val temp = TemporaryFolder()

    private class Endpoint(db: Database, port: Int = 0) : AutoCloseable {
        private val api = AccountApi(db)
        private val executor = Executors.newFixedThreadPool(4)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 8)
        val address get() = "http://127.0.0.1:${server.address.port}"
        val port get() = server.address.port
        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    val result = try {
                        200 to (api.handle(exchange) ?: throw ApiError(404, "No route"))
                    } catch (e: ApiError) { e.status to JSONObject().put("error", e.message) }
                    val bytes = result.second.toString().toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(result.first, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally { exchange.close() }
            }
            server.start()
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    private fun client() = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private fun call(client: HttpClient, address: String, path: String, token: String = "", data: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(address + path)).timeout(Duration.ofSeconds(10))
        if (token.isNotEmpty()) request.header("X-Session", token)
        if (data != null) request.POST(HttpRequest.BodyPublishers.ofString(data))
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun account(db: Database, name: String) = db.authenticate(name, "Acceptance-only-123", true).getString("session")
    private fun ids(response: HttpResponse<String>) = JSONObject(response.body()).getJSONArray("seen").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).getInt("id") }.toSet()
    }

    @Test fun twoConcurrentClientsKeepCollectionsSeparateAndShareAfterConsent() {
        val db = Database(File(temp.root, "concurrent.db"))
        val alice = account(db, "accept_alice"); val bob = account(db, "accept_bob")
        Endpoint(db).use { endpoint ->
            val start = CountDownLatch(1)
            val workers = Executors.newFixedThreadPool(2)
            val started = System.nanoTime()
            val timings = try {
                val tasks = listOf(alice to 1, bob to 52).map { (session, first) ->
                    workers.submit(Callable {
                        val http = client(); start.await()
                        (first until first + 100).map { id ->
                            val before = System.nanoTime()
                            val response = call(http, endpoint.address, "/encounters", session, """{"id":$id}""")
                            assertEquals(200, response.statusCode())
                            assertTrue(ids(response).contains(id))
                            (System.nanoTime() - before) / 1_000_000.0
                        }
                    })
                }
                start.countDown()
                tasks.flatMap { it.get(120, TimeUnit.SECONDS) }.sorted()
            } finally { workers.shutdownNow() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            val http = client()
            assertEquals((1..100).toSet(), ids(call(http, endpoint.address, "/me", alice)))
            assertEquals((52..151).toSet(), ids(call(http, endpoint.address, "/me", bob)))
            assertEquals(200, call(http, endpoint.address, "/friend-requests", alice, """{"username":"accept_bob"}""").statusCode())
            val pending = JSONObject(call(http, endpoint.address, "/friends", bob).body())
            assertEquals(1, pending.getJSONArray("incoming").length())
            assertEquals(0, pending.getJSONArray("friends").length())
            assertEquals(200, call(http, endpoint.address, "/friend-requests/respond", bob, """{"username":"accept_alice","accept":true}""").statusCode())
            val friends = JSONObject(call(http, endpoint.address, "/friends", alice).body()).getJSONArray("friends")
            assertEquals(100, friends.getJSONObject(0).getJSONArray("seen").length())
            println("ACCEPTANCE_METRIC " + JSONObject().put("scenario", "two_clients_loopback").put("clients", 2)
                .put("requests", timings.size).put("successes", timings.size).put("elapsedMs", elapsedMs)
                .put("medianMs", timings[timings.size / 2]).put("p95Ms", timings[(timings.size * 0.95).toInt() - 1])
                .put("maxMs", timings.last()).put("requestsPerSecond", timings.size * 1000 / elapsedMs))
        }
    }

    @Test fun stoppedEndpointFailsAndRestartRecoversPersistentSessionAndCollection() {
        val file = File(temp.root, "recovery.db"); val db = Database(file)
        val token = account(db, "recovery_user")
        val first = Endpoint(db); val address = first.address; val port = first.port
        val http = client()
        try { assertEquals(200, call(http, address, "/encounters", token, """{"id":25}""").statusCode()) }
        finally { first.close() }
        try { call(http, address, "/me", token); fail("Stopped endpoint accepted request") }
        catch (_: java.io.IOException) { }
        Endpoint(Database(file), port).use { restarted ->
            assertEquals(setOf(25), ids(call(http, restarted.address, "/me", token)))
            repeat(3) { assertEquals(200, call(http, restarted.address, "/encounters", token, """{"id":25}""").statusCode()) }
            assertEquals(setOf(25), ids(call(http, restarted.address, "/me", token)))
        }
    }

    @Test fun rejectsMalformedLargeUnauthorizedAndExcessiveAuthenticationRequests() {
        val db = Database(File(temp.root, "limits.db")); val token = account(db, "limits_user")
        Endpoint(db).use { endpoint ->
            val http = client()
            assertEquals(401, call(http, endpoint.address, "/me").statusCode())
            assertEquals(401, call(http, endpoint.address, "/me", "forged").statusCode())
            assertEquals(405, call(http, endpoint.address, "/encounters", token).statusCode())
            assertEquals(400, call(http, endpoint.address, "/encounters", token, "{").statusCode())
            assertEquals(413, call(http, endpoint.address, "/encounters", token, "x".repeat(4097)).statusCode())
            repeat(10) { assertEquals(401, call(http, endpoint.address, "/login", data = """{"username":"limits_user","password":"wrong-pass"}""").statusCode()) }
            assertEquals(429, call(http, endpoint.address, "/login", data = """{"username":"limits_user","password":"wrong-pass"}""").statusCode())
            assertEquals(200, call(http, endpoint.address, "/me", token).statusCode())
            assertEquals(200, call(http, endpoint.address, "/logout", token, "{}").statusCode())
            assertEquals(401, call(http, endpoint.address, "/me", token).statusCode())
        }
    }

    @Test fun sqliteBackupRestoresUsersRequestsAndEncounters() {
        val file = File(temp.root, "source.db"); val backup = File(temp.root, "backup.db")
        val db = Database(file); val aliceToken = account(db, "backup_alice"); val bobToken = account(db, "backup_bob")
        val alice = db.user(aliceToken); val bob = db.user(bobToken)
        db.record(alice, 25, "camera"); db.requestFriend(alice, "backup_bob")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.prepareStatement("VACUUM INTO ?").use { statement -> statement.setString(1, backup.absolutePath); statement.execute() }
        }
        val restored = Database(backup)
        assertEquals(alice, restored.user(aliceToken)); assertEquals(bob, restored.user(bobToken))
        assertEquals(25, restored.collection(alice).getJSONObject(0).getInt("id"))
        assertEquals("camera", restored.collection(alice).getJSONObject(0).getString("source"))
        assertEquals("backup_alice", restored.requests(bob).getJSONArray("incoming").getString(0))
        DriverManager.getConnection("jdbc:sqlite:${backup.absolutePath}").use { c -> c.createStatement().use { s ->
            s.executeQuery("PRAGMA quick_check").use { rows -> assertTrue(rows.next()); assertEquals("ok", rows.getString(1)) }
        } }
    }
}
