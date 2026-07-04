package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import java.util.List;

public class TraderDecisionAgent implements TradingAgent {
    @Override
    public String name() { return "TRADER_DECISION_AGENT"; }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        if (candidate == null || !candidate.isActionableBuy()) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.0, "No actionable candidate available.");
        }
        double score = Math.max(0.0, Math.min(1.0, candidate.getConfidence() * 0.75 + Math.min(0.25, candidate.getExpectedMovePercent() / 20.0)));
        return AgentOpinion.of(name(), score >= 0.75 ? AgentVote.STRONG_BULLISH : AgentVote.BULLISH, score,
                "Trader proposes autonomous execution for " + candidate.getTicker() + " using " + candidate.getStrategyName());
    }
}
