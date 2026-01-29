package com.beansys.breathing.shared

data class Phase(
    val name: String,
    val durationSeconds: Double,
    val colorHex: Long
)

data class Preset(
    val name: String,
    val inhale: Double,
    val hold: Double,
    val exhale: Double,
    val rest: Double
)

object BreathingDefaults {
    const val inhaleSeconds = 4.0
    const val holdSeconds = 2.0
    const val exhaleSeconds = 6.0
    const val restSeconds = 0.0
    const val countdownMinutes = 5.0

    const val actionTintHex: Long = 0xFF47ADBD

    val presets: List<Preset> = listOf(
        Preset(name = "5.5 - 0 - 5.5 - 0", inhale = 5.5, hold = 0.0, exhale = 5.5, rest = 0.0),
        Preset(name = "4 - 0 - 6 - 0", inhale = 4.0, hold = 0.0, exhale = 6.0, rest = 0.0)
    )

    fun phases(
        inhale: Double,
        hold: Double,
        exhale: Double,
        rest: Double
    ): List<Phase> = listOf(
        Phase(name = "Inhale", durationSeconds = inhale, colorHex = 0xFF599EF2),
        Phase(name = "Hold", durationSeconds = hold, colorHex = 0xFF7373E6),
        Phase(name = "Exhale", durationSeconds = exhale, colorHex = 0xFF338C73),
        Phase(name = "Rest", durationSeconds = rest, colorHex = 0xFF596699)
    )

    fun cycleDuration(phases: List<Phase>): Double =
        phases.sumOf { if (it.durationSeconds > 0) it.durationSeconds else 0.0 }

    fun breathsPerMinute(phases: List<Phase>): Double {
        val duration = cycleDuration(phases)
        return if (duration > 0) 60.0 / duration else 0.0
    }

    fun nextPhaseIndex(current: Int, phases: List<Phase>): Int {
        var next = (current + 1) % phases.size
        var safety = 0
        while (phases[next].durationSeconds <= 0.01 && safety < phases.size) {
            next = (next + 1) % phases.size
            safety += 1
        }
        return next
    }
}
