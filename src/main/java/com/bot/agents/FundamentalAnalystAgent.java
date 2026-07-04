package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.NewsEvent;
import java.util.List;

public class FundamentalAnalystAgent implements TradingAgent {
    @Override
    public String name() { return "FUNDAMENTAL_ANALYST"; }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        String text = text(context).toLowerCase();
        double score = 0.50;
        String reason = "No strong fundamental catalyst detected.";
        if (containsAny(text, "raises guidance", "raised guidance", "guidance raise", "beats expectations", "record revenue", "profitability", "positive earnings")) {
            score = 0.82;
            reason = "Fundamental catalyst supports stronger future cash flow or earnings expectations.";
        } else if (containsAny(text, "contract", "award", "partnership", "fda approval", "clearance", "acquisition", "merger")) {
            score = 0.76;
            reason = "Business catalyst may change forward expectations.";
        } else if (containsAny(text, "offering", "dilution", "going concern", "investigation", "lawsuit", "class action", "bankruptcy")) {
            score = 0.20;
            reason = "Fundamental risk or dilution/legal issue detected.";
        }
        return AgentOpinion.of(name(), voteFromScore(score), score, reason);
    }

    private static String text(StrategyContext context) {
        NewsEvent news = context == null ? null : context.getLatestNews();
        return news == null ? "" : news.fullText();
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }

    private static AgentVote voteFromScore(double score) {
        if (score >= 0.80) return AgentVote.STRONG_BULLISH;
        if (score >= 0.62) return AgentVote.BULLISH;
        if (score <= 0.25) return AgentVote.STRONG_BEARISH;
        if (score <= 0.40) return AgentVote.BEARISH;
        return AgentVote.NEUTRAL;
    }
}
