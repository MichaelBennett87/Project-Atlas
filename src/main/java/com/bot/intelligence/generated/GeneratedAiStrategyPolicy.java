package com.bot.intelligence.generated;

/**
 * Auto-generated bounded strategy policy used by the market intelligence layer.
 *
 * This file is intentionally narrow: the autonomous optimizer may rewrite only
 * numeric constants inside this class. It must not rewrite broker, execution,
 * account, credential, or order-submission code. The live bot reads these
 * constants at runtime, while offline evolution can regenerate this file after
 * market hours and after validation.
 */
public final class GeneratedAiStrategyPolicy {
    private GeneratedAiStrategyPolicy() {}

    public static final long GENERATED_AT_MS = 0L;
    public static final String GENERATION_REASON = "baseline";

    public static final double BASE_TARGET_PROBABILITY = 0.44;
    public static final double RVOL_EXPANSION_BONUS = 0.20;
    public static final double MODERATE_RVOL_BONUS = 0.08;
    public static final double EARLY_MISSING_RVOL_PENALTY = -0.03;
    public static final double WEAK_RVOL_PENALTY = -0.10;
    public static final double BULLISH_BREAK_BONUS = 0.18;
    public static final double VWAP_STRENGTH_BONUS = 0.16;
    public static final double FAILED_BREAKDOWN_BONUS = 0.15;
    public static final double STRUCTURE_STABILITY_BONUS = 0.10;
    public static final double ONE_BAR_CONTINUATION_BONUS = 0.07;
    public static final double THREE_BAR_CONTINUATION_BONUS = 0.08;
    public static final double FALLING_KNIFE_PENALTY = -0.15;
    public static final double OVEREXTENDED_RSI_PENALTY = -0.08;
    public static final double EXCESSIVE_VOLATILITY_PENALTY = -0.12;
    public static final double FRESH_CATALYST_BONUS = 0.14;
    public static final double NEGATIVE_SENTIMENT_PENALTY = -0.12;
    public static final double NEGATIVE_NEWS_SHORT_SENTIMENT_MULTIPLIER = 0.62;
    public static final double NEGATIVE_NEWS_SHORT_RVOL_BONUS = 0.18;
    public static final double NEGATIVE_NEWS_SHORT_STRUCTURE_BONUS = 0.12;

    public static final double AVG_WIN_PERCENT = 5.0;
    public static final double AVG_LOSS_PERCENT = 2.5;

    public static final double MOMENTUM_RETURN3_MULTIPLIER = 12.0;
    public static final double MOMENTUM_RVOL_BONUS = 0.25;
    public static final double MOMENTUM_BREAK_BONUS = 0.25;
    public static final double MOMENTUM_VWAP_BONUS = 0.10;

    public static final double MEAN_REVERSION_DROP_MULTIPLIER = 4.0;
    public static final double MEAN_REVERSION_BOUNCE_MULTIPLIER = 8.0;
    public static final double MEAN_REVERSION_VWAP_BONUS = 0.22;
    public static final double MEAN_REVERSION_HIGHER_LOWS_BONUS = 0.12;

    public static final double VWAP_RECLAIM_BASE_BONUS = 0.45;
    public static final double VWAP_DISTANCE_BONUS = 0.20;
    public static final double VWAP_RVOL_BONUS = 0.15;

    public static final double SQUEEZE_RVOL_BONUS = 0.25;
    public static final double SQUEEZE_GREEN_VOLUME_BONUS = 0.20;
    public static final double SQUEEZE_BREAK_BONUS = 0.22;
    public static final double SQUEEZE_SENTIMENT_BONUS = 0.08;
}
