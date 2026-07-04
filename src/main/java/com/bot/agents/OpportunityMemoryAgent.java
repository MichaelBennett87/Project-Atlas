package com.bot.agents;

import com.bot.intelligence.OpportunityMemoryProfile;
import com.bot.intelligence.OpportunityMemoryService;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;

import java.util.List;

/** Committee agent that uses persistent ticker personality memory. */
public class OpportunityMemoryAgent implements TradingAgent {
    @Override
    public String name() {
        return "OPPORTUNITY_MEMORY_AGENT";
    }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        String ticker = context == null ? null : context.getTicker();
        if (ticker == null || ticker.isBlank()) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.50, "No ticker for opportunity memory lookup.");
        }
        OpportunityMemoryProfile profile = OpportunityMemoryService.getInstance().profile(ticker);
        if (profile.getObservations() < 4) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.48, "Limited ticker personality memory. " + profile.compactSummary());
        }
        double score = profile.opportunityScore();
        String strategyName = candidate == null ? "" : candidate.getStrategyName();
        boolean strategyMatches = strategyName != null && !strategyName.isBlank() &&
                profile.getBestStrategy().toUpperCase().contains(strategyToken(strategyName.toUpperCase()));
        if (score >= 0.62 && strategyMatches) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.69, "Ticker personality supports this strategy. " + profile.compactSummary());
        }
        if (score >= 0.70) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.64, "Ticker has strong opportunity personality even if preferred strategy differs. " + profile.compactSummary());
        }
        if (score <= 0.22 && profile.getObservations() >= 10) {
            return AgentOpinion.of(name(), AgentVote.REDUCED, 0.61, "Ticker has weak historical opportunity personality; reduce size. " + profile.compactSummary());
        }
        return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.55, "Ticker opportunity personality is moderate. " + profile.compactSummary());
    }

    private static String strategyToken(String strategy) {
        if (strategy == null) return "";
        if (strategy.contains("PARABOLIC")) return "PARABOLIC";
        if (strategy.contains("CATALYST") || strategy.contains("NEWS") || strategy.contains("FDA") || strategy.contains("EARNINGS")) return "CATALYST";
        if (strategy.contains("VWAP")) return "VWAP";
        if (strategy.contains("RANGE")) return "RANGE";
        return strategy;
    }
}
