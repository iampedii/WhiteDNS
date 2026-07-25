package shop.whitedns.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDomainsTest {

    @Test
    fun splitTrimsStripsTrailingDotsAndDropsDuplicates() {
        assertEquals(
            listOf("a.example.com", "b.example.com"),
            ServerDomains.split("  a.example.com. , b.example.com ,, A.EXAMPLE.COM "),
        )
    }

    @Test
    fun splitKeepsCaseOfTheFirstOccurrence() {
        assertEquals(listOf("A.Example.com"), ServerDomains.split("A.Example.com, a.example.com"))
    }

    @Test
    fun normalizeProducesTheCanonicalCommaSeparatedForm() {
        assertEquals("a.example.com, b.example.com", ServerDomains.normalize("a.example.com,b.example.com"))
    }

    @Test
    fun singleDomainSurvivesUnchanged() {
        assertEquals("only.example.com", ServerDomains.normalize("only.example.com"))
        assertEquals("only.example.com", ServerDomains.primary("only.example.com"))
    }

    @Test
    fun validityRejectsBlankAndSeparatorOnlyInput() {
        assertFalse(ServerDomains.isValid(""))
        assertFalse(ServerDomains.isValid("   "))
        assertFalse(ServerDomains.isValid(",,, ,"))
        assertTrue(ServerDomains.isValid("a.example.com"))
    }

    /** Reordering the same routes must not create a second "server". */
    @Test
    fun duplicateDetectionIgnoresDomainOrder() {
        val first = ConnectionProfile(
            id = "a",
            name = "A",
            customServerDomain = "a.example.com, b.example.com",
            customServerEncryptionKey = "key",
        )
        val second = first.copy(id = "b", name = "B", customServerDomain = "b.example.com, a.example.com")

        val settings = WhiteDnsSettings(connectionProfiles = listOf(first, second))

        assertEquals(1, settings.duplicateConnectionProfileCount())
        assertEquals(1, settings.deleteDuplicateConnectionProfiles().normalizedConnectionProfiles().size)
    }
}
