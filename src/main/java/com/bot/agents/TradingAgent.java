package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import java.util.List;

public interface TradingAgent {
    String name();
    AgentOpinion evaluate(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals);
}
