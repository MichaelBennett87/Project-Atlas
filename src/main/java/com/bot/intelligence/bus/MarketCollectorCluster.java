package com.bot.intelligence.bus;

import com.bot.intelligence.MarketKnowledgeDatabase;
import com.bot.intelligence.MarketKnowledgeStore;
import com.bot.intelligence.MarketStateDatabase2;
import com.bot.intelligence.StateDrivenOpportunityRankingEngine;

/** Starts the data-collection side of the architecture independent from trading strategies. */
public final class MarketCollectorCluster {
    private final MicrostructureAgent microstructureAgent = new MicrostructureAgent();
    private final StateDrivenOpportunityRankingEngine stateRanker = new StateDrivenOpportunityRankingEngine();
    private final MarketOperatingSystemCollector marketOsCollector = new MarketOperatingSystemCollector();
    private volatile boolean started;
    public synchronized void start() {
        if (started) return;
        started = true;
        MarketKnowledgeDatabase.getInstance().start();
        MarketKnowledgeStore.getInstance().start();
        MarketStateDatabase2.getInstance().start();
        boolean startMicrostructure = boolEnv("MARKET_COLLECTOR_START_MICROSTRUCTURE_AGENT", false);
        boolean startStateRanker = boolEnv("MARKET_COLLECTOR_START_STATE_RANKER", false);
        boolean startMarketOs = boolEnv("MARKET_COLLECTOR_START_MARKET_OS", false);
        if (startMicrostructure) microstructureAgent.start();
        if (startStateRanker) stateRanker.start();
        if (startMarketOs) marketOsCollector.start();
        System.out.println("MARKET COLLECTOR CLUSTER STARTED: source=PolygonPremium sink=MarketKnowledgeDatabase " +
                "agents=microstructure:" + startMicrostructure +
                ",stateRanker:" + startStateRanker +
                ",marketOS:" + startMarketOs +
                " mode=MOMENTUM_FIRST_RESEARCH_ONLY_BY_DEFAULT");
    }
    public synchronized void stop() {
        try { microstructureAgent.stop(); } catch (Exception ignored) {}
        try { stateRanker.stop(); } catch (Exception ignored) {}
        try { marketOsCollector.stop(); } catch (Exception ignored) {}
        try { MarketStateDatabase2.getInstance().stop(); } catch (Exception ignored) {}
        started = false;
    }

    private static boolean boolEnv(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }
}
