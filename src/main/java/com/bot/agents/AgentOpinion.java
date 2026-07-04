package com.bot.agents;

public class AgentOpinion {
    private final String agentName;
    private final AgentVote vote;
    private final double confidence;
    private final String reason;

    public AgentOpinion(String agentName, AgentVote vote, double confidence, String reason) {
        this.agentName = agentName == null ? "UNKNOWN_AGENT" : agentName.trim();
        this.vote = vote == null ? AgentVote.NEUTRAL : vote;
        this.confidence = clamp(confidence);
        this.reason = reason == null ? "" : reason.trim();
    }

    public static AgentOpinion of(String agentName, AgentVote vote, double confidence, String reason) {
        return new AgentOpinion(agentName, vote, confidence, reason);
    }

    public String getAgentName() { return agentName; }
    public AgentVote getVote() { return vote; }
    public double getConfidence() { return confidence; }
    public String getReason() { return reason; }

    public boolean isBullish() {
        return vote == AgentVote.BULLISH || vote == AgentVote.STRONG_BULLISH;
    }

    public boolean isBearish() {
        return vote == AgentVote.BEARISH || vote == AgentVote.STRONG_BEARISH || vote == AgentVote.VETOED;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public String toString() {
        return agentName + "=" + vote + " confidence=" + confidence + " reason=" + reason;
    }
}
