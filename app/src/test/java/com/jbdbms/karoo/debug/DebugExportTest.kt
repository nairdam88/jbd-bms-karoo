package com.jbdbms.karoo.debug

import org.junit.Assert.assertTrue
import org.junit.Test

class DebugExportTest {

    private val report = DebugReport(
        generatedAt = "2026-10-19 22:00:00.000",
        appVersion = "0.2.6",
        appCode = 8,
        androidRelease = "12",
        sdk = 31,
        manufacturer = "Hammerhead",
        model = "Karoo",
        readiness = "READY",
        adapter = "ON enabled=true le=true",
        settings = "autoReconnect=true",
        link = "requested=AA:BB connected=AA:CC type=2 bond=none",
        scans = listOf("BMS AA:BB rssi=-70"),
        log = listOf("22:00:01.000 [#1] Connecting"),
        raw = listOf("TX DD A5 03 00 FF FD 77"),
    )

    @Test
    fun `report includes device, link and log sections`() {
        val text = DebugExport.format(report)

        assertTrue(text.contains("=== JBD BMS Karoo debug ==="))
        assertTrue(text.contains("App: 0.2.6 (8)"))
        assertTrue(text.contains("Device: Hammerhead Karoo"))
        assertTrue(text.contains("--- GATT target ---"))
        assertTrue(text.contains("requested=AA:BB"))
        assertTrue(text.contains("--- Scan results (1) ---"))
        assertTrue(text.contains("--- Connection log (1) ---"))
        assertTrue(text.contains("[#1] Connecting"))
        assertTrue(text.contains("--- Raw frames (last 1) ---"))
    }

    @Test
    fun `empty scan and log lists still render headings`() {
        val text = DebugExport.format(report.copy(scans = emptyList(), log = emptyList(), raw = emptyList()))

        assertTrue(text.contains("--- Scan results (0) ---"))
        assertTrue(text.contains("--- Connection log (0) ---"))
        assertTrue(!text.contains("--- Raw frames"))
    }
}
