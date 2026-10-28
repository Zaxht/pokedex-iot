package cl.pokedex.server

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager

class DatabaseTest {
    @get:Rule val temp = TemporaryFolder()
    private fun database() = Database(java.io.File(temp.root, "pokedex.db"))
    private fun account(db: Database, name: String) = db.authenticate(name, "Demo-pass-123", true).getString("session")
    private fun failure(status: Int, action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (e: ApiError) { assertEquals(status, e.status) }
    }
    @Test fun expansionPreservesExistingAccountsAndAccepts151() {
        val db = database(); val token = account(db,"trainer"); val user = db.user(token)
        db.record(user,25,"camera")
        val reopened = database()
        assertEquals(user,reopened.user(token))
        for (id in 1..151) reopened.record(user,id,"manual")
        assertEquals(151,reopened.collection(user).length())
        assertEquals("camera",(0 until 151).map { reopened.collection(user).getJSONObject(it) }.first { it.getInt("id")==25 }.getString("source"))
        failure(400) { reopened.record(user,0,"manual") }
        failure(400) { reopened.record(user,152,"manual") }
    }
    @Test fun accountsSurviveRestartAndPasswordsAreHashed() {
        val db = database()
        val token = account(db, "Alice")
        val restarted = database()
        assertEquals(db.user(token), restarted.user(restarted.authenticate("alice", "Demo-pass-123", false).getString("session")))
        failure(409) { account(db, "ALICE") }
        failure(401) { db.authenticate("alice", "wrong-pass", false) }
        DriverManager.getConnection("jdbc:sqlite:${temp.root}/pokedex.db").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT hash FROM users").use { r -> assertTrue(r.next()); assertNotEquals("Demo-pass-123",r.getString(1)) }
        } }
    }
    @Test fun collectionsAreSharedOnlyAfterRecipientAccepts() {
        val db = database()
        val alice = db.user(account(db,"alice")); val bob = db.user(account(db,"bob"))
        db.record(alice,25,"camera"); db.record(bob,4,"manual")
        assertEquals(25,db.collection(alice).getJSONObject(0).getInt("id"))
        assertEquals(4,db.collection(bob).getJSONObject(0).getInt("id"))
        assertEquals(0,db.friends(alice).length())
        db.requestFriend(alice,"bob"); db.requestFriend(alice,"bob")
        assertEquals(0,db.friends(alice).length())
        assertEquals(1,db.requests(bob).getJSONArray("incoming").length())
        assertEquals("bob",db.requests(alice).getJSONArray("outgoing").getString(0))
        failure(409) { db.requestFriend(bob,"alice") }
        db.respondToRequest(bob,"alice",true)
        assertEquals(0,db.requests(bob).getJSONArray("incoming").length())
        assertEquals(1,db.friends(alice).length())
        assertEquals(4,db.friends(alice).getJSONObject(0).getJSONArray("seen").getJSONObject(0).getInt("id"))
        assertEquals(1,db.friends(bob).length())
        failure(409) { db.requestFriend(alice,"bob") }
        db.record(bob,7,"camera")
        assertEquals(2,database().friends(alice).getJSONObject(0).getJSONArray("seen").length())
    }
    @Test fun pendingRequestsPersistAndOnlyRecipientCanRespond() {
        val db = database()
        val alice = db.user(account(db,"alice")); val bob = db.user(account(db,"bob")); val eve = db.user(account(db,"eve"))
        db.requestFriend(alice,"bob"); db.requestFriend(eve,"bob")
        val restarted = database()
        assertEquals(2,restarted.requests(bob).getJSONArray("incoming").length())
        failure(404) { restarted.respondToRequest(eve,"alice",true) }
        failure(404) { restarted.respondToRequest(alice,"bob",true) }
        restarted.respondToRequest(bob,"alice",false)
        assertEquals(1,restarted.requests(bob).getJSONArray("incoming").length())
        assertEquals(0,restarted.requests(alice).getJSONArray("outgoing").length())
        assertEquals(0,restarted.friends(bob).length())
        restarted.respondToRequest(bob,"eve",false)
        assertEquals(0,restarted.requests(bob).getJSONArray("incoming").length())
        failure(404) { restarted.respondToRequest(bob,"eve",true) }
    }
    @Test fun upgradeKeepsLegacyFriendsAndCollections() {
        val db = database()
        val token = account(db,"alice"); val alice = db.user(token); val bob = db.user(account(db,"bob"))
        db.record(alice,25,"camera")
        // Reproduce el esquema previo a las solicitudes en una base temporal.
        DriverManager.getConnection("jdbc:sqlite:${temp.root}/pokedex.db").use { c -> c.createStatement().use { s ->
            s.execute("DROP TABLE friend_requests")
            s.execute("INSERT INTO friends VALUES($alice,$bob)")
        } }
        val upgraded = database()
        assertEquals(alice,upgraded.user(token))
        assertEquals(25,upgraded.collection(alice).getJSONObject(0).getInt("id"))
        assertEquals("bob",upgraded.friends(alice).getJSONObject(0).getString("username"))
        assertEquals(0,upgraded.requests(bob).getJSONArray("incoming").length())
        upgraded.requestFriend(bob,"alice")
        upgraded.respondToRequest(alice,"bob",true)
        assertEquals(1,upgraded.friends(bob).length())
    }
    @Test fun repeatedRecognitionDoesNotDuplicateOrDowngrade() {
        val db = database(); val user = db.user(account(db,"alice"))
        db.record(user,25,"manual"); db.record(user,25,"camera"); db.record(user,25,"manual")
        assertEquals(1,db.collection(user).length())
        assertEquals("camera",db.collection(user).getJSONObject(0).getString("source"))
        failure(400) { db.record(user,999,"camera") }
    }
    @Test fun logoutRevokesOnlyThatSession() {
        val db = database(); val first = account(db,"alice")
        val second = db.authenticate("alice","Demo-pass-123",false).getString("session")
        db.logout(first)
        failure(401) { db.user(first) }
        assertTrue(db.user(second)>0)
        failure(401) { db.user("forged") }
    }
    @Test fun invalidNamesAndFriendsAreRejected() {
        val db = database(); val user = db.user(account(db,"alice"))
        failure(400) { account(db,"a'; DROP TABLE users;--") }
        failure(400) { db.requestFriend(user,"alice") }
        failure(404) { db.requestFriend(user,"missing") }
    }
}
