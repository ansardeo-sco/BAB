package com.ansardeo.binancebot.data

/**
 * Leverage-based configuration (confirmed spec). Single source of truth.
 * Rates are selected automatically by leverage; not user-editable.
 * New Entry Distance % is used for re-entry and averaging-trigger order placement.
 */
data class LeverageProfile(
    val leverage: Int,
    val trailingActivationPercent: Double,
    val callbackRatePercent: Double,
    val newEntryDistancePercent: Double,
    val slPercent: Double,
)

object LeverageConfig {
    val SUPPORTED_LEVERAGES: List<Int> = listOf(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,25)
    val MIN_LEVERAGE = 1
    val MAX_LEVERAGE = 25

    private val profiles: Map<Int, LeverageProfile> = listOf(
        LeverageProfile(1, 26.00, 3.00, 10.00, 80.00),
        LeverageProfile(2, 13.00, 1.50, 5.00, 40.00),
        LeverageProfile(3, 8.67, 1.00, 3.33, 26.67),
        LeverageProfile(4, 6.50, 0.75, 2.50, 20.00),
        LeverageProfile(5, 5.20, 0.60, 2.00, 16.00),
        LeverageProfile(6, 4.33, 0.50, 1.67, 13.33),
        LeverageProfile(7, 3.71, 0.43, 1.43, 11.43),
        LeverageProfile(8, 3.25, 0.38, 1.25, 10.00),
        LeverageProfile(9, 2.89, 0.33, 1.11, 8.89),
        LeverageProfile(10, 2.60, 0.30, 1.00, 8.00),
        LeverageProfile(11, 2.36, 0.27, 0.91, 7.27),
        LeverageProfile(12, 2.17, 0.25, 0.83, 6.67),
        LeverageProfile(13, 2.00, 0.23, 0.77, 6.15),
        LeverageProfile(14, 1.86, 0.21, 0.71, 5.71),
        LeverageProfile(15, 1.73, 0.20, 0.67, 5.33),
        LeverageProfile(16, 1.63, 0.19, 0.63, 5.00),
        LeverageProfile(17, 1.53, 0.18, 0.59, 4.71),
        LeverageProfile(18, 1.44, 0.17, 0.56, 4.44),
        LeverageProfile(19, 1.37, 0.16, 0.53, 4.21),
        LeverageProfile(20, 1.30, 0.15, 0.50, 4.00),
        LeverageProfile(25, 1.04, 0.12, 0.40, 3.20),
    ).associateBy { it.leverage }

    fun isSupported(leverage: Int): Boolean = profiles.containsKey(leverage)

    fun requireSupported(leverage: Int): Int {
        require(isSupported(leverage)) {
            "Unsupported leverage ${leverage}x. Allowed values: ${SUPPORTED_LEVERAGES.joinToString(", ")}x."
        }
        return leverage
    }

    fun profileFor(leverage: Int): LeverageProfile? = profiles[leverage]

    fun requireProfile(leverage: Int): LeverageProfile =
        profileFor(requireSupported(leverage))
            ?: throw IllegalArgumentException("Missing leverage profile for ${leverage}x")

    /** For display/defaulting only where a fallback is needed; never use to accept invalid input. */
    fun coerceToSupportedOrDefault(leverage: Int, default: Int = 6): Int =
        if (isSupported(leverage)) leverage else default.also { requireSupported(it) }

    fun allProfiles(): List<LeverageProfile> = SUPPORTED_LEVERAGES.map { requireProfile(it) }
}
