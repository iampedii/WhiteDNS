package shop.whitedns.client.scan

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import shop.whitedns.client.model.DnsClientEngine
import shop.whitedns.client.model.ResolverTextValidation
import shop.whitedns.client.model.validateResolverText

object WhiteDnsScannerResultStore {
    const val ResultFileName = "Scanner result"
    private val ResultFileLock = Any()

    /** Engines write only to their own store; reads span all of them. */
    private val AllEngines = listOf(DnsClientEngine.StormDns, DnsClientEngine.CottenDns)

    /** The store this engine owns. Only this engine ever writes here. */
    fun resultFile(context: Context, engine: String): File {
        return File(resultDirectory(context, engine), ResultFileName)
    }

    /**
     * Every engine's scanned resolvers, this engine's own first.
     *
     * Storage stays per engine so each one's results are still its own, but a
     * resolver another engine already reached is worth trying: both tunnel over
     * the same resolvers, and validity only really diverges under opt-in DoT/DoH
     * or unusual query types. Reading only one store also starves an engine's
     * early-start threshold — StormDNS needs 8 valid resolvers before it
     * fast-connects, so a half-filled store silently costs Fast Connect.
     */
    fun readValidResolvers(context: Context, engine: String): List<String> {
        return readValidResolverSet(context, engine).toList()
    }

    fun readValidResolverSet(context: Context, engine: String): Set<String> {
        val ordered = LinkedHashSet<String>()
        ordered += readEngineStore(context, engine)
        AllEngines.filterNot { DnsClientEngine.normalize(it) == DnsClientEngine.normalize(engine) }
            .forEach { other -> ordered += readEngineStore(context, other) }
        return ordered
    }

    /** Just one engine's store, for the write path, which must stay scoped. */
    private fun readEngineStore(context: Context, engine: String): Set<String> {
        return runCatching {
            val file = resultFile(context, engine)
            if (!file.isFile) {
                emptySet()
            } else {
                AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).useLines { lines ->
                    normalizeScanResolverSet(lines)
                }
            }
        }.getOrDefault(emptySet())
    }

    /**
     * Folds newly scanned resolvers into this engine's own store, then returns
     * the full cross-engine view for display.
     */
    fun mergeValidResolvers(context: Context, engine: String, resolvers: Iterable<String>): List<String> {
        val incomingResolvers = normalizeResolverEntries(resolvers)
        if (incomingResolvers.isEmpty()) {
            return readValidResolvers(context, engine)
        }
        val ownResolvers = (readEngineStore(context, engine).toList() + incomingResolvers).distinct()
        writeValidResolvers(context, engine, ownResolvers)
        return readValidResolvers(context, engine)
    }

    fun appendValidResolvers(context: Context, engine: String, resolvers: Iterable<String>) {
        synchronized(ResultFileLock) {
            val target = resultFile(context, engine)
            target.parentFile?.mkdirs()
            var wroteResolver = false
            FileOutputStream(target, true).use { stream ->
                resolvers.forEach { rawResolver ->
                    val resolver = normalizeResolverEntry(rawResolver) ?: return@forEach
                    if (target.length() > 0L || wroteResolver) {
                        stream.write("\n".toByteArray(Charsets.UTF_8))
                    }
                    stream.write(resolver.toByteArray(Charsets.UTF_8))
                    wroteResolver = true
                }
            }
        }
    }

    fun normalizeResolverText(rawText: String): List<String> {
        return normalizeScanResolverText(rawText).normalizedResolvers
    }

    fun normalizeScanResolverText(rawText: String): ResolverTextValidation {
        val validation = validateResolverText(rawText)
        return validation.copy(
            normalizedResolvers = validation.normalizedResolvers
                .map(::stripScanResolverPort)
                .distinct(),
        )
    }

    fun normalizeScanResolverFile(file: File): ResolverTextValidation {
        return file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            normalizeScanResolverLines(lines)
        }
    }

    fun copyCandidateScanResolverFile(
        lines: Sequence<String>,
        outputFile: File,
    ): Int {
        outputFile.parentFile?.mkdirs()
        var candidateCount = 0
        outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) {
                    return@forEach
                }
                if (candidateCount > 0) {
                    writer.write("\n")
                }
                writer.write(line)
                candidateCount += 1
            }
        }
        return candidateCount
    }

    fun writePendingScanResolverFile(
        lines: Sequence<String>,
        outputFile: File,
        excludedResolvers: Set<String> = emptySet(),
    ): ScanResolverFileSummary {
        outputFile.parentFile?.mkdirs()
        val seenResolvers = mutableSetOf<String>()
        var totalResolverCount = 0
        var pendingResolverCount = 0
        var alreadyValidResolverCount = 0
        var invalidEntryCount = 0

        outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) {
                    return@forEach
                }
                val validation = normalizeScanResolverText(line)
                invalidEntryCount += validation.invalidEntries.size
                validation.normalizedResolvers.forEach { resolver ->
                    if (!seenResolvers.add(resolver)) {
                        return@forEach
                    }
                    totalResolverCount += 1
                    if (resolver in excludedResolvers) {
                        alreadyValidResolverCount += 1
                    } else {
                        if (pendingResolverCount > 0) {
                            writer.write("\n")
                        }
                        writer.write(resolver)
                        pendingResolverCount += 1
                    }
                }
            }
        }

        return ScanResolverFileSummary(
            totalResolverCount = totalResolverCount,
            pendingResolverCount = pendingResolverCount,
            alreadyValidResolverCount = alreadyValidResolverCount,
            invalidEntryCount = invalidEntryCount,
        )
    }

    fun rewritePendingScanResolverFile(
        file: File,
        excludedResolvers: Set<String> = emptySet(),
    ): ScanResolverFileSummary {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        val summary = file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            writePendingScanResolverFile(
                lines = lines,
                outputFile = tempFile,
                excludedResolvers = excludedResolvers,
            )
        }
        replaceFile(tempFile, file)
        return summary
    }

    fun normalizeScanResolverLines(lines: Sequence<String>): ResolverTextValidation {
        val normalizedResolvers = mutableListOf<String>()
        val invalidEntries = mutableListOf<String>()
        val seenResolvers = mutableSetOf<String>()
        val seenInvalidEntries = mutableSetOf<String>()

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEach
            }
            val validation = normalizeScanResolverText(line)
            validation.normalizedResolvers.forEach { resolver ->
                if (seenResolvers.add(resolver)) {
                    normalizedResolvers += resolver
                }
            }
            validation.invalidEntries.forEach { invalidEntry ->
                if (seenInvalidEntries.add(invalidEntry)) {
                    invalidEntries += invalidEntry
                }
            }
        }

        return ResolverTextValidation(
            normalizedResolvers = normalizedResolvers,
            invalidEntries = invalidEntries,
        )
    }

    private fun normalizeScanResolverSet(lines: Sequence<String>): Set<String> {
        val normalizedResolvers = linkedSetOf<String>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEach
            }
            normalizeScanResolverText(line).normalizedResolvers.forEach(normalizedResolvers::add)
        }
        return normalizedResolvers
    }

    fun normalizeResolverEntries(resolvers: Iterable<String>): List<String> {
        return normalizeResolverText(resolvers.joinToString(separator = "\n"))
    }

    fun normalizeResolverEntry(resolver: String): String? {
        return normalizeResolverEntries(listOf(resolver)).firstOrNull()
    }

    private fun writeValidResolvers(context: Context, engine: String, resolvers: List<String>) {
        synchronized(ResultFileLock) {
            val target = resultFile(context, engine)
            target.parentFile?.mkdirs()
            val atomicFile = AtomicFile(target)
            var stream: FileOutputStream? = null
            try {
                stream = atomicFile.startWrite()
                stream.write(resolvers.joinToString(separator = "\n").toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (error: IOException) {
                stream?.let(atomicFile::failWrite)
                throw error
            }
        }
    }

    private fun replaceFile(source: File, target: File) {
        if (source.renameTo(target)) {
            return
        }
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    /**
     * Scoped per engine. A resolver validated over CottenDNS (DoH, reshaped
     * QNAMEs, non-TXT records) is not necessarily reachable over StormDNS's
     * TXT-over-UDP path, so the two must never read each other's results.
     */
    private fun resultDirectory(context: Context, engine: String): File {
        return File(File(context.noBackupFilesDir, DnsClientEngine.normalize(engine)), "scan")
    }

    private fun stripScanResolverPort(resolver: String): String {
        val text = resolver.trim()
        val bracketedMatch = BracketedResolverPortRegex.matchEntire(text)
        if (bracketedMatch != null) {
            return bracketedMatch.groupValues[1]
        }
        val hostPortMatch = ResolverPortRegex.matchEntire(text)
        if (hostPortMatch != null) {
            return hostPortMatch.groupValues[1]
        }
        return text
    }

    private val BracketedResolverPortRegex = Regex("""^\[([^]]+)]:(\d{1,5})$""")
    private val ResolverPortRegex = Regex("""^([^:]+):(\d{1,5})$""")
}

data class ScanResolverFileSummary(
    val totalResolverCount: Int,
    val pendingResolverCount: Int,
    val alreadyValidResolverCount: Int,
    val invalidEntryCount: Int,
)
