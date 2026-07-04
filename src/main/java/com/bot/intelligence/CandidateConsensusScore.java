package com.bot.intelligence;

import com.bot.master.StrategySignal;

import java.util.Locale;

/**
 * Score object for comparing candidate trades using shared agent context.
 * The score is advisory; the risk kernel and multi-agent committee remain authoritative.
 */
public final class CandidateConsensusScore {
    private final StrategySignal signal;
    private final double basePriority;
    private final double worldScore;
    private final double opportunityScore;
    private final double replayScore;
    private final double lifecycleScore;
    private final double unifiedScore;
    private final String reason;

    public CandidateConsensusScore(
            StrategySignal signal,
            double basePriority,
            double worldScore,
            double opportunityScore,
            double replayScore,
            double lifecycleScore,
            double unifiedScore,
            String reason
    ) {
        this.signal = signal;
        this.basePriority = safe(basePriority);
        this.worldScore = clamp(worldScore);
        this.opportunityScore = clamp(opportunityScore);
        this.replayScore = clamp(replayScore);
        this.lifecycleScore = clamp(lifecycleScore);
        this.unifiedScore = safe(unifiedScore);
        this.reason = reason == null ? "" : reason;
    }

    public StrategySignal getSignal() { return signal; }
    public double getBasePriority() { return basePriority; }
    public double getWorldScore() { return worldScore; }
    public double getOpportunityScore() { return opportunityScore; }
    public double getReplayScore() { return replayScore; }
    public double getLifecycleScore() { return lifecycleScore; }
    public double getUnifiedScore() { return unifiedScore; }
    public String getReason() { return reason; }

    public String compactSummary() {
        return (signal == null ? "UNKNOWN" : signal.getTicker() + ":" + signal.getStrategyName()) +
                " unified=" + fmt(unifiedScore) +
                " base=" + fmt(basePriority) +
                " world=" + fmt(worldScore) +
                " memory=" + fmt(opportunityScore) +
                " replay=" + fmt(replayScore) +
                " lifecycle=" + fmt(lifecycleScore) +
                " reason=" + reason;
    }

    private static double safe(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, value);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
