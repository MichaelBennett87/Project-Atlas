package com.bot.intelligence;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class AdaptiveTradingPolicy {

    public final double minProbabilityTarget;
    public final double minExpectedValuePercent;
    public final double minProposalScore;
    public final double maxStopProbability;
    public final double riskFractionPerTrade;
    public final boolean liveTradingAllowed;
    public final long updatedAtMs;
    private final Map<String, Double> strategyMultipliers;

    public AdaptiveTradingPolicy(
            double minProbabilityTarget,
            double minExpectedValuePercent,
            double minProposalScore,
            double maxStopProbability,
            double riskFractionPerTrade,
            boolean liveTradingAllowed,
            long updatedAtMs,
            Map<String, Double> strategyMultipliers
    ) {
        this.minProbabilityTarget = clamp(minProbabilityTarget, 0.45, envDouble("AI_POLICY_MAX_MIN_PROBABILITY_TARGET", 0.82));
        this.minExpectedValuePercent = clamp(minExpectedValuePercent, 0.10, envDouble("AI_POLICY_MAX_MIN_EXPECTED_VALUE_PERCENT", 2.50));
        this.minProposalScore = clamp(minProposalScore, 0.05, envDouble("AI_POLICY_MAX_MIN_PROPOSAL_SCORE", 0.65));
        this.maxStopProbability = clamp(maxStopProbability, 0.05, 0.80);
        this.riskFractionPerTrade = clamp(riskFractionPerTrade, 0.0001, 0.02);
        this.liveTradingAllowed = liveTradingAllowed;
        this.updatedAtMs = updatedAtMs <= 0 ? System.currentTimeMillis() : updatedAtMs;
        this.strategyMultipliers = strategyMultipliers == null
                ? defaultMultipliers()
                : new HashMap<>(strategyMultipliers);
    }

    public static AdaptiveTradingPolicy defaults() {
        return new AdaptiveTradingPolicy(
                envDouble("AI_MIN_PROBABILITY_TARGET", 0.62),
                envDouble("AI_MIN_EXPECTED_VALUE_PERCENT", 1.10),
                envDouble("AI_MIN_PROPOSAL_SCORE", 0.35),
                envDouble("AI_MAX_STOP_PROBABILITY", 0.48),
                envDouble("AI_RISK_FRACTION_PER_TRADE", 0.0025),
                !"false".equalsIgnoreCase(System.getenv().getOrDefault("AI_POLICY_LIVE_TRADING_ALLOWED", "true")),
                System.currentTimeMillis(),
                defaultMultipliers()
        );
    }

    public double multiplierFor(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) {
            return 1.0;
        }
        return strategyMultipliers.getOrDefault(strategyName.trim().toUpperCase(), 1.0);
    }

    public Map<String, Double> strategyMultipliers() {
        return Collections.unmodifiableMap(strategyMultipliers);
    }

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("updatedAtMs", Long.toString(updatedAtMs));
        p.setProperty("minProbabilityTarget", Double.toString(minProbabilityTarget));
        p.setProperty("minExpectedValuePercent", Double.toString(minExpectedValuePercent));
        p.setProperty("minProposalScore", Double.toString(minProposalScore));
        p.setProperty("maxStopProbability", Double.toString(maxStopProbability));
        p.setProperty("riskFractionPerTrade", Double.toString(riskFractionPerTrade));
        p.setProperty("liveTradingAllowed", Boolean.toString(liveTradingAllowed));
        for (Map.Entry<String, Double> e : strategyMultipliers.entrySet()) {
            p.setProperty("strategyMultiplier." + e.getKey(), Double.toString(e.getValue()));
        }
        return p;
    }

    public static AdaptiveTradingPolicy fromProperties(Properties p) {
        if (p == null || p.isEmpty()) {
            return defaults();
        }
        Map<String, Double> multipliers = defaultMultipliers();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("strategyMultiplier.")) {
                String name = key.substring("strategyMultiplier.".length()).trim().toUpperCase();
                multipliers.put(name, parseDouble(p.getProperty(key), multipliers.getOrDefault(name, 1.0)));
            }
        }
        return new AdaptiveTradingPolicy(
                parseDouble(p.getProperty("minProbabilityTarget"), defaults().minProbabilityTarget),
                parseDouble(p.getProperty("minExpectedValuePercent"), defaults().minExpectedValuePercent),
                parseDouble(p.getProperty("minProposalScore"), defaults().minProposalScore),
                parseDouble(p.getProperty("maxStopProbability"), defaults().maxStopProbability),
                parseDouble(p.getProperty("riskFractionPerTrade"), defaults().riskFractionPerTrade),
                Boolean.parseBoolean(p.getProperty("liveTradingAllowed", Boolean.toString(defaults().liveTradingAllowed))),
                parseLong(p.getProperty("updatedAtMs"), System.currentTimeMillis()),
                multipliers
        );
    }

    private static Map<String, Double> defaultMultipliers() {
        Map<String, Double> m = new HashMap<>();
        m.put("MARKET_INTELLIGENCE_AI", 1.0);
        m.put("FEATURE_MOMENTUM", 1.0);
        m.put("FEATURE_MEAN_REVERSION", 1.0);
        m.put("FEATURE_VWAP_RECLAIM", 1.0);
        m.put("FEATURE_SQUEEZE", 1.0);
        m.put("FEATURE_FAILED_BREAKDOWN", 1.0);
        m.put("MOMENTUM_NEWS_RUNNER", 1.0);
        m.put("PANIC_REVERSAL", 1.0);
        m.put("VWAP_RECLAIM", 1.0);
        m.put("FAILED_BREAKDOWN", 1.0);
        m.put("SHORT_SQUEEZE", 1.0);
        m.put("NEGATIVE_NEWS_SHORT", 1.0);
        m.put("FEATURE_NEGATIVE_NEWS_SHORT", 1.0);
        m.put("SHORT_ALPHA_BREAKDOWN", 1.0);
        m.put("OFFERING_FADE_SHORT", 1.0);
        m.put("FAILED_VWAP_SHORT", 1.0);
        m.put("PARABOLIC_EXHAUSTION_SHORT", 1.0);
        m.put("GAP_FILL", 1.0);
        return m;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double envDouble(String key, double fallback) {
        return parseDouble(System.getenv(key), fallback);
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
