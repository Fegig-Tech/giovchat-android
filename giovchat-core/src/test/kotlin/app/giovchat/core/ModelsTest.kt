package app.giovchat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun messagesParseFromSocketFramesAndDeprecatedHistoryRows() {
        val socket = GiovMessage.fromJson(json("id" to "m1", "role" to "visitor", "content" to "Hi", "createdAt" to 5))!!
        assertEquals(GiovRole.VISITOR, socket.role)
        val row = GiovMessage.fromJson(
            json("id" to "h2", "role" to "agent", "content" to "Hello", "created_at" to 7, "is_bot" to 1, "is_automated" to 0, "attachment_id" to "[\"https://x.test/a.png\"]"),
        )!!
        assertTrue(row.isBot)
        assertFalse(row.isAutomated)
        assertEquals(7L, row.createdAt)
        assertEquals("https://x.test/a.png", row.attachments.single().url)
        assertEquals(GiovRole.VISITOR, GiovMessage.fromJson(json("id" to "u", "role" to "user", "content" to "x", "createdAt" to 1))!!.role)
        assertNull(GiovMessage.fromJson(json("id" to "x", "role" to "system", "content" to "x", "createdAt" to 1)))
        assertNull(GiovMessage.fromJson(json("id" to "x", "role" to "agent", "createdAt" to 1)))
        assertNull(GiovMessage.fromJson(json("id" to "x", "role" to "agent", "content" to "x", "createdAt" to "now")))
    }

    @Test
    fun aPersonsAvatarMustBeHttp() {
        val message = GiovMessage.fromJson(json("id" to "m", "role" to "agent", "content" to "x", "createdAt" to 1, "senderName" to "Ada", "senderAvatar" to "javascript:alert(1)"))!!
        assertNull(message.senderAvatar)
        assertTrue(message.isFromPerson)
        val bot = GiovMessage.fromJson(json("id" to "m", "role" to "agent", "content" to "x", "createdAt" to 1, "isBot" to true, "senderName" to "Bot", "senderAvatar" to "https://x.test/a.png"))!!
        assertNull("only a person carries a sender photo", bot.senderAvatar)
        assertFalse(bot.isFromPerson)
    }

    @Test
    fun previewsKeepOnlySafeParts() {
        val good = GiovAttachmentPreview.sanitize(json("thumb" to "data:image/webp;base64,AAAA", "width" to 40, "height" to 30))!!
        assertEquals(40, good.width)
        assertEquals("AAAA", good.thumbBase64)
        assertNull("a script thumb is dropped", GiovAttachmentPreview.sanitize(json("thumb" to "data:text/html;base64,AAAA"))?.thumb)
        assertNull(GiovAttachmentPreview.sanitize(json("thumb" to "data:image/png;base64,AAAA")))
        assertNull("a lone dimension is dropped", GiovAttachmentPreview.sanitize(json("width" to 40))?.width)
        assertNull(GiovAttachmentPreview.sanitize(json("width" to 99999, "height" to 4)))
        assertNull(GiovAttachmentPreview.sanitize(json("thumb" to "data:image/jpeg;base64," + "A".repeat(6000))))
    }

    @Test
    fun attachmentsParseFromTheJsonEncodedUrlListAlignedWithPreviews() {
        val parsed = parseAttachments(
            jsonValue("[\"https://x.test/a.png\",\"https://x.test/b.pdf\"]"),
            jsonValue(listOf(null, mapOf("width" to 4, "height" to 3))),
        )
        assertEquals(2, parsed.size)
        assertNull(parsed[0].preview)
        assertEquals(4, parsed[1].preview!!.width)
        assertTrue(parseAttachments(jsonValue("not json")).isEmpty())
        assertTrue(parseAttachments(jsonValue("{\"a\":1}")).isEmpty())
        assertTrue(parseAttachments(null).isEmpty())
    }

    @Test
    fun attachmentNamesAndImageDetection() {
        val key = GiovAttachment("https://api.test/conversations/c/attachments/attachments%2Ft%2Fc%2Fphoto%20one.png")
        assertEquals("photo one.png", key.displayName)
        assertTrue(key.isImage)
        assertFalse(GiovAttachment("https://x.test/a/report.pdf").isImage)
        assertTrue(GiovAttachment("https://x.test/a/blob", preview = GiovAttachmentPreview(width = 1, height = 1)).isImage)
        assertTrue(GiovAttachment("https://x.test/a/blob.bin", contentType = "image/png").isImage)
        assertEquals("mine.pdf", GiovAttachment("https://x.test/a/blob", filename = "mine.pdf").displayName)
    }

    @Test
    fun attachmentPolicyExpandsWildcardsAgainstTheServerTypesOnly() {
        val policy = GiovAttachmentPolicy.fromJson(json("enabled" to true, "allowedTypes" to listOf("image/*", "application/pdf"), "maxSizeMb" to 2))!!
        assertTrue("image/png" in policy.allowedTypes && "application/pdf" in policy.allowedTypes)
        assertFalse("text/plain" in policy.allowedTypes)
        assertEquals(2 * 1024 * 1024L, policy.maxBytes)
        val star = GiovAttachmentPolicy.fromJson(json("allowedTypes" to listOf("*")))!!
        assertEquals(SUPPORTED_ATTACHMENT_TYPES.size, star.allowedTypes.size)
        assertFalse("a wildcard cannot enable anything the server refuses", "application/x-msdownload" in star.allowedTypes)
        assertEquals(10.0, GiovAttachmentPolicy.fromJson(json("maxSizeMb" to 500))!!.maxSizeMb, 0.0)
        assertFalse(GiovAttachmentPolicy.fromJson(json("enabled" to false))!!.enabled)
        assertNull(GiovAttachmentPolicy.fromJson(null))
        assertEquals(setOf("image/jpeg", "image/png", "image/gif", "image/webp"), GiovAttachmentPolicy.DEFAULT.allowedTypes.toSet())
    }

    @Test
    fun validateAttachmentsAppliesTypeSizeCountAndTheDisabledSwitch() {
        val policy = GiovAttachmentPolicy(true, listOf("image/png", "application/pdf"), 1.0)
        fun file(name: String, type: String, size: Long) = GiovPickedFile(name, type, size) { error("not read") }
        val ok = validateAttachments(listOf(file("a.png", "IMAGE/PNG", 10)), policy)
        assertEquals(1, ok.accepted.size)
        val bad = validateAttachments(listOf(file("x.exe", "application/x-msdownload", 1), file("big.pdf", "application/pdf", 2L * 1024 * 1024)), policy)
        assertTrue(bad.accepted.isEmpty())
        assertEquals(AttachmentProblem.TypeNotAllowed("x.exe"), bad.problems[0])
        assertEquals(AttachmentProblem.TooLarge("big.pdf", 1.0), bad.problems[1])
        val many = validateAttachments((1..7).map { file("$it.png", "image/png", 1) }, policy, existingCount = 1)
        assertEquals(4, many.accepted.size)
        assertEquals(listOf<AttachmentProblem>(AttachmentProblem.TooMany(5)), many.problems)
        assertEquals(listOf<AttachmentProblem>(AttachmentProblem.Disabled), validateAttachments(listOf(file("a.png", "image/png", 1)), GiovAttachmentPolicy(false, emptyList(), 1.0)).problems)
    }

    @Test
    fun downloadsCarryExactlyOneCredential() {
        assertEquals("https://x.test/a?at=ACCESS", attachmentDisplayUrl("https://x.test/a", accessToken = "ACCESS", visitorToken = "V"))
        assertEquals("https://x.test/a?k=1&token=V%2B", attachmentDisplayUrl("https://x.test/a?k=1", visitorToken = "V+"))
        assertEquals("https://x.test/a", attachmentDisplayUrl("https://x.test/a"))
    }

    @Test
    fun accessTokensAreValidatedAndTheOneThatExpiresLastIsKept() {
        assertNull(AttachmentAccess.fromJson(json("token" to "short", "expiresAt" to 1)))
        val early = AttachmentAccess.fromJson(json("token" to "access-token-abcdefgh", "expiresAt" to 1_000))!!
        val late = AttachmentAccess.fromJson(json("token" to "access-token-zzzzzzzz", "expiresAt" to 2_000))!!
        assertSame(late, AttachmentAccess.newer(early, late))
        assertSame(late, AttachmentAccess.newer(late, early))
        assertSame(early, AttachmentAccess.newer(null, early))
        assertSame(early, AttachmentAccess.newer(early, null))
        val token = AttachmentAccess("access-token-abcdefgh", 10 * 60_000L)
        assertTrue(token.usableAt(4 * 60_000L))
        assertFalse("stops 5 minutes early", token.usableAt(5 * 60_000L + 1))
    }

    @Test
    fun visitorTokenExpiryIsInSecondsAndRenewedWithinAMinute() {
        val token = VisitorToken.fromJson(json("token" to "jwt", "expiresAt" to 1_000), EPOCH)!!
        assertEquals(1_000_000L, token.expiresAt)
        assertTrue(token.usableAt(1_000_000L - 61_000))
        assertFalse(token.usableAt(1_000_000L - 60_000))
        assertEquals(EPOCH + 14 * 60_000L, VisitorToken.fromJson(json("token" to "jwt"), EPOCH)!!.expiresAt)
        assertNull(VisitorToken.fromJson(json("nope" to 1), EPOCH))
    }

    @Test
    fun widgetConfigIsParsedDefensively() {
        val config = GiovWidgetConfig.fromJson(
            json(
                "title" to " Acme ", "theme" to "dark", "botEnabled" to true, "botName" to "Atlas",
                "botAvatarUrl" to "javascript:x", "avatarUrl" to "https://x.test/a.png", "showBranding" to false,
                "requestUserInfo" to mapOf("email" to true), "launcherStyle" to "weird", "unknownField" to 1,
                "attachments" to mapOf("enabled" to false),
            ),
        )
        assertEquals("Acme", config.title)
        assertEquals(GiovThemeMode.DARK, config.theme)
        assertNull(config.botAvatarUrl)
        assertEquals("https://x.test/a.png", config.avatarUrl)
        assertFalse(config.showBranding)
        assertTrue(config.requestUserInfo.email && !config.requestUserInfo.phone)
        assertEquals("bubble", config.launcherStyle)
        assertFalse(config.attachmentPolicy.enabled)
        assertTrue(GiovWidgetConfig.fromJson(json("title" to "x")).showBranding)
        assertEquals(DEFAULT_PRIMARY_COLOR, GiovWidgetConfig.fromJson(json("title" to "x")).primaryColor)
    }

    @Test
    fun mergeKeepsTheKeyAMessageWasShownUnderAndSortsByTime() {
        val acked = GiovMessage("srv-1", GiovRole.VISITOR, "hi", 10, clientMessageId = "c1")
        val fromHistory = GiovMessage("srv-1", GiovRole.VISITOR, "hi", 10)
        val reply = GiovMessage("srv-2", GiovRole.AGENT, "yo", 5)
        val merged = mergeMessages(listOf(acked), listOf(fromHistory, reply))
        assertEquals(listOf("srv-2", "srv-1"), merged.map { it.id })
        assertEquals("c1", merged.last().key)
    }

    @Test
    fun missingContactFieldsHonoursTheFormAndRejectsMalformedEmail() {
        val ask = GiovRequestUserInfo(email = true, firstName = true)
        assertEquals(ask, missingContactFields(ask, GiovContact()))
        assertEquals(GiovRequestUserInfo(email = true), missingContactFields(ask, GiovContact(email = "not-an-email", firstName = "Ada")))
        assertFalse(missingContactFields(ask, GiovContact(email = "ada@acme.co", firstName = "Ada")).any)
    }
}
