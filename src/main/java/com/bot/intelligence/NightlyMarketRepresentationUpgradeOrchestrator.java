package com.bot.intelligence;

/**
 * Runs the market-state representation upgrades after market close.
 * This does not trade and does not rewrite source. It creates richer technical,
 * synthetic, feature-selection, multi-timeframe, knowledge-graph, and memory artifacts
 * for the next policy evolution pass.
 */
public final class NightlyMarketRepresentationUpgradeOrchestrator {
    public Result run() {
        long start = System.currentTimeMillis();
        TechnicalIntelligenceEngine.RunResult tech = new TechnicalIntelligenceEngine().runFromHistoricalRepository();
        SyntheticTrainingGenerator.Result synthetic = new SyntheticTrainingGenerator().generate();
        FeatureImportanceAnalyzer.Result importance = new FeatureImportanceAnalyzer().analyze();
        MarketKnowledgeGraph.Result graph = new MarketKnowledgeGraph().runNightlyExpansion();
        AnalystWorkingMemory.Result memory = AnalystWorkingMemory.getInstance().snapshot();
        Result result = new Result(tech.summary(), synthetic.summary(), importance.summary, graph.summary(), memory.items, System.currentTimeMillis() - start);
        System.out.println("NIGHTLY MARKET REPRESENTATION UPGRADE COMPLETE: " + result.summary());
        return result;
    }

    public static final class Result {
        public final String technicalSummary, syntheticSummary, featureImportanceSummary, knowledgeGraphSummary;
        public final int workingMemoryItems;
        public final long elapsedMs;
        Result(String t, String s, String f, String k, int m, long e) { technicalSummary=t; syntheticSummary=s; featureImportanceSummary=f; knowledgeGraphSummary=k; workingMemoryItems=m; elapsedMs=e; }
        public String summary() { return "technical={" + technicalSummary + "} synthetic={" + syntheticSummary + "} featureImportance={" + featureImportanceSummary + "} knowledgeGraph={" + knowledgeGraphSummary + "} workingMemoryItems=" + workingMemoryItems + " elapsedMs=" + elapsedMs; }
    }
}
