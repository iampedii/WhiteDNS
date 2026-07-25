package shop.whitedns.client.storm

import android.content.Context
import java.io.File
import shop.whitedns.client.model.DnsClientEngine

class StormDnsBinaryInstaller(
    private val context: Context,
) {

    fun installExecutable(engine: String = DnsClientEngine.StormDns): File {
        val normalizedEngine = DnsClientEngine.normalize(engine)
        val executable = File(
            context.applicationInfo.nativeLibraryDir,
            if (normalizedEngine == DnsClientEngine.CottenDns) CottenDnsNativeLibraryName else StormDnsNativeLibraryName,
        )
        val engineName = DnsClientEngine.displayName(normalizedEngine)
        if (!executable.exists()) {
            throw IllegalStateException(
                "$engineName native executable not found: ${executable.absolutePath}",
            )
        }
        if (!executable.canExecute()) {
            throw IllegalStateException(
                "$engineName native executable is not executable: ${executable.absolutePath}",
            )
        }
        return executable
    }

    companion object {
        private const val StormDnsNativeLibraryName = "libstormdns_client.so"
        private const val CottenDnsNativeLibraryName = "libcottendns_client.so"
    }
}
