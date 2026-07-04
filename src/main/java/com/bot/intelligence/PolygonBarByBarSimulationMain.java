package com.bot.intelligence;

public final class PolygonBarByBarSimulationMain {
    private PolygonBarByBarSimulationMain() {
    }

    public static void main(String[] args) {
        System.out.println("POLYGON BAR-BY-BAR SIMULATION STARTED");
        PolygonBarByBarSimulationEngine.Result result =
                new PolygonBarByBarSimulationEngine().run();
        System.out.println("POLYGON BAR-BY-BAR SIMULATION COMPLETE: " + result.summary());
    }
}
