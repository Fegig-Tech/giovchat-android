package app.giovchat.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the session secret lives between launches: the `{conversationId, sessionToken}` pair, one per [scope].
 *
 * The session token is a bearer secret that proves this device owns the conversation. **Back it with the
 * platform's secure storage**: this JVM module has no Android dependency, so the default
 * [InMemoryGiovSessionStore] forgets the conversation when the process ends. On Android, encrypt the pair with an
 * Android Keystore key (AES-GCM) before storing it, and keep the file out of Auto Backup and device transfer; the
 * README and the sample's `EncryptedSessionStore` show one way. (`androidx.security:security-crypto` is deprecated.)
 *
 * Implementations may throw; the SDK reports a [GiovStorageException] and carries on without the stored session.
 */
public interface GiovSessionStore {
    /** The stored session for [scope], or null. */
    public suspend fun read(scope: String): GiovSession?

    public suspend fun write(scope: String, session: GiovSession)

    public suspend fun delete(scope: String)
}

/** Keeps sessions for the life of the process only (the default). */
public class InMemoryGiovSessionStore : GiovSessionStore {
    private val sessions = ConcurrentHashMap<String, GiovSession>()

    override suspend fun read(scope: String): GiovSession? = sessions[scope]

    override suspend fun write(scope: String, session: GiovSession) {
        sessions[scope] = session
    }

    override suspend fun delete(scope: String) {
        sessions.remove(scope)
    }
}

/**
 * The key a session is stored under: one per API, workspace, signed-in customer ([GiovContact.userId]) and
 * [sessionKey], so signing in as someone else never resumes the previous person's conversation. Contact details
 * (email, phone, names) are not part of it: a visitor who corrects their name or fills in the pre-chat form keeps
 * their conversation.
 */
public fun giovSessionScope(baseUrl: String, tenantKey: String, contact: GiovContact? = null, sessionKey: String? = null): String =
    JsonArray(
        listOf(baseUrl.trimEnd('/'), tenantKey, sessionKey, contact?.userId).map { if (it == null) JsonNull else JsonPrimitive(it) },
    ).toString()

private val SESSION_TOKEN = Regex("^[a-f0-9]{64}$")

/** A stored pair is used only if it looks like one. */
internal fun isValidStoredSession(session: GiovSession?): Boolean =
    session != null && session.conversationId.isNotEmpty() && SESSION_TOKEN.matches(session.sessionToken)
