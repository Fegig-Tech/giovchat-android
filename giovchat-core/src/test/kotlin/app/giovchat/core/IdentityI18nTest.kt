package app.giovchat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun config(botEnabled: Boolean = true, botName: String? = "Atlas") = GiovWidgetConfig(
    title = "Acme support",
    botEnabled = botEnabled,
    botName = botName,
    botAvatarUrl = "https://cdn.test/atlas.png",
    avatarUrl = "https://cdn.test/team.png",
)

private fun message(
    role: GiovRole = GiovRole.AGENT,
    isBot: Boolean = false,
    isAutomated: Boolean = false,
    senderName: String? = null,
) = GiovMessage(id = "m", role = role, content = "x", createdAt = 1, isBot = isBot, isAutomated = isAutomated, senderName = senderName)

class IdentityTest {
    @Test
    fun theHeaderIsThePersonOnceOneTakesOverElseTheBotWhileItAnswersElseTheTeam() {
        assertEquals(
            GiovHeaderIdentity(GiovHeaderKind.PERSON, "Ada", "https://cdn.test/ada.png"),
            headerIdentity(config(), "Ada", "https://cdn.test/ada.png"),
        )
        assertEquals(GiovHeaderIdentity(GiovHeaderKind.BOT, "Atlas", "https://cdn.test/atlas.png"), headerIdentity(config()))
        assertEquals(
            GiovHeaderIdentity(GiovHeaderKind.TEAM, "Acme support", "https://cdn.test/team.png"),
            headerIdentity(config(botEnabled = false)),
        )
        assertEquals(GiovHeaderKind.TEAM, headerIdentity(config(botName = null)).kind)
        assertNull("only http(s) images load", headerIdentity(config(), "Ada", "javascript:alert(1)").avatar)
        assertNull(headerIdentity(config(), "Ada").avatar)
        assertEquals("an empty name is no person", GiovHeaderKind.BOT, headerIdentity(config(), "").kind)
    }

    @Test
    fun statusLinesComeFromTheStrings() {
        val strings = GIOV_STRINGS.getValue("en")
        assertEquals("Live agent", headerIdentity(config(), "Ada").status(strings))
        assertEquals("AI assistant · Online", headerIdentity(config()).status(strings))
        assertEquals("We'll be with you shortly", headerIdentity(config(botEnabled = false)).status(strings))
    }

    @Test
    fun senderLabelsAiIdentityWinsOverAutomatedPeopleByNameVisitorsAsYou() {
        val strings = GIOV_STRINGS.getValue("en")
        assertEquals("Atlas", messageSenderLabel(message(isBot = true, isAutomated = true), strings, botName = "Atlas"))
        assertEquals("GiovBot", messageSenderLabel(message(isBot = true), strings, botName = " "))
        assertEquals("Automated", messageSenderLabel(message(isAutomated = true, senderName = "x"), strings))
        assertEquals("Ada", messageSenderLabel(message(senderName = " Ada "), strings))
        assertEquals("Bola", messageSenderLabel(message(), strings, agentName = "Bola"))
        assertEquals("Agent", messageSenderLabel(message(), strings))
        assertEquals("You", messageSenderLabel(message(role = GiovRole.VISITOR, isBot = true), strings))
    }

    @Test
    fun onlyExplicitServerAiMetadataBrandsTypingAMismatchedEndKeepsTheOtherIndicator() {
        var state = GiovTypingState()
        state = reduceAgentTyping(state, json("type" to "typing", "role" to "agent", "isBot" to true))
        assertEquals(GiovTypingState(visible = true, isBot = true), state)
        assertEquals(state, reduceAgentTyping(state, json("type" to "typing_end", "role" to "agent", "isBot" to false)))
        assertEquals(GiovTypingState(), reduceAgentTyping(state, json("type" to "typing_end", "role" to "agent")))
        assertEquals(state, reduceAgentTyping(state, json("type" to "typing", "role" to "visitor")))
        assertEquals(
            GiovTypingState(visible = true),
            reduceAgentTyping(GiovTypingState(), json("type" to "typing", "role" to "agent", "isBot" to "true")),
        )
    }
}

class I18nTest {
    @Test
    fun everyShippedLanguageBuildsEveryStringAndNoneIsEmpty() {
        assertEquals(GIOV_LOCALES.toSet(), GIOV_STRINGS.keys)
        for ((code, s) in GIOV_STRINGS) {
            val plain = listOf(
                s.loading, s.tryAgain, s.retry, s.closeChat, s.connecting, s.reconnecting, s.statusPerson, s.statusBot, s.statusTeam,
                s.composerPlaceholder, s.messageInput, s.sendMessage, s.attachFile, s.you, s.agent, s.automated, s.teamTyping,
                s.sending, s.notSent, s.tapToRetry, s.discard, s.uploadFailed, s.sessionExpired, s.conversationEnded, s.unavailable,
                s.sendFailed, s.busy, s.attachmentsOff, s.attachment, s.image, s.preChatTitle, s.firstName, s.lastName, s.email,
                s.phone, s.startChat, s.removeAttachment("f.png"), s.botTyping("Atlas"), s.uploading(42),
                s.typeNotAllowed("a.pdf"), s.fileTooLarge("a.pdf", 5.0), s.tooManyFiles(5), s.openAttachment("a.pdf"), s.required("Email"),
            )
            for (text in plain) assertTrue(code, text.isNotBlank())
            assertTrue(code, "Atlas" in s.botTyping("Atlas"))
            assertTrue(code, "42" in s.uploading(42))
            assertTrue(code, "a.pdf" in s.fileTooLarge("a.pdf", 5.0) && "5" in s.fileTooLarge("a.pdf", 5.0))
            assertTrue("2.5" in s.fileTooLarge("a.pdf", 2.5))
            assertFalse("a whole number has no decimals", "5.0" in s.fileTooLarge("a.pdf", 5.0))
            assertTrue(code, "5" in s.tooManyFiles(5))
        }
        assertEquals("a.pdf : ce type de fichier n'est pas autorisé.", GIOV_STRINGS.getValue("fr").typeNotAllowed("a.pdf"))
        assertEquals("Wird hochgeladen: 42 %", GIOV_STRINGS.getValue("de").uploading(42))
        assertEquals("AI assistant · Online", GIOV_STRINGS.getValue("en").statusBot)
        assertEquals("Discard", GIOV_STRINGS.getValue("en").discard)
        assertEquals("Supprimer", GIOV_STRINGS.getValue("fr").discard)
        assertEquals("Descartar", GIOV_STRINGS.getValue("es").discard)
        assertEquals("Descartar", GIOV_STRINGS.getValue("pt").discard)
        assertEquals("Verwerfen", GIOV_STRINGS.getValue("de").discard)
    }

    @Test
    fun localeResolution() {
        assertEquals("fr", resolveGiovLocale("fr-CA"))
        assertEquals("pt", resolveGiovLocale("pt_BR"))
        assertEquals("de", resolveGiovLocale("DE"))
        assertEquals("en", resolveGiovLocale("ja-JP"))
        assertEquals("en", resolveGiovLocale(null))
        assertEquals("Tú", resolveGiovStrings("es").you)
        assertTrue("device locale falls back to a shipped language", resolveGiovStrings().loading.isNotEmpty())
    }

    @Test
    fun hostOverridesReplaceStringsBlankOnesAreIgnored() {
        val strings = resolveGiovStrings("es") { base ->
            base.withOverrides(sendMessage = "Mandar", tryAgain = "", botTyping = { name -> "$name…" })
        }
        assertEquals("Mandar", strings.sendMessage)
        assertEquals(GIOV_STRINGS.getValue("es").tryAgain, strings.tryAgain)
        assertEquals("Atlas…", strings.botTyping("Atlas"))
        assertEquals(GIOV_STRINGS.getValue("es").you, strings.you)
        assertSame(GIOV_STRINGS.getValue("en"), resolveGiovStrings("en"))
    }

    @Test
    fun failuresMapToSentencesNeverServerText() {
        val strings = GIOV_STRINGS.getValue("en")
        assertEquals(strings.sessionExpired, strings.describeError(GiovSessionExpiredException("x")))
        assertEquals(strings.conversationEnded, strings.describeError(GiovConversationClosedException("x")))
        assertEquals(strings.busy, strings.describeError(GiovWorkspaceBusyException("x", 30)))
        assertEquals(strings.uploadFailed, strings.describeError(GiovAttachmentRejectedException("x")))
        assertEquals(strings.sendFailed, strings.describeError(GiovSendFailedException("x")))
        assertEquals(strings.unavailable, strings.describeError(GiovNetworkException("boom")))
        assertNotEquals("boom", strings.describeError(GiovServerException("boom", 500)))
    }
}

class SessionScopeTest {
    @Test
    fun scopeDiffersPerWorkspaceAndVisitorIdentity() {
        val a = giovSessionScope("https://api.test/", "t", GiovContact(userId = "u1"))
        assertEquals(a, giovSessionScope("https://api.test", "t", GiovContact(userId = "u1")))
        assertNotEquals(a, giovSessionScope("https://api.test", "t", GiovContact(userId = "u2")))
        assertNotEquals(a, giovSessionScope("https://api.test", "other", GiovContact(userId = "u1")))
        assertNotEquals(a, giovSessionScope("https://api.test", "t", GiovContact(userId = "u1"), sessionKey = "acct-2"))
    }

    @Test
    fun contactStartFieldsCarryUserIdAndHashAndSkipEmptyValues() {
        assertEquals(
            mapOf("userId" to "u1", "userHash" to "ab", "firstName" to "Ada"),
            GiovContact(userId = "u1", userHash = "ab", email = " ", firstName = " Ada ").toStartFields(),
        )
    }

    @Test
    fun onlyWellFormedStoredSessionsAreUsed() {
        assertTrue(isValidStoredSession(testSession))
        assertFalse(isValidStoredSession(GiovSession("c", "short")))
        assertFalse(isValidStoredSession(GiovSession("", "b".repeat(64))))
        assertFalse(isValidStoredSession(GiovSession("c", "B".repeat(64))))
        assertFalse(isValidStoredSession(null))
    }

    @Test
    fun theSessionTokenNeverAppearsInToString() {
        assertFalse(testSession.toString().contains("bbbb"))
    }
}
