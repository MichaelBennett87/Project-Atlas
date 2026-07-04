package com.bot.intelligence;

public final class LiveTradeReadinessGateMain {
    private LiveTradeReadinessGateMain() {
    }

    public static void main(String[] args) {
        System.out.println("LIVE TRADE READINESS GATE SNAPSHOT STARTED");
        LiveTradeReadinessGate.Result result = LiveTradeReadinessGate.getInstance().writeHealthSnapshot();
        System.out.println("LIVE TRADE READINESS GATE SNAPSHOT COMPLETE: " + result.summary());
    }
}
