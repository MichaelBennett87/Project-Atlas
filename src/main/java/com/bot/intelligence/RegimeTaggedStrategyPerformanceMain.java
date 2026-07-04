package com.bot.intelligence;

public final class RegimeTaggedStrategyPerformanceMain {
    private RegimeTaggedStrategyPerformanceMain() {
    }

    public static void main(String[] args) {
        System.out.println("REGIME-TAGGED STRATEGY PERFORMANCE STARTED");
        RegimeTaggedStrategyPerformanceEngine.Result result =
                new RegimeTaggedStrategyPerformanceEngine().run();
        System.out.println("REGIME-TAGGED STRATEGY PERFORMANCE COMPLETE: " + result.summary());
    }
}
