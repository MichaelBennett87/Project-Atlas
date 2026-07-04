package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.model.TradeDirection;
import java.util.List;

public class TechnicalAnalystAgent implements TradingAgent {
    @Override
    public String name() { return "TECHNICAL_ANALYST"; }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        List<Bar> bars = context == null ? null : context.getBars();
        if (bars == null || bars.size() < 3) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.45, "Not enough bars for technical confirmation.");
        }
        Bar last = bars.get(bars.size() - 1);
        Bar prior = bars.get(bars.size() - 2);
        double move = prior.close == 0.0 ? 0.0 : (last.close - prior.close) / prior.close;
        double volumeRatio = averageVolume(bars) <= 0.0 ? 1.0 : last.volume / averageVolume(bars);
        boolean shortCandidate = candidate != null && candidate.getDirection() == TradeDirection.SHORT_STOCK;
        double directionalMove = shortCandidate ? -move : move;
        double score = 0.50 + Math.max(-0.25, Math.min(0.25, directionalMove * 5.0)) + Math.max(-0.10, Math.min(0.20, (volumeRatio - 1.0) * 0.08));
        score = Math.max(0.0, Math.min(1.0, score));
        String reason = "direction=" + (shortCandidate ? "SHORT" : "LONG") + " move=" + String.format("%.4f", move) + " volumeRatio=" + String.format("%.2f", volumeRatio);
        return AgentOpinion.of(name(), voteFromScore(score), score, reason);
    }

    private static double averageVolume(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int start = Math.max(0, bars.size() - 20);
        double total = 0.0;
        int count = 0;
        for (int i = start; i < bars.size(); i++) {
            total += Math.max(0.0, bars.get(i).volume);
            count++;
        }
        return count == 0 ? 0.0 : total / count;
    }

    private static AgentVote voteFromScore(double score) {
        if (score >= 0.75) return AgentVote.STRONG_BULLISH;
        if (score >= 0.58) return AgentVote.BULLISH;
        if (score <= 0.25) return AgentVote.STRONG_BEARISH;
        if (score <= 0.42) return AgentVote.BEARISH;
        return AgentVote.NEUTRAL;
    }
}
