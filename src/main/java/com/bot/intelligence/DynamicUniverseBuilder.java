package com.bot.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds a focused live universe instead of blindly spending equal effort on
 * every tradable symbol. Inputs are intentionally simple: the broker provides
 * the tradable universe, while the AI memory/ranker promotes names with recent
 * volume, news, catalysts, and predictive context.
 */
public final class DynamicUniverseBuilder {
    private final PredictiveOpportunityRanker ranker = PredictiveOpportunityRanker.getInstance();
    private final StockMemoryService memory = StockMemoryService.getInstance();

    public List<String> build(List<String> tradableUniverse, Set<String> activeSymbols, int maxSymbols) {
        int cap = Math.max(50, maxSymbols);
        LinkedHashSet<String> selected = new LinkedHashSet<>();

        if (tradableUniverse != null) {
            int warmLimit = Math.min(tradableUniverse.size(), envInt("STOCK_MEMORY_WARM_UNIVERSE_LIMIT", 6000));
            for (int i = 0; i < warmLimit; i++) {
                memory.observeUniverseSymbol(tradableUniverse.get(i));
            }
        }

        addAll(selected, ranker.topTickers(Math.min(cap / 2, envInt("DYNAMIC_UNIVERSE_MEMORY_LIMIT", 350))));
        addAll(selected, activeSymbols);
        addAll(selected, coreMarketProxies());

        if (tradableUniverse != null) {
            for (String symbol : tradableUniverse) {
                if (selected.size() >= cap) break;
                String normalized = normalize(symbol);
                if (!normalized.isBlank()) {
                    selected.add(normalized);
                }
            }
        }

        List<String> result = new ArrayList<>(selected);
        if (result.size() > cap) {
            result = new ArrayList<>(result.subList(0, cap));
        }
        System.out.println("DYNAMIC UNIVERSE BUILT: selected=" + result.size() + " memoryProfiles=" + memory.size() + " cap=" + cap);
        return result;
    }

    private static void addAll(LinkedHashSet<String> target, Iterable<String> values) {
        if (target == null || values == null) return;
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) target.add(normalized);
        }
    }

    private static List<String> coreMarketProxies() {
        List<String> proxies = new ArrayList<>();
        proxies.add("SPY");
        proxies.add("QQQ");
        proxies.add("IWM");
        proxies.add("DIA");
        return proxies;
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
