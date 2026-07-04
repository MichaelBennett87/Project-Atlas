package com.bot.scanner;

import com.bot.model.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared opportunity registry. Scanner, news, candidate tracking, and entry
 * staging all touch the same OpportunityContext so bars/velocity/RVOL/lifecycle
 * cannot drift into separate sources of truth.
 */
public final class OpportunityContextRegistry {
    private static final OpportunityContextRegistry INSTANCE = new OpportunityContextRegistry();

    private final Map<String, OpportunityContext> contexts = new ConcurrentHashMap<>();
    private final SharedRollingBarHistoryService sharedBars = SharedRollingBarHistoryService.getInstance();

    private OpportunityContextRegistry() {}

    public static OpportunityContextRegistry getInstance() {
        return INSTANCE;
    }

    public OpportunityContext getOrCreate(String ticker) {
        String symbol = normalize(ticker);
        if (symbol.isEmpty()) {
            return new OpportunityContext("");
        }
        return contexts.computeIfAbsent(symbol, OpportunityContext::new);
    }

    public TechnicalFeatureSnapshot observeBar(String ticker, Bar bar, String source) {
        String symbol = normalize(ticker);
        OpportunityContext context = getOrCreate(symbol);
        if (source != null && !source.isBlank()) {
            context.markSource(source, context.headline());
        }
        sharedBars.observe(symbol, bar);
        return TechnicalFeatureService.getInstance().snapshot(symbol);
    }

    public void markCatalyst(String ticker, String source, String headline, double catalystScore, double predictiveScore, boolean highPriority) {
        OpportunityContext context = getOrCreate(ticker);
        context.markSource(source, headline);
        context.markCatalyst(catalystScore, predictiveScore, highPriority);
    }

    public void markLifecycle(String ticker, MomentumCandidateTracker.LifecycleStage stage) {
        getOrCreate(ticker).markLifecycle(stage);
    }

    public List<String> topSymbols(int limit) {
        List<OpportunityContext> list = new ArrayList<>(contexts.values());
        list.sort(Comparator.comparingDouble((OpportunityContext c) -> {
            TechnicalFeatureSnapshot f = c.features();
            return Math.max(c.catalystScore(), 0.0) * 0.25
                    + Math.max(c.predictiveScore(), 0.0) * 0.15
                    + Math.max(f.relativeVolume, 0.0) * 0.10
                    + Math.abs(f.threeBarVelocityPct) * 0.08
                    + Math.min(1.0, Math.max(0.0, f.dollarVolume / 150_000.0)) * 0.20;
        }).reversed());
        List<String> out = new ArrayList<>();
        int max = Math.max(0, limit);
        long now = System.currentTimeMillis();
        long ttl = longEnv("OPPORTUNITY_CONTEXT_TTL_MS", 15 * 60_000L);
        for (OpportunityContext context : list) {
            if (out.size() >= max) break;
            if (context == null || context.ticker().isBlank()) continue;
            if (now - context.lastUpdatedAt() > ttl) continue;
            out.add(context.ticker());
        }
        return out;
    }

    public int activeCount() {
        long now = System.currentTimeMillis();
        long ttl = longEnv("OPPORTUNITY_CONTEXT_TTL_MS", 15 * 60_000L);
        contexts.entrySet().removeIf(e -> now - e.getValue().lastUpdatedAt() > ttl);
        return contexts.size();
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static long longEnv(String key, long fallback) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
