package shop.whitedns.client.scan

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Splitting scan results per engine must not cost users the resolvers they had
 * already scanned before the split.
 */
class ScannerResultSeedTest {

    private lateinit var root: File
    private lateinit var legacy: File
    private lateinit var target: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scanseed").toFile()
        legacy = File(File(root, "stormdns"), WhiteDnsScannerResultStore.ResultFileName)
        target = File(File(root, "cottendns"), WhiteDnsScannerResultStore.ResultFileName)
        legacy.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun inheritsTheSharedHistoryOnFirstUse() {
        legacy.writeText("1.1.1.1\n9.9.9.9")

        WhiteDnsScannerResultStore.seedResultFileIfMissing(legacy, target)

        assertTrue("CottenDNS should inherit the pre-split resolvers", target.isFile)
        assertEquals("1.1.1.1\n9.9.9.9", target.readText())
    }

    /** Copied, not moved — StormDNS must keep its own list. */
    @Test
    fun leavesTheLegacyFileInPlace() {
        legacy.writeText("1.1.1.1")

        WhiteDnsScannerResultStore.seedResultFileIfMissing(legacy, target)

        assertTrue(legacy.isFile)
        assertEquals("1.1.1.1", legacy.readText())
    }

    /** Once an engine has its own results the seed must never run again. */
    @Test
    fun neverOverwritesResultsTheEngineAlreadyHas() {
        legacy.writeText("1.1.1.1")
        target.parentFile?.mkdirs()
        target.writeText("8.8.8.8")

        WhiteDnsScannerResultStore.seedResultFileIfMissing(legacy, target)

        assertEquals("8.8.8.8", target.readText())
    }

    /** The two diverge after seeding: a later StormDNS scan must not follow. */
    @Test
    fun divergesAfterTheFirstSeed() {
        legacy.writeText("1.1.1.1")
        WhiteDnsScannerResultStore.seedResultFileIfMissing(legacy, target)

        legacy.writeText("1.1.1.1\n8.8.8.8")
        WhiteDnsScannerResultStore.seedResultFileIfMissing(legacy, target)

        assertEquals("1.1.1.1", target.readText())
    }

    @Test
    fun doesNothingWhenThereIsNoHistoryToInherit() {
        WhiteDnsScannerResultStore.seedResultFileIfMissing(legacy, target)

        assertFalse(target.exists())
    }
}
