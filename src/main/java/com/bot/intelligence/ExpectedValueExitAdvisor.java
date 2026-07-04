package com.bot.intelligence;

import com.bot.model.Position;

/** Probability-style exit advisor used after basic hard-stop and staged-profit checks. */
public class ExpectedValueExitAdvisor {
    private final boolean enabled = !"false".equalsIgnoreCase(System.getenv().getOrDefault("EV_EXIT_ADVISOR_ENABLED", "true"));
    private final long minAgeMs = envLong("EV_EXIT_MIN_AGE_SECONDS", 900L) * 1000L;
    private final long staleAgeMs = envLong("EV_EXIT_STALE_AGE_SECONDS", 1_800L) * 1000L;
    private final double weakConfidence = envDouble("EV_EXIT_WEAK_CONFIDENCE", 0.72);
    private final double weakPriority = envDouble("EV_EXIT_WEAK_PRIORITY", 0.035);
    private final double minLoss = envDouble("EV_EXIT_MIN_LOSS_PERCENT", -0.35) / 100.0;
    private final double giveback = envDouble("EV_EXIT_GAIN_GIVEBACK_PERCENT", 1.10) / 100.0;

    public String exitReason(Position position, double currentPrice) {
        if (!enabled || position == null || currentPrice <= 0.0 || position.entryPrice <= 0.0) return null;
        long ageMs = position.openedAt <= 0 ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - position.openedAt);
        if (ageMs < minAgeMs) return null;
        double pnl = position.isShortPosition()
                ? (position.entryPrice - currentPrice) / position.entryPrice
                : (currentPrice - position.entryPrice) / position.entryPrice;
        double bestGain = bestGain(position, currentPrice);
        boolean weakEntry = position.entryConfidence < weakConfidence && position.entryPriorityScore < weakPriority;
        if (ageMs >= staleAgeMs && weakEntry && pnl <= minLoss) {
            return "EV_EXIT_STALE_WEAK_TRADE ageMinutes=" + String.format("%.1f", ageMs / 60000.0) + " pnl=" + String.format("%.2f%%", pnl * 100.0);
        }
        if (bestGain > 0.0125 && pnl <= bestGain - giveback && weakEntry) {
            return "EV_EXIT_EXPECTANCY_DECAY bestGain=" + String.format("%.2f%%", bestGain * 100.0) + " current=" + String.format("%.2f%%", pnl * 100.0);
        }
        return null;
    }

    private double bestGain(Position p, double currentPrice) {
        if (p.isShortPosition()) {
            double trough = p.troughPrice > 0 ? p.troughPrice : currentPrice;
            return (p.entryPrice - trough) / p.entryPrice;
        }
        double peak = p.peakPrice > 0 ? p.peakPrice : currentPrice;
        return (peak - p.entryPrice) / p.entryPrice;
    }
    private static long envLong(String key,long fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Long.parseLong(v.trim());}catch(Exception e){return fallback;}}
    private static double envDouble(String key,double fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Double.parseDouble(v.trim());}catch(Exception e){return fallback;}}
}
