package com.bot.master;

import com.bot.model.NewsEvent;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents the unified engine from buying the same story repeatedly through
 * related tickers, ETFs, or sympathy symbols. This is intentionally lightweight:
 * it uses the article headline/content as the story key and a coarse sector key
 * to avoid over-concentration while still letting independent catalysts through.
 */
public class StoryExposureManager {

    private static final long DEFAULT_STORY_BLOCK_MS = 30L * 60L * 1000L;
    private static final long DEFAULT_SECTOR_BLOCK_MS = 30L * 60L * 1000L;

    private final Map<String, Exposure> storyExposures = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Exposure>> sectorExposures = new ConcurrentHashMap<>();
    private final long storyBlockMs;
    private final long sectorBlockMs;
    private final int maxPositionsPerStory;
    private final int maxPositionsPerSector;
    private final boolean enabled;

    public StoryExposureManager() {
        this.enabled = envBoolean("STORY_EXPOSURE_ENABLED", true);
        this.storyBlockMs = envLong("STORY_EXPOSURE_BLOCK_MINUTES", 30L) * 60L * 1000L;
        this.sectorBlockMs = envLong("SECTOR_EXPOSURE_BLOCK_MINUTES", 30L) * 60L * 1000L;
        this.maxPositionsPerStory = Math.max(1, envInt("MAX_POSITIONS_PER_STORY", 1));
        this.maxPositionsPerSector = Math.max(1, envInt("MAX_POSITIONS_PER_SECTOR", 2));
    }

    public synchronized String blockReason(NewsEvent news, StrategySignal signal) {
        if (!enabled || signal == null || news == null) {
            return null;
        }

        pruneExpired(System.currentTimeMillis());

        String storyKey = storyKey(news);
        String ticker = normalize(signal.getTicker());
        if (!storyKey.isBlank()) {
            long count = storyExposures.values().stream()
                    .filter(exposure -> exposure.storyKey.equals(storyKey))
                    .count();
            Exposure existing = storyExposures.get(storyKey);
            if (existing != null && !existing.ticker.equals(ticker) && count >= maxPositionsPerStory) {
                return "Story exposure blocked duplicate article. existingTicker=" + existing.ticker + " storyKey=" + storyKey;
            }
        }

        String sectorKey = sectorKey(news, ticker);
        if (!sectorKey.isBlank()) {
            Map<String, Exposure> sector = sectorExposures.get(sectorKey);
            if (sector != null && !sector.containsKey(ticker) && sector.size() >= maxPositionsPerSector) {
                return "Sector exposure blocked. sector=" + sectorKey + " activeTickers=" + sector.keySet();
            }
        }

        return null;
    }

    public synchronized void recordFill(NewsEvent news, StrategySignal signal) {
        if (!enabled || signal == null || news == null) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneExpired(now);

        String ticker = normalize(signal.getTicker());
        String storyKey = storyKey(news);
        String sectorKey = sectorKey(news, ticker);
        Exposure exposure = new Exposure(ticker, storyKey, sectorKey, now);

        if (!storyKey.isBlank()) {
            storyExposures.put(storyKey, exposure);
        }

        if (!sectorKey.isBlank()) {
            sectorExposures.computeIfAbsent(sectorKey, key -> new ConcurrentHashMap<>())
                    .put(ticker, exposure);
        }

        System.out.println("STORY EXPOSURE RECORDED: ticker=" + ticker + " story=" + storyKey + " sector=" + sectorKey);
    }

    private void pruneExpired(long now) {
        storyExposures.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs > storyBlockMs);

        for (Iterator<Map.Entry<String, Map<String, Exposure>>> it = sectorExposures.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Map<String, Exposure>> entry = it.next();
            entry.getValue().entrySet().removeIf(child -> now - child.getValue().createdAtMs > sectorBlockMs);
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    public static String storyKey(NewsEvent news) {
        if (news == null) {
            return "";
        }

        String id = safe(news.getId()).trim();
        String headline = normalizeStoryText(news.getHeadline());
        if (!headline.isBlank()) {
            return Integer.toHexString(headline.hashCode());
        }
        if (!id.isBlank()) {
            return id.toUpperCase(Locale.ROOT);
        }
        return "";
    }

    private static String normalizeStoryText(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT);
        text = text.replaceAll("&#39;", "'");
        text = text.replaceAll("[^a-z0-9 ]", " ");
        text = text.replaceAll("\\b(nyse|nasdaq|amex|stock|shares|share|trading|today|monday|tuesday|wednesday|thursday|friday)\\b", " ");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    private static String sectorKey(NewsEvent news, String ticker) {
        String text = (safe(news == null ? null : news.getHeadline()) + " " + safe(news == null ? null : news.getContent()))
                .toLowerCase(Locale.ROOT);
        String symbol = normalize(ticker);

        if (containsAny(text, "micron", "semiconductor", "chip", "chips", "ai", "gpu", "nand", "dram") ||
                containsAny(symbol, "MU", "NVDA", "AMD", "SOXQ", "SMH", "SOXL", "CHPX", "SPMO")) {
            return "SEMICONDUCTORS";
        }
        if (containsAny(text, "biotech", "fda", "clinical", "phase 2", "phase ii", "phase 3", "phase iii", "trial")) {
            return "BIOTECH";
        }
        if (containsAny(text, "bank", "banc", "financial", "broker", "trading tools")) {
            return "FINANCIALS";
        }
        if (containsAny(text, "solar", "energy", "oil", "gas", "uranium", "nuclear")) {
            return "ENERGY";
        }
        if (containsAny(text, "airline", "freight", "shipping", "logistics", "transport")) {
            return "TRANSPORT";
        }
        return "GENERAL";
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class Exposure {
        private final String ticker;
        private final String storyKey;
        private final String sectorKey;
        private final long createdAtMs;

        private Exposure(String ticker, String storyKey, String sectorKey, long createdAtMs) {
            this.ticker = ticker;
            this.storyKey = storyKey;
            this.sectorKey = sectorKey;
            this.createdAtMs = createdAtMs;
        }
    }
}
