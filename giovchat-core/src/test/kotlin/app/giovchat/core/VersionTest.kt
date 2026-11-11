package app.giovchat.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    @Test
    fun clientVersionMatchesTheGradleVersion() {
        val gradleVersion = System.getProperty("giovchat.gradleVersion") ?: error("giovchat.gradleVersion is not set")
        assertEquals("GiovChatVersion.NAME must equal VERSION_NAME in gradle.properties", gradleVersion, GiovChatVersion.NAME)
    }
}
