package com.bot.intelligence;

public final class PaperTradingPerformanceGateMain {
    private PaperTradingPerformanceGateMain() {
    }

    public static void main(String[] args) {
        PaperTradingPerformanceGate.Result result = new PaperTradingPerformanceGate().run();
        System.out.println("PAPER TRADING PERFORMANCE GATE COMPLETE: " + result.summary());
    }
}
