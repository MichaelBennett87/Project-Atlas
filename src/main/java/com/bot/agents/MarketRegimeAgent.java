package com.bot.agents;

import com.bot.intelligence.WorldModelAgent;
import com.bot.intelligence.WorldModelSnapshot;
import com.bot.intelligence.MarketRegime;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.model.TradeDirection;
import java.util.List;

public class MarketRegimeAgent implements TradingAgent {
    @Override
    public String name() { return "MARKET_REGIME_AGENT"; }

    @Override
    public AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        WorldModelSnapshot world = WorldModelAgent.getInstance().currentSnapshot();
        List<Bar> bars = context == null ? null : context.getBars();
        if (bars == null || bars.size() < 10) {
            if (world != null && world.getRegime() != MarketRegime.UNKNOWN) {
                if (world.getVolatilityScore() >= 0.72) {
                    return AgentOpinion.of(name(), AgentVote.REDUCED, 0.66, "World model shows elevated volatility; reduce size. " + world.compactSummary());
                }
                if (world.getCatalystHeatScore() >= 0.70 || world.getParabolicHeatScore() >= 0.65) {
                    return AgentOpinion.of(name(), AgentVote.BULLISH, 0.62, "World model supports momentum/catalyst trading even with limited ticker bars. " + world.compactSummary());
                }
                return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.55, "Ticker bars limited; using world model context. " + world.compactSummary());
            }
            return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.50, "Regime unknown; not enough recent bars.");
        }
        double start = bars.get(Math.max(0, bars.size() - 10)).close;
        double end = bars.get(bars.size() - 1).close;
        double trend = start == 0.0 ? 0.0 : (end - start) / start;
        double volatility = averageAbsoluteReturn(bars, 10);
        boolean shortCandidate = candidate != null && candidate.getDirection() == TradeDirection.SHORT_STOCK;
        if (volatility > 0.025 || (world != null && world.getVolatilityScore() >= 0.72)) {
            return AgentOpinion.of(name(), AgentVote.REDUCED, 0.68, "High short-term/world volatility regime; reduce size. tickerVolatility=" + volatility + " world=" + (world == null ? "unknown" : world.compactSummary()));
        }
        if (!shortCandidate && world != null && world.getParabolicHeatScore() >= 0.70 && trend >= -0.01) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.66, "World model shows strong parabolic tape; long continuation candidates can receive support. " + world.compactSummary());
        }
        if (shortCandidate && world != null && world.getParabolicHeatScore() >= 0.70 && trend <= 0.03) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.64, "World model shows parabolic tape; short exhaustion candidates can be valid with tight risk. " + world.compactSummary());
        }
        if (!shortCandidate && trend > 0.015) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.62, "Short-term trend supports long momentum. trend=" + trend);
        }
        if (!shortCandidate && trend < -0.020) {
            return AgentOpinion.of(name(), AgentVote.BEARISH, 0.62, "Short-term trend is hostile to fresh long entries. trend=" + trend);
        }
        if (shortCandidate && trend < -0.015) {
            return AgentOpinion.of(name(), AgentVote.BULLISH, 0.64, "Short-term trend supports short continuation. trend=" + trend);
        }
        if (shortCandidate && trend > 0.025) {
            return AgentOpinion.of(name(), AgentVote.REDUCED, 0.64, "Short entry is countering a strong upward regime; reduce size. trend=" + trend);
        }
        return AgentOpinion.of(name(), AgentVote.NEUTRAL, 0.55, "Regime is neutral/range-bound. trend=" + trend + " volatility=" + volatility);
    }

    private static double averageAbsoluteReturn(List<Bar> bars, int lookback) {
        if (bars == null || bars.size() < 2) return 0.0;
        int start = Math.max(1, bars.size() - lookback);
        double total = 0.0;
        int count = 0;
        for (int i = start; i < bars.size(); i++) {
            double prior = bars.get(i - 1).close;
            double current = bars.get(i).close;
            if (prior > 0.0) {
                total += Math.abs((current - prior) / prior);
                count++;
            }
        }
        return count == 0 ? 0.0 : total / count;
    }
}
