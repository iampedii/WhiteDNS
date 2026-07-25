package shop.whitedns.client.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CottenDnsProfileSettingsTest {

    @Test
    fun normalizeFallsBackToPresetForUnknownOverrides() {
        val settings = CottenDnsProfileSettings(
            configPreset = "nonsense",
            transportMode = "carrier-pigeon",
            deliveryMode = "smoke-signal",
            qnameMode = "loud",
        ).normalized()

        assertEquals("default", settings.configPreset)
        assertEquals(CottenDnsProfileSettings.ModePreset, settings.transportMode)
        assertEquals(CottenDnsProfileSettings.ModePreset, settings.deliveryMode)
        assertEquals(CottenDnsProfileSettings.ModePreset, settings.qnameMode)
    }

    @Test
    fun normalizeAcceptsThePresetAliasesTheEngineUses() {
        assertEquals("tcp-survival", CottenDnsProfileSettings(configPreset = "TCP").normalized().configPreset)
        assertEquals("master-storm", CottenDnsProfileSettings(configPreset = "storm").normalized().configPreset)
    }

    @Test
    fun normalizeRejectsOutOfRangePorts() {
        val settings = CottenDnsProfileSettings(
            resolverDoTPort = "0",
            resolverDoHPort = "70000",
            resolverDoHPath = "   ",
        ).normalized()

        assertEquals("853", settings.resolverDoTPort)
        assertEquals("443", settings.resolverDoHPort)
        assertEquals("/dns-query", settings.resolverDoHPath)
    }

    @Test
    fun serverTypeAliasesMapToCompatibility() {
        assertEquals(true, CottenDnsProfileSettings(serverType = "storm").normalized().isCompatibility)
        assertEquals(true, CottenDnsProfileSettings(serverType = "Compatibility").normalized().isCompatibility)
        assertEquals(false, CottenDnsProfileSettings().normalized().isCompatibility)
    }

    @Test
    fun jsonRoundTripPreservesEveryField() {
        val original = CottenDnsProfileSettings(
            serverType = CottenDnsProfileSettings.ServerTypeCompatibility,
            configPreset = "speed",
            transportMode = "dot",
            deliveryMode = "txt-https",
            qnameMode = "moderate",
            resolverTlsServerName = "dns.example.com",
            resolverTlsPin = "pin123",
            resolverDoTPort = "8853",
            resolverDoHPort = "8443",
            resolverDoHPath = "/q",
        )

        assertEquals(original, cottenDnsProfileSettingsFromJson(original.toJson()))
    }

    /** Profiles saved before this feature must still load. */
    @Test
    fun missingOrEmptyJsonYieldsDefaults() {
        assertEquals(CottenDnsProfileSettings(), cottenDnsProfileSettingsFromJson(null))
        assertEquals(CottenDnsProfileSettings(), cottenDnsProfileSettingsFromJson(JSONObject()))
    }
}
