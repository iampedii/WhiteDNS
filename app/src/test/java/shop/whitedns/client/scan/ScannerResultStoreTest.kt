package shop.whitedns.client.scan

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Each engine keeps its own scanned-resolver store, but neither may be starved
 * by that split: StormDNS needs 8 valid resolvers before it fast-connects, so an
 * engine that could only see its own half-filled store would silently lose Fast
 * Connect. Writes stay scoped; reads span both.
 */
class ScannerResultStoreTest {

    private lateinit var root: File
    private lateinit var storm: File
    private lateinit var cotten: File

    private fun writeStore(target: File, vararg resolvers: String) {
        target.parentFile?.mkdirs()
        target.writeText(resolvers.joinToString("\n"))
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scanstore").toFile()
        storm = File(File(root, "stormdns"), WhiteDnsScannerResultStore.ResultFileName)
        cotten = File(File(root, "cottendns"), WhiteDnsScannerResultStore.ResultFileName)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** The union is what each engine sees, whichever one did the scanning. */
    @Test
    fun eachEngineSeesBothStores() {
        writeStore(storm, "1.1.1.1", "9.9.9.9")
        writeStore(cotten, "8.8.8.8")

        val stormView = readUnion(own = storm, other = cotten)
        val cottenView = readUnion(own = cotten, other = storm)

        assertEquals(setOf("1.1.1.1", "9.9.9.9", "8.8.8.8"), stormView)
        assertEquals(setOf("1.1.1.1", "9.9.9.9", "8.8.8.8"), cottenView)
    }

    /** An engine that has never scanned still gets a usable list. */
    @Test
    fun anEmptyStoreStillSeesTheOtherEngine() {
        writeStore(storm, "1.1.1.1", "9.9.9.9")

        assertEquals(setOf("1.1.1.1", "9.9.9.9"), readUnion(own = cotten, other = storm))
    }

    /** Enough resolvers to clear StormDNS's 8-resolver early-start target. */
    @Test
    fun aSplitListStillClearsTheFastConnectThreshold() {
        writeStore(storm, "1.0.0.1", "1.1.1.1", "8.8.4.4", "8.8.8.8")
        writeStore(cotten, "9.9.9.9", "149.112.112.112", "208.67.222.222", "208.67.220.220")

        val stormView = readUnion(own = storm, other = cotten)

        assertTrue(
            "StormDNS needs 8 valid resolvers to fast-connect, saw ${stormView.size}",
            stormView.size >= 8,
        )
    }

    /** Own entries come first, so an engine still prefers what it proved itself. */
    @Test
    fun theEnginesOwnResultsComeFirst() {
        writeStore(storm, "1.1.1.1")
        writeStore(cotten, "8.8.8.8")

        assertEquals(listOf("8.8.8.8", "1.1.1.1"), readUnion(own = cotten, other = storm).toList())
    }

    @Test
    fun duplicatesAcrossStoresAppearOnce() {
        writeStore(storm, "1.1.1.1", "9.9.9.9")
        writeStore(cotten, "1.1.1.1", "8.8.8.8")

        assertEquals(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9"), readUnion(own = cotten, other = storm).toList())
    }

    /**
     * Mirrors readValidResolverSet's own-then-others ordering. The production
     * path resolves the same files from a Context, which a JVM test has no way
     * to supply.
     */
    private fun readUnion(own: File, other: File): Set<String> {
        val ordered = LinkedHashSet<String>()
        listOf(own, other).forEach { file ->
            if (file.isFile) {
                ordered += WhiteDnsScannerResultStore.normalizeResolverEntries(file.readLines())
            }
        }
        return ordered
    }
}
