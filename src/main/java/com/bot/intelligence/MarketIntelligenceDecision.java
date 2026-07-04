package com.bot.intelligence;

public class MarketIntelligenceDecision {
    private final MarketFeatureSnapshot features;
    private final ProbabilityPrediction prediction;
    private final StrategyProposal bestProposal;
    private final boolean tradeAllowed;
    private final String reason;

    public MarketIntelligenceDecision(
            MarketFeatureSnapshot features,
            ProbabilityPrediction prediction,
            StrategyProposal bestProposal,
            boolean tradeAllowed,
            String reason
    ) {
        this.features = features;
        this.prediction = prediction;
        this.bestProposal = bestProposal;
        this.tradeAllowed = tradeAllowed;
        this.reason = reason == null ? "" : reason;
    }

    public MarketFeatureSnapshot getFeatures() { return features; }
    public ProbabilityPrediction getPrediction() { return prediction; }
    public StrategyProposal getBestProposal() { return bestProposal; }
    public boolean isTradeAllowed() { return tradeAllowed; }
    public String getReason() { return reason; }
}
