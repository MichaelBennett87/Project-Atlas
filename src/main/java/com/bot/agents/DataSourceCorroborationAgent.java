package com.bot.agents;

import com.bot.intelligence.bus.MarketIntelligenceBus;
import com.bot.intelligence.bus.MarketIntelligenceSignal;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;

import java.util.List;

public class DataSourceCorroborationAgent implements TradingAgent {
    private final long lookbackMs;
    private final double strongThreshold;
    private final double weakThreshold;

    public DataSourceCorroborationAgent() {
        this.lookbackMs = envLong("DATA_SOURCE_AGENT_LOOKBACK_MS", 15 * 60_000L);
        this.strongThreshold = envDouble("DATA_SOURCE_AGENT_STRONG_THRESHOLD", 0.65);
        this.weakThreshold = envDouble("DATA_SOURCE_AGENT_WEAK_THRESHOLD", 0.20);
    }

    @Override
    public String name() {
        return "DATA_SOURCE_CORROBORATION_AGENT";
    }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        if (context == null || context.getTicker() == null || context.getTicker().isBlank()) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.50, "No ticker for data-source corroboration.");
        }
        List<MarketIntelligenceSignal> recent = MarketIntelligenceBus.getInstance().recentSignals(context.getTicker(), 50);
        double corroboration = MarketIntelligenceBus.getInstance().corroborationScore(context.getTicker(), lookbackMs);
        if (recent.isEmpty()) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.45, "No external bus signals yet for ticker; neutral rather than veto.");
        }
        if (corroboration >= strongThreshold) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.70 + Math.min(0.25, corroboration * 0.25),
                    "Multiple/strong market-intelligence bus signals corroborate ticker. score=" + String.format("%.2f", corroboration) + " recentSignals=" + recent.size());
        }
        if (corroboration <= weakThreshold && context.hasNews()) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.45,
                    "Single-source or weak corroboration. score=" + String.format("%.2f", corroboration) + " recentSignals=" + recent.size());
        }
        return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.55,
                "Moderate data-source corroboration. score=" + String.format("%.2f", corroboration) + " recentSignals=" + recent.size());
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
