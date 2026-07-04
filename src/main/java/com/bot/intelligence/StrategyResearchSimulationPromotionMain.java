package com.bot.intelligence;

public final class StrategyResearchSimulationPromotionMain {
    private StrategyResearchSimulationPromotionMain() {
    }

    public static void main(String[] args) {
        System.out.println("STRATEGY RESEARCH SIMULATION PROMOTION STARTED");
        StrategyResearchSimulationPromotionEngine.Result result =
                new StrategyResearchSimulationPromotionEngine().run();
        System.out.println("STRATEGY RESEARCH SIMULATION PROMOTION COMPLETE: " + result.summary());
    }
}
