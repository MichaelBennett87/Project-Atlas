package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.NewsEvent;
import java.util.List;

public class RiskManagerAgent implements TradingAgent {
    private final double minCommitteeConfidence;
    private final int maxSuggestedQuantity;

    public RiskManagerAgent() {
        this.minCommitteeConfidence = envDouble("MULTI_AGENT_MIN_CANDIDATE_CONFIDENCE", 0.55);
        this.maxSuggestedQuantity = envInt("MULTI_AGENT_MAX_SUGGESTED_QTY", 10_000);
    }

    @Override
    public String name() { return "RISK_MANAGER_AGENT"; }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        if (candidate == null || !candidate.isActionableBuy()) {
            return AgentOpinion.of(name(), AgentVote.VETOED, 1.0, "No actionable trade candidate to approve.");
        }
        if (candidate.getConfidence() < minCommitteeConfidence) {
            return AgentOpinion.of(name(), AgentVote.VETOED, 0.90, "Candidate confidence below multi-agent minimum: " + candidate.getConfidence());
        }
        if (candidate.getSuggestedQuantity() <= 0) {
            return AgentOpinion.of(name(), AgentVote.VETOED, 0.90, "Candidate quantity is zero.");
        }
        if (candidate.getSuggestedQuantity() > maxSuggestedQuantity) {
            return AgentOpinion.of(name(), AgentVote.REDUCED, 0.80, "Suggested quantity exceeds committee maximum and must be resized.");
        }
        NewsEvent news = context == null ? null : context.getLatestNews();
        String text = news == null ? "" : news.fullText().toLowerCase();
        boolean shortCandidate = candidate.getDirection() == com.bot.model.TradeDirection.SHORT_STOCK;
        if (!shortCandidate && containsAny(text, "class action", "investigation", "offering", "registered direct", "atm offering", "delisting", "bankruptcy")) {
            return AgentOpinion.of(name(), AgentVote.VETOED, 0.95, "Hard long-risk veto for legal/dilution/distress language.");
        }
        if (shortCandidate && containsAny(text, "offering", "registered direct", "atm offering", "delisting", "bankruptcy")) {
            return AgentOpinion.of(name(), AgentVote.APPROVED, 0.82, "Risk manager allows short-side bearish catalyst within autonomous safety limits.");
        }
        return AgentOpinion.of(name(), AgentVote.APPROVED, 0.82, "Risk manager approves within autonomous safety limits.");
    }

    public int approvedQuantity(StrategySignal candidate, List<AgentOpinion> opinions) {
        if (candidate == null) return 0;
        int qty = Math.max(0, candidate.getSuggestedQuantity());
        if (qty > maxSuggestedQuantity) qty = maxSuggestedQuantity;
        boolean reduce = false;
        if (opinions != null) {
            for (AgentOpinion opinion : opinions) {
                if (opinion != null && opinion.getVote() == AgentVote.REDUCED) {
                    reduce = true;
                    break;
                }
            }
        }
        if (reduce) {
            qty = Math.max(1, (int)Math.floor(qty * 0.50));
        }
        return qty;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
