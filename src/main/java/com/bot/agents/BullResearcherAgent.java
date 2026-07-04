package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import java.util.List;

public class BullResearcherAgent implements TradingAgent {
    @Override
    public String name() { return "BULL_RESEARCHER"; }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        if (candidate == null) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.0, "No candidate to defend.");
        }
        double confidence = candidate.getConfidence();
        String reason = "Best candidate strategy=" + candidate.getStrategyName() + " confidence=" + confidence + " expectedMove=" + candidate.getExpectedMovePercent();
        if (confidence >= 0.80) {
            return AgentOpinion.of(name(), AgentVote.STRONG_BULLISH, confidence, reason);
        }
        if (confidence >= 0.60) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, confidence, reason);
        }
        return AgentOpinion.of(name(), AgentVote.NEUTRAL, confidence, reason);
    }
}
