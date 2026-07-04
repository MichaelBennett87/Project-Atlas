package com.bot.intelligence;

import com.bot.intelligence.bus.MarketIntelligenceSignal;
import com.bot.intelligence.bus.MarketIntelligenceSignalType;
import com.bot.model.Bar;
import com.bot.model.NewsEvent;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent ticker behavior memory.
 *
 * This is the "personality" layer: which tickers tend to make violent moves,
 * pull back cleanly, produce second legs, and prefer parabolic/news/pre-catalyst
 * tactics. It is designed to feed existing agents without becoming a strategy.
 */
public final class OpportunityMemoryService {
    private static final OpportunityMemoryService INSTANCE = new OpportunityMemoryService();

    private final ConcurrentHashMap<String, OpportunityMemoryProfile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Bar>> recentBars = new ConcurrentHashMap<>();
    private final Path path = Path.of(System.getenv().getOrDefault("OPPORTUNITY_MEMORY_PATH", "logs/opportunity_memory.csv"));
    private final int maxBars = envInt("OPPORTUNITY_MEMORY_MAX_BARS", 60);
    private volatile long lastFlushAt = 0L;
    private volatile boolean started = false;

    private OpportunityMemoryService() {
        load();
    }

    public static OpportunityMemoryService getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        System.out.println("OPPORTUNITY MEMORY STARTED: profiles=" + profiles.size() + " path=" + path);
    }

    public OpportunityMemoryProfile profile(String ticker) {
        String normalized = normalize(ticker);
        if (normalized.isBlank()) return new OpportunityMemoryProfile("UNKNOWN");
        return profiles.computeIfAbsent(normalized, OpportunityMemoryProfile::new);
    }

    public void observeSignal(MarketIntelligenceSignal signal) {
        if (signal == null || signal.getTicker() == null || signal.getTicker().isBlank()) return;
        start();
        OpportunityMemoryProfile profile = profile(signal.getTicker());
        profile.incrementObservations();
        if (signal.getType() == MarketIntelligenceSignalType.NEWS || signal.getType() == MarketIntelligenceSignalType.PRESS_RELEASE || signal.getType() == MarketIntelligenceSignalType.SEC_FILING || signal.getType() == MarketIntelligenceSignalType.CATALYST_CALENDAR) {
            profile.incrementCatalystObservations();
            profile.setAverageNewsMoveScore(smooth(profile.getAverageNewsMoveScore(), signal.getPriority(), 0.30));
        }
        profile.setStrategySuccessScore(smooth(profile.getStrategySuccessScore(), signal.getConfidence(), 0.12));
        updatePersonality(profile);
        flushPeriodically();
    }

    public void observeNews(NewsEvent news) {
        if (news == null || news.getTicker() == null || news.getTicker().isBlank()) return;
        start();
        OpportunityMemoryProfile profile = profile(news.getTicker());
        profile.incrementObservations();
        profile.incrementCatalystObservations();
        profile.setAverageNewsMoveScore(smooth(profile.getAverageNewsMoveScore(), clamp(news.getCatalystScore()), 0.35));
        updatePersonality(profile);
        flushPeriodically();
    }

    public void observeBar(String ticker, Bar bar) {
        String normalized = normalize(ticker);
        if (normalized.isBlank() || bar == null || bar.close <= 0.0) return;
        start();
        Deque<Bar> bars = recentBars.computeIfAbsent(normalized, ignored -> new ArrayDeque<>());
        synchronized (bars) {
            bars.addLast(bar);
            while (bars.size() > maxBars) bars.removeFirst();
        }
        OpportunityMemoryProfile profile = profile(normalized);
        profile.incrementObservations();
        updateFromBars(profile, copyBars(bars));
        updatePersonality(profile);
        flushPeriodically();
    }

    public double boostFor(String ticker, String strategyName) {
        OpportunityMemoryProfile profile = profiles.get(normalize(ticker));
        if (profile == null) return 0.0;
        double boost = profile.opportunityScore() * 0.08;
        String strategy = strategyName == null ? "" : strategyName.toUpperCase(Locale.ROOT);
        if (!strategy.isBlank() && profile.getBestStrategy().toUpperCase(Locale.ROOT).contains(strategyToken(strategy))) {
            boost += 0.035;
        }
        return clamp(boost);
    }

    public List<OpportunityMemoryProfile> topProfiles(int limit) {
        List<OpportunityMemoryProfile> ordered = new ArrayList<>(profiles.values());
        ordered.sort(Comparator.comparingDouble(OpportunityMemoryProfile::opportunityScore).reversed());
        return ordered.subList(0, Math.min(Math.max(0, limit), ordered.size()));
    }

    public int size() {
        return profiles.size();
    }

    public void flushNow() {
        try {
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("ticker,observations,catalystObservations,parabolicObservations,averageNewsMoveScore,averagePullbackScore,averageSecondLegScore,averageVolatilityScore,averageLiquidityScore,strategySuccessScore,preferredHoldMillis,lastObservedAt,bestStrategy,personality");
                writer.newLine();
                for (OpportunityMemoryProfile p : topProfiles(envInt("OPPORTUNITY_MEMORY_MAX_FLUSH_ROWS", 3000))) {
                    writer.write(csv(p.getTicker()) + "," +
                            p.getObservations() + "," +
                            p.getCatalystObservations() + "," +
                            p.getParabolicObservations() + "," +
                            fmt(p.getAverageNewsMoveScore()) + "," +
                            fmt(p.getAveragePullbackScore()) + "," +
                            fmt(p.getAverageSecondLegScore()) + "," +
                            fmt(p.getAverageVolatilityScore()) + "," +
                            fmt(p.getAverageLiquidityScore()) + "," +
                            fmt(p.getStrategySuccessScore()) + "," +
                            p.getPreferredHoldMillis() + "," +
                            p.getLastObservedAt() + "," +
                            csv(p.getBestStrategy()) + "," +
                            csv(p.getPersonality()));
                    writer.newLine();
                }
            }
            lastFlushAt = System.currentTimeMillis();
        } catch (Exception e) {
            System.err.println("OPPORTUNITY MEMORY FLUSH ERROR: " + e.getMessage());
        }
    }

    private void updateFromBars(OpportunityMemoryProfile profile, List<Bar> bars) {
        if (profile == null || bars == null || bars.size() < 4) return;
        Bar latest = bars.get(bars.size() - 1);
        Bar threeBack = bars.get(Math.max(0, bars.size() - 4));
        Bar prior = bars.get(Math.max(0, bars.size() - 2));
        if (latest.close <= 0 || threeBack.close <= 0 || prior.close <= 0) return;
        double move = (latest.close - threeBack.close) / threeBack.close;
        double pullback = latest.high > 0 ? Math.max(0.0, (latest.high - latest.close) / latest.high) : 0.0;
        double secondLeg = Math.max(0.0, (latest.close - prior.close) / prior.close);
        double range = latest.close > 0 ? Math.max(0.0, (latest.high - latest.low) / latest.close) : 0.0;
        double liquidity = clamp(Math.log10(Math.max(1.0, latest.volume)) / 8.0);
        profile.setAverageVolatilityScore(smooth(profile.getAverageVolatilityScore(), clamp(range / 0.10), 0.22));
        profile.setAverageLiquidityScore(smooth(profile.getAverageLiquidityScore(), liquidity, 0.18));
        if (move >= 0.08 || range >= 0.06) {
            profile.incrementParabolicObservations();
            profile.setAverageNewsMoveScore(smooth(profile.getAverageNewsMoveScore(), clamp(move / 0.25), 0.18));
        }
        profile.setAveragePullbackScore(smooth(profile.getAveragePullbackScore(), clamp(pullback / 0.12), 0.20));
        profile.setAverageSecondLegScore(smooth(profile.getAverageSecondLegScore(), clamp(secondLeg / 0.10), 0.20));
        long hold = estimateHoldMillis(profile, move, range);
        profile.setPreferredHoldMillis(hold);
    }

    private void updatePersonality(OpportunityMemoryProfile p) {
        String best;
        if (p.getAverageNewsMoveScore() >= 0.62 && p.getCatalystObservations() >= 2) {
            best = "CATALYST_MOMENTUM";
        } else if (p.getAverageVolatilityScore() >= 0.65 && p.getAverageSecondLegScore() >= 0.45) {
            best = "PARABOLIC_CONTINUATION";
        } else if (p.getAveragePullbackScore() >= 0.62 && p.getAverageSecondLegScore() < 0.30) {
            best = "PARABOLIC_EXHAUSTION_OR_FADE";
        } else if (p.getAverageLiquidityScore() >= 0.55 && p.getAverageVolatilityScore() >= 0.45) {
            best = "VWAP_OR_RANGE_EXPANSION";
        } else {
            best = "WATCH_FOR_CONFIRMATION";
        }
        p.setBestStrategy(best);
        p.setStrategySuccessScore(smooth(p.getStrategySuccessScore(), p.opportunityScore(), 0.10));
        p.setPersonality("best=" + best +
                " newsMove=" + fmt(p.getAverageNewsMoveScore()) +
                " pullback=" + fmt(p.getAveragePullbackScore()) +
                " secondLeg=" + fmt(p.getAverageSecondLegScore()) +
                " volatility=" + fmt(p.getAverageVolatilityScore()) +
                " liquidity=" + fmt(p.getAverageLiquidityScore()) +
                " observations=" + p.getObservations());
    }

    private long estimateHoldMillis(OpportunityMemoryProfile p, double move, double range) {
        if (p.getBestStrategy().contains("PARABOLIC")) return 12L * 60L * 1000L;
        if (move >= 0.10 || range >= 0.08) return 18L * 60L * 1000L;
        if (p.getAverageNewsMoveScore() >= 0.60) return 45L * 60L * 1000L;
        return 30L * 60L * 1000L;
    }

    private void flushPeriodically() {
        long now = System.currentTimeMillis();
        if (now - lastFlushAt >= envLong("OPPORTUNITY_MEMORY_FLUSH_INTERVAL_MS", 60_000L)) flushNow();
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 1; i < lines.size(); i++) {
                List<String> cells = parseCsvLine(lines.get(i));
                if (cells.size() < 10) continue;
                OpportunityMemoryProfile p = profile(unquote(get(cells, 0)));
                p.setObservations(parseInt(get(cells, 1)));
                p.setCatalystObservations(parseInt(get(cells, 2)));
                p.setParabolicObservations(parseInt(get(cells, 3)));
                p.setAverageNewsMoveScore(parseDouble(get(cells, 4)));
                p.setAveragePullbackScore(parseDouble(get(cells, 5)));
                p.setAverageSecondLegScore(parseDouble(get(cells, 6)));
                p.setAverageVolatilityScore(parseDouble(get(cells, 7)));
                p.setAverageLiquidityScore(parseDouble(get(cells, 8)));
                p.setStrategySuccessScore(parseDouble(get(cells, 9)));
                p.setPreferredHoldMillis(parseLong(get(cells, 10), 30L * 60L * 1000L));
                p.setLastObservedAt(parseLong(get(cells, 11), System.currentTimeMillis()));
                p.setBestStrategy(unquote(get(cells, 12)));
                p.setPersonality(unquote(get(cells, 13)));
            }
            System.out.println("OPPORTUNITY MEMORY LOADED: profiles=" + profiles.size() + " path=" + path);
        } catch (Exception e) {
            System.err.println("OPPORTUNITY MEMORY LOAD ERROR: " + e.getMessage());
        }
    }

    private List<Bar> copyBars(Deque<Bar> deque) {
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    private static String strategyToken(String strategy) {
        if (strategy.contains("PARABOLIC")) return "PARABOLIC";
        if (strategy.contains("CATALYST") || strategy.contains("NEWS") || strategy.contains("FDA") || strategy.contains("EARNINGS")) return "CATALYST";
        if (strategy.contains("VWAP")) return "VWAP";
        if (strategy.contains("RANGE")) return "RANGE";
        return strategy;
    }

    private static double smooth(double oldValue, double newValue, double alpha) {
        if (oldValue <= 0.0) return clamp(newValue);
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

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        if (line == null) return cells;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    current.append('\"');
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

    private static String get(List<String> cells, int index) {
        return cells == null || index < 0 || index >= cells.size() ? "" : cells.get(index);
    }

    private static double parseDouble(String value) {
        try { return Double.parseDouble(value.trim()); } catch (Exception e) { return 0.0; }
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return 0; }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value.trim()); } catch (Exception e) { return fallback; }
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
