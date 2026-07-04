package com.bot.intelligence.historical;

/** Standalone after-hours historical research entry point. */
public final class HistoricalResearchMain {
    public static void main(String[] args) {
        HistoricalResearchOrchestrator.Result result = new HistoricalResearchOrchestrator().runNightlyResearch();
        System.out.println("HISTORICAL RESEARCH COMPLETE: " + result.summary());
    }
}
