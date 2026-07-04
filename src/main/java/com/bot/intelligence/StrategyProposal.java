package com.bot.intelligence;

public class StrategyProposal {
    public final String strategyName;
    public final double rawScore;
    public final String reason;

    public StrategyProposal(String strategyName, double rawScore, String reason) {
        this.strategyName = strategyName == null ? "UNKNOWN" : strategyName;
        this.rawScore = Math.max(0.0, Math.min(1.0, rawScore));
        this.reason = reason == null ? "" : reason;
    }
}
