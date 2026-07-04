package com.bot.agents;

import com.bot.intelligence.MarketRegime;
import com.bot.intelligence.WorldModelAgent;
import com.bot.intelligence.WorldModelSnapshot;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.TradeDirection;

import java.util.List;

/** Trading committee agent that consults the shared WorldModelAgent. */
public class WorldModelConsultantAgent implements TradingAgent {
    @Override
    public String name() {
        return "WORLD_MODEL_AGENT";
    }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        WorldModelSnapshot world = WorldModelAgent.getInstance().currentSnapshot();
        if (world == null || world.getRegime() == MarketRegime.UNKNOWN) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.50, "World model has not accumulated enough context yet.");
        }
        boolean shortCandidate = candidate != null && candidate.getDirection() == TradeDirection.SHORT_STOCK;
        if (world.getDataConfidenceScore() < 0.15 && context != null && context.hasNews()) {
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.46, "World model has low external provider diversity; avoid over-weighting single-source context. " + world.compactSummary());
        }
        if (!shortCandidate && (world.getRegime() == MarketRegime.STRONG_UPTREND || world.getRegime() == MarketRegime.UPTREND) && world.getCatalystHeatScore() >= 0.55) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.68, "World model supports long catalyst/momentum setups. " + world.compactSummary());
        }
        if (!shortCandidate && world.getRegime() == MarketRegime.PANIC) {
            return AgentOpinion.of(name(), AgentVote.REDUCED, 0.70, "World model is panic-like; reduce long size unless setup is exceptional. " + world.compactSummary());
        }
        if (shortCandidate && (world.getRegime() == MarketRegime.DOWNTREND || world.getRegime() == MarketRegime.PANIC)) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.66, "World model supports short-side/fade setups. " + world.compactSummary());
        }
        if (world.getParabolicHeatScore() >= 0.70) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.63, "World model shows hot parabolic tape; momentum agents have opportunity but need tight exits. " + world.compactSummary());
        }
        if (world.getVolatilityScore() >= 0.80) {
            return AgentOpinion.of(name(), AgentVote.REDUCED, 0.62, "World model volatility is high; reduce sizing. " + world.compactSummary());
        }
        return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.55, "World model neutral/mixed. " + world.compactSummary());
    }
}
