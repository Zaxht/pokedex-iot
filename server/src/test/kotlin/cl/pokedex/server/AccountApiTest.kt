package cl.pokedex.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AccountApiTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun mobileApiRegistersSharesAndRejectsMissingSession() {
        val api = AccountApi(Database(java.io.File(temp.root,"api.db")))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { e ->
            try {
                val body = try { 200 to api.handle(e).toString() } catch (x: ApiError) { x.status to JSONObject().put("error",x.message).toString() }
                val bytes = body.second.toByteArray(); e.sendResponseHeaders(body.first, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            } finally { e.close() }
        }
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            fun call(path: String, session: String = "", data: String? = null): HttpResponse<String> {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}$path"))
                if (session.isNotEmpty()) request.header("X-Session",session)
                if (data != null) request.POST(HttpRequest.BodyPublishers.ofString(data))
                return client.send(request.build(),HttpResponse.BodyHandlers.ofString())
            }
            assertEquals(401,call("/me").statusCode())
            val alice = JSONObject(call("/register",data="""{"username":"alice","password":"Demo-pass-123"}""").body()).getString("session")
            val bob = JSONObject(call("/register",data="""{"username":"bob","password":"Demo-pass-123"}""").body()).getString("session")
            val eve = JSONObject(call("/register",data="""{"username":"eve","password":"Demo-pass-123"}""").body()).getString("session")
            assertEquals(401,call("/friend-requests").statusCode())
            assertEquals(200,call("/encounters",bob,"""{"id":25,"user_id":1}""").statusCode())
            assertEquals(0,JSONObject(call("/me",alice).body()).getJSONArray("seen").length())
            // Incluso la ruta antigua debe solicitar consentimiento, nunca añadir directamente.
            val pending = JSONObject(call("/friends",alice,"""{"username":"bob","sender_id":99}""").body())
            assertEquals(0,pending.getJSONArray("friends").length())
            assertEquals("bob",pending.getJSONArray("outgoing").getString(0))
            assertEquals("alice",JSONObject(call("/friend-requests",bob).body()).getJSONArray("incoming").getString(0))
            assertEquals(404,call("/friend-requests/respond",eve,"""{"username":"alice","accept":true,"recipient_id":2}""").statusCode())
            assertEquals(400,call("/friend-requests/respond",bob,"""{"username":"alice","accept":"true"}""").statusCode())
            val accepted = JSONObject(call("/friend-requests/respond",bob,"""{"username":"alice","accept":true}""").body())
            assertEquals(0,accepted.getJSONArray("incoming").length())
            assertEquals("alice",accepted.getJSONArray("friends").getJSONObject(0).getString("username"))
            val friends = JSONObject(call("/friends",alice).body()).getJSONArray("friends")
            assertEquals(25,friends.getJSONObject(0).getJSONArray("seen").getJSONObject(0).getInt("id"))
            assertEquals(200,call("/friend-requests",eve,"""{"username":"bob"}""").statusCode())
            assertEquals(200,call("/friend-requests/respond",bob,"""{"username":"eve","accept":false}""").statusCode())
            assertEquals(0,JSONObject(call("/friends",eve).body()).getJSONArray("friends").length())
            call("/logout",bob,"{}")
            assertEquals(401,call("/me",bob).statusCode())
        } finally { server.stop(0) }
    }
}
