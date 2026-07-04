package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.NewsEvent;
import com.bot.model.TradeDirection;
import java.util.List;

public class BearResearcherAgent implements TradingAgent {
    @Override
    public String name() { return "BEAR_RESEARCHER"; }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        String text = context == null || context.getLatestNews() == null ? "" : context.getLatestNews().fullText().toLowerCase();
        if (containsAny(text, "class action", "investigation", "offering", "registered direct", "atm offering", "bankruptcy", "delisting")) {
            return AgentOpinion.of(name(), AgentVote.STRONG_BEARISH, 0.88, "Bear case found high-risk legal/dilution/distress language.");
        }
        if (candidate == null || candidate.getConfidence() < 0.65) {
            return AgentOpinion.of(name(), AgentVote.BEARISH, 0.60, "Bear case: candidate confidence is not strong enough for autonomous execution.");
        }
        if (candidate.getDirection() == TradeDirection.SHORT_STOCK) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.62, "Bear researcher supports the short-side thesis for an approved short candidate.");
        }
        return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.42, "No decisive bear case found.");
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }
}
