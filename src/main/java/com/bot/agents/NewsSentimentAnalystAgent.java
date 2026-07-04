package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.sentiment.SentimentScore;
import com.bot.model.TradeDirection;
import java.util.List;

public class NewsSentimentAnalystAgent implements TradingAgent {
    @Override
    public String name() { return "NEWS_SENTIMENT_ANALYST"; }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        SentimentScore sentiment = context == null ? null : context.getSentiment();
        if (context == null || !context.hasNews()) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.50, "No current news catalyst; sentiment neutral.");
        }
        double net = sentiment == null ? 0.0 : sentiment.netSentiment();
        double confidence = Math.max(0.0, Math.min(1.0, 0.50 + Math.abs(net) * 0.50));
        boolean shortCandidate = candidate != null && candidate.getDirection() == TradeDirection.SHORT_STOCK;
        if (!shortCandidate && net >= 0.55) {
            return AgentOpinion.of(name(), AgentVote.STRONG_BULLISH, confidence, "Strong positive financial sentiment detected. net=" + net);
        }
        if (!shortCandidate && net >= 0.25) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, confidence, "Positive financial sentiment detected. net=" + net);
        }
        if (!shortCandidate && net <= -0.55) {
            return AgentOpinion.of(name(), AgentVote.STRONG_BEARISH, confidence, "Strong negative financial sentiment detected. net=" + net);
        }
        if (!shortCandidate && net <= -0.25) {
            return AgentOpinion.of(name(), AgentVote.BEARISH, confidence, "Negative financial sentiment detected. net=" + net);
        }
        if (shortCandidate && net <= -0.55) {
            return AgentOpinion.of(name(), AgentVote.STRONG_BULLISH, confidence, "Negative sentiment supports short thesis. net=" + net);
        }
        if (shortCandidate && net <= -0.25) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, confidence, "Negative sentiment supports short thesis. net=" + net);
        }
        if (shortCandidate && net >= 0.55) {
            return AgentOpinion.of(name(), AgentVote.STRONG_BEARISH, confidence, "Positive sentiment is hostile to short thesis. net=" + net);
        }
        if (shortCandidate && net >= 0.25) {
            return AgentOpinion.of(name(), AgentVote.BEARISH, confidence, "Positive sentiment is hostile to short thesis. net=" + net);
        }
        return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.50, "Sentiment is mixed or weak. net=" + net);
    }
}
