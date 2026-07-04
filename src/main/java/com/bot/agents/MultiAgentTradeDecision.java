package com.bot.agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MultiAgentTradeDecision {
    private final boolean approved;
    private final boolean vetoed;
    private final double confidenceMultiplier;
    private final int approvedQuantity;
    private final String reason;
    private final List<AgentOpinion> opinions;

    public MultiAgentTradeDecision(
            boolean approved,
            boolean vetoed,
            double confidenceMultiplier,
            int approvedQuantity,
            String reason,
            List<AgentOpinion> opinions
    ) {
        this.approved = approved;
        this.vetoed = vetoed;
        this.confidenceMultiplier = clampMultiplier(confidenceMultiplier);
        this.approvedQuantity = Math.max(0, approvedQuantity);
        this.reason = reason == null ? "" : reason;
        this.opinions = opinions == null ? new ArrayList<>() : new ArrayList<>(opinions);
    }

    public static MultiAgentTradeDecision approved(int approvedQuantity, double confidenceMultiplier, String reason, List<AgentOpinion> opinions) {
        return new MultiAgentTradeDecision(true, false, confidenceMultiplier, approvedQuantity, reason, opinions);
    }

    public static MultiAgentTradeDecision vetoed(String reason, List<AgentOpinion> opinions) {
        return new MultiAgentTradeDecision(false, true, 0.0, 0, reason, opinions);
    }

    public boolean isApproved() { return approved; }
    public boolean isVetoed() { return vetoed; }
    public double getConfidenceMultiplier() { return confidenceMultiplier; }
    public int getApprovedQuantity() { return approvedQuantity; }
    public String getReason() { return reason; }
    public List<AgentOpinion> getOpinions() { return Collections.unmodifiableList(opinions); }

    public String compactSummary() {
        StringBuilder sb = new StringBuilder(reason == null ? "" : reason);
        if (!opinions.isEmpty()) {
            sb.append(" | votes=");
            for (int i = 0; i < opinions.size(); i++) {
                AgentOpinion opinion = opinions.get(i);
                if (i > 0) sb.append("; ");
                sb.append(opinion.getAgentName()).append(":").append(opinion.getVote()).append("@").append(String.format("%.2f", opinion.getConfidence()));
            }
        }
        return sb.toString();
    }

    private static double clampMultiplier(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.25, value));
    }
}
