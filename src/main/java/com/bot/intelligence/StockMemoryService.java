package com.bot.intelligence;

import com.bot.model.Bar;
import com.bot.model.NewsEvent;
import com.bot.stream.NewsPriorityGate;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent AI memory for per-symbol market context.
 *
 * The live engine updates this on every usable bar/news item so the bot stops
 * treating each catalyst as an isolated event. The memory file is intentionally
 * simple CSV so the nightly evolution process and humans can audit it easily.
 */
public final class StockMemoryService {
    private static final StockMemoryService INSTANCE = new StockMemoryService();

    private final ConcurrentHashMap<String, StockMemoryProfile> profiles = new ConcurrentHashMap<>();
    private final Path journalPath = Path.of(System.getenv().getOrDefault("STOCK_MEMORY_PATH", "logs/stock_memory.csv"));
    private volatile long lastFlushAt = 0L;

    private StockMemoryService() {
        loadExistingMemory();
    }

    public static StockMemoryService getInstance() {
        return INSTANCE;
    }

    public StockMemoryProfile profile(String ticker) {
        String normalized = normalize(ticker);
        if (normalized.isBlank()) {
            return new StockMemoryProfile("UNKNOWN");
        }
        return profiles.computeIfAbsent(normalized, StockMemoryProfile::new);
    }


    /**
     * Creates/warms a durable profile for a symbol even before it has news.
     * This lets the predictive ranker build a real memory universe over time
     * instead of starting every session with memoryProfiles=0.
     */
    public void observeUniverseSymbol(String ticker) {
        String normalized = normalize(ticker);
        if (normalized.isBlank()) {
            return;
        }
        StockMemoryProfile profile = profile(normalized);
        if (profile.getPredictiveScore() <= 0.0) {
            profile.setPredictiveScore(0.03);
        }
        updateThesis(profile);
        flushPeriodically();
    }

    /**
     * Records that the scanner considered a symbol even if the current bar was
     * filtered out. Repeated touches keep liquid/current names from disappearing
     * from the AI world model just because they did not trigger a setup today.
     */
    public void observeScanCandidate(String ticker) {
        String normalized = normalize(ticker);
        if (normalized.isBlank()) {
            return;
        }
        StockMemoryProfile profile = profile(normalized);
        profile.setPredictiveScore(computePredictiveScore(profile));
        flushPeriodically();
    }

    public void observeBar(String ticker, Bar bar) {
        String normalized = normalize(ticker);
        if (normalized.isBlank() || bar == null || bar.close <= 0) {
            return;
        }
        StockMemoryProfile profile = profile(normalized);
        double rangeScore = bar.close > 0 ? clamp((bar.high - bar.low) / bar.close / 0.08) : 0.0;
        double volumeScore = clamp(Math.log10(Math.max(1.0, bar.volume)) / 8.0);
        double trend = bar.open > 0 ? clamp(((bar.close - bar.open) / bar.open + 0.05) / 0.10) : profile.getTrendScore();
        profile.setTrendScore(smooth(profile.getTrendScore(), trend, 0.35));
        profile.setRelativeVolumeScore(smooth(profile.getRelativeVolumeScore(), Math.max(rangeScore, volumeScore), 0.30));
        profile.setPredictiveScore(computePredictiveScore(profile));
        profile.setLastBarAt(System.currentTimeMillis());
        updateThesis(profile);
        flushPeriodically();
    }

    public void observeNews(NewsEvent news) {
        if (news == null) {
            return;
        }
        String normalized = normalize(news.getTicker());
        if (normalized.isBlank()) {
            return;
        }
        StockMemoryProfile profile = profile(normalized);
        profile.incrementNewsCount();
        profile.setLastNewsAt(System.currentTimeMillis());
        profile.setLastHeadline(news.getHeadline());

        double catalystScore = clamp(news.getCatalystScore());
        double priorityScore = NewsPriorityGate.isHighPriorityCatalyst(news) ? 1.0 : NewsPriorityGate.sourcePriority(news) / 100.0;
        if (NewsPriorityGate.isHighPriorityCatalyst(news)) {
            profile.incrementHighPriorityCatalystCount();
        }
        profile.setNewsQualityScore(smooth(profile.getNewsQualityScore(), Math.max(catalystScore, priorityScore), 0.45));
        profile.setCatalystSensitivityScore(smooth(profile.getCatalystSensitivityScore(), catalystScore, 0.30));
        profile.setPredictiveScore(computePredictiveScore(profile));
        updateThesis(profile);
        flushPeriodically();
    }

    public List<String> topPredictiveTickers(int limit) {
        int capped = Math.max(1, limit);
        List<StockMemoryProfile> ordered = new ArrayList<>(profiles.values());
        ordered.sort((a, b) -> Double.compare(memoryEnhancedScore(b), memoryEnhancedScore(a)));
        List<String> tickers = new ArrayList<>();
        long staleCutoff = System.currentTimeMillis() - envLong("STOCK_MEMORY_ACTIVE_TTL_MS", 24L * 60L * 60L * 1000L);
        for (StockMemoryProfile profile : ordered) {
            if (tickers.size() >= capped) break;
            if (profile.getTicker() == null || profile.getTicker().isBlank()) continue;
            if (profile.getLastUpdatedAt() < staleCutoff && profile.blendedOpportunityScore() < 0.35) continue;
            tickers.add(profile.getTicker());
        }
        return tickers;
    }

    public List<StockMemoryProfile> topProfiles(int limit) {
        List<StockMemoryProfile> ordered = new ArrayList<>(profiles.values());
        ordered.sort((a, b) -> Double.compare(memoryEnhancedScore(b), memoryEnhancedScore(a)));
        return ordered.subList(0, Math.min(Math.max(0, limit), ordered.size()));
    }

    public int size() {
        return profiles.size();
    }

    public void flushNow() {
        try {
            Files.createDirectories(journalPath.getParent() == null ? Path.of(".") : journalPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("ticker,trendScore,relativeVolumeScore,catalystSensitivityScore,newsQualityScore,predictiveScore,lastUpdatedAt,lastNewsAt,lastBarAt,newsCount,highPriorityCatalystCount,preferredStrategy,thesis,lastHeadline");
                writer.newLine();
                for (StockMemoryProfile profile : topProfiles(Math.max(1, envInt("STOCK_MEMORY_MAX_FLUSH_ROWS", 2000)))) {
                    writer.write(csv(profile.getTicker()) + "," +
                            fmt(profile.getTrendScore()) + "," +
                            fmt(profile.getRelativeVolumeScore()) + "," +
                            fmt(profile.getCatalystSensitivityScore()) + "," +
                            fmt(profile.getNewsQualityScore()) + "," +
                            fmt(profile.getPredictiveScore()) + "," +
                            profile.getLastUpdatedAt() + "," +
                            profile.getLastNewsAt() + "," +
                            profile.getLastBarAt() + "," +
                            profile.getNewsCount() + "," +
                            profile.getHighPriorityCatalystCount() + "," +
                            csv(profile.getPreferredStrategy()) + "," +
                            csv(profile.getThesis()) + "," +
                            csv(profile.getLastHeadline()));
                    writer.newLine();
                }
            }
            lastFlushAt = System.currentTimeMillis();
        } catch (Exception e) {
            System.err.println("STOCK MEMORY FLUSH ERROR: " + e.getMessage());
        }
    }

    private void flushPeriodically() {
        long now = System.currentTimeMillis();
        if (now - lastFlushAt >= envLong("STOCK_MEMORY_FLUSH_INTERVAL_MS", 60_000L)) {
            flushNow();
        }
    }

    private void loadExistingMemory() {
        if (!Files.exists(journalPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(journalPath);
            for (int i = 1; i < lines.size(); i++) {
                List<String> cells = parseCsvLine(lines.get(i));
                if (cells.size() < 6) continue;
                StockMemoryProfile profile = profile(unquote(cells.get(0)));
                profile.setTrendScore(parseDouble(getCell(cells, 1)));
                profile.setRelativeVolumeScore(parseDouble(getCell(cells, 2)));
                profile.setCatalystSensitivityScore(parseDouble(getCell(cells, 3)));
                profile.setNewsQualityScore(parseDouble(getCell(cells, 4)));
                profile.setPredictiveScore(parseDouble(getCell(cells, 5)));
                profile.setLastUpdatedAt(parseLong(getCell(cells, 6)));
                profile.setLastNewsAt(parseLong(getCell(cells, 7)));
                profile.setLastBarAt(parseLong(getCell(cells, 8)));
                profile.setNewsCount(parseInt(getCell(cells, 9)));
                profile.setHighPriorityCatalystCount(parseInt(getCell(cells, 10)));
                if (cells.size() > 11) profile.setPreferredStrategy(unquote(getCell(cells, 11)));
                if (cells.size() > 12) profile.setThesis(unquote(getCell(cells, 12)));
                if (cells.size() > 13) profile.setLastHeadline(unquote(getCell(cells, 13)));
            }
            System.out.println("STOCK MEMORY LOADED: profiles=" + profiles.size() + " path=" + journalPath);
        } catch (Exception e) {
            System.err.println("STOCK MEMORY LOAD ERROR: " + e.getMessage());
        }
    }

    private void updateThesis(StockMemoryProfile profile) {
        String strategy;
        if (profile.getCatalystSensitivityScore() >= 0.70) {
            strategy = "CATALYST_MOMENTUM";
        } else if (profile.getTrendScore() >= 0.65 && profile.getRelativeVolumeScore() >= 0.55) {
            strategy = "RELATIVE_STRENGTH_BREAKOUT";
        } else if (profile.getRelativeVolumeScore() >= 0.70) {
            strategy = "VOLATILITY_EXPANSION";
        } else if (profile.getTrendScore() <= 0.30) {
            strategy = "SHORT_OR_FADE_ONLY";
        } else {
            strategy = "WATCH_FOR_CONFIRMATION";
        }
        profile.setPreferredStrategy(strategy);
        OpportunityMemoryProfile opportunity = OpportunityMemoryService.getInstance().profile(profile.getTicker());
        WorldModelSnapshot world = WorldModelAgent.getInstance().currentSnapshot();
        String personality = opportunity == null ? "unknown" : opportunity.compactSummary();
        String worldSummary = world == null ? "world=unknown" : world.compactSummary();
        profile.setThesis("score=" + fmt(memoryEnhancedScore(profile)) +
                " baseScore=" + fmt(profile.blendedOpportunityScore()) +
                " strategy=" + strategy +
                " trend=" + fmt(profile.getTrendScore()) +
                " rvol=" + fmt(profile.getRelativeVolumeScore()) +
                " catalyst=" + fmt(profile.getCatalystSensitivityScore()) +
                " personality={" + personality + "}" +
                " world={" + worldSummary + "}");
    }

    private static double memoryEnhancedScore(StockMemoryProfile profile) {
        if (profile == null) return 0.0;
        double base = profile.blendedOpportunityScore();
        double opportunityBoost = OpportunityMemoryService.getInstance().boostFor(profile.getTicker(), profile.getPreferredStrategy());
        double worldBoost = WorldModelAgent.getInstance().worldModelBoostFor(profile.getTicker());
        return clamp(base + opportunityBoost + worldBoost);
    }

    private double computePredictiveScore(StockMemoryProfile profile) {
        return clamp(profile.getTrendScore() * 0.30 + profile.getRelativeVolumeScore() * 0.30 + profile.getCatalystSensitivityScore() * 0.25 + profile.getNewsQualityScore() * 0.15);
    }

    private static double smooth(double oldValue, double newValue, double alpha) {
        if (oldValue <= 0) return clamp(newValue);
        return clamp(oldValue * (1.0 - alpha) + newValue * alpha);
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1).replace("\"\"", "\"");
        }
        return v;
    }

    private static double parseDouble(String value) {
        try { return Double.parseDouble(value.trim()); } catch (Exception e) { return 0.0; }
    }


    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        if (line == null) {
            return cells;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private static String getCell(List<String> cells, int index) {
        return cells == null || index < 0 || index >= cells.size() ? "" : cells.get(index);
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); } catch (Exception e) { return 0L; }
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return 0; }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
