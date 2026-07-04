package com.bot.intelligence;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.master.TradingStrategy;
import com.bot.model.TradeDirection;

public class MarketIntelligenceStrategy implements TradingStrategy {

    private final MarketIntelligenceEngine engine;
    private final AdaptiveTradingPolicyStore policyStore;

    public MarketIntelligenceStrategy() {
        this(new AdaptiveTradingPolicyStore());
    }

    public MarketIntelligenceStrategy(AdaptiveTradingPolicyStore policyStore) {
        this(new MarketIntelligenceEngine(
                new MarketFeatureEngine(),
                new HeuristicProbabilityModel(),
                new StrategyProposalGenerator(),
                new FeatureJournal(),
                policyStore
        ), policyStore);
    }

    public MarketIntelligenceStrategy(MarketIntelligenceEngine engine) {
        this(engine, new AdaptiveTradingPolicyStore());
    }

    public MarketIntelligenceStrategy(MarketIntelligenceEngine engine, AdaptiveTradingPolicyStore policyStore) {
        this.engine = engine;
        this.policyStore = policyStore == null ? new AdaptiveTradingPolicyStore() : policyStore;
    }

    @Override
    public String name() {
        return "MARKET_INTELLIGENCE_AI";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        if (context == null) {
            return StrategySignal.hold(name(), "UNKNOWN", 0.0, "No context.");
        }

        MarketIntelligenceDecision decision = engine.evaluate(context);
        ProbabilityPrediction prediction = decision.getPrediction();
        double confidence = prediction == null ? 0.0 : prediction.confidence();

        if (!decision.isTradeAllowed()) {
            return StrategySignal.hold(
                    name(),
                    context.getTicker(),
                    confidence,
                    decision.getReason()
            );
        }

        int quantity = suggestedQuantity(context, prediction);
        return StrategySignal.buy(
                name(),
                context.getTicker(),
                TradeDirection.LONG_STOCK,
                confidence,
                Math.max(0.01, prediction.getExpectedValuePercent() / 100.0),
                quantity,
                decision.getReason()
        );
    }

    private int suggestedQuantity(StrategyContext context, ProbabilityPrediction prediction) {
        double equity = context == null ? 0.0 : context.getAccountEquity();
        double price = context == null ? 0.0 : context.getLastPrice();
        if (equity <= 0.0 || price <= 0.0) {
            return 1;
        }
        AdaptiveTradingPolicy policy = policyStore.currentPolicy();
        double riskFraction = policy.riskFractionPerTrade;
        double dollars = Math.max(50.0, equity * riskFraction * Math.max(0.50, prediction.confidence()));
        return Math.max(1, (int) Math.floor(dollars / price));
    }
}
