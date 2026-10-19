package com.jbdbms.karoo.protocol

/**
 * Decoded snapshot of a JBD / XiaoXiang / Overkill Solar BMS.
 *
 * All values are expressed in their natural SI-ish units (V, A, W, Ah, °C)
 * regardless of the on-wire scaling.
 */
data class BmsData(
    val connected: Boolean = false,
    val packVoltageV: Double? = null,
    /** Signed: negative = discharging, positive = charging. */
    val currentA: Double? = null,
    val powerW: Double? = null,
    val remainingCapacityAh: Double? = null,
    val fullCapacityAh: Double? = null,
    val designCapacityAh: Double? = null,
    val balanceCapacityAh: Double? = null,
    val cycleCount: Int? = null,
    val socPercent: Int? = null,
    /** Per-cell voltages in volts, index 0 = cell 1. */
    val cells: List<Double> = emptyList(),
    val temperaturesC: List<Double> = emptyList(),
    val chargeFetOn: Boolean? = null,
    val dischargeFetOn: Boolean? = null,
    /** 1-based cell numbers currently balancing. */
    val balancingCells: List<Int> = emptyList(),
    val errorFlags: List<String> = emptyList(),
    val swVersion: String? = null,
    val manufactureDate: String? = null,
    val deviceName: String? = null,
    val lastUpdateEpochMs: Long = 0L,
) {
    val cellMinV: Double? get() = cells.minOrNull()
    val cellMaxV: Double? get() = cells.maxOrNull()
    val cellDeltaV: Double? get() = if (cells.isEmpty()) null else cellMaxV!! - cellMinV!!
    val cellAvgV: Double? get() = if (cells.isEmpty()) null else cells.average()
    val cellMinIndex: Int get() = if (cells.isEmpty()) -1 else cells.indexOf(cells.min())
    val cellMaxIndex: Int get() = if (cells.isEmpty()) -1 else cells.indexOf(cells.max())
}
