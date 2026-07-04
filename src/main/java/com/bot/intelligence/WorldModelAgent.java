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
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared live market world model.
 *
 * This service continuously summarizes broad market state for every strategy and
 * LLM-style agent: regime, news/catalyst heat, small-cap leadership, parabolic
 * heat, provider diversity, and sector heat. It does not buy or sell anything.
 */
public final class WorldModelAgent {
    private static final WorldModelAgent INSTANCE = new WorldModelAgent();

    private final ConcurrentHashMap<String, Deque<Bar>> barsByTicker = new ConcurrentHashMap<>();
    private final LiveTechnicalFeatureStore liveFeatureStore = LiveTechnicalFeatureStore.getInstance();
    private final MarketFeatureBus marketFeatureBus = MarketFeatureBus.getInstance();
    private final MarketStateDatabase marketStateDatabase = MarketStateDatabase.getInstance();
    private final MarketMemoryEmbeddingService marketMemoryEmbeddingService = MarketMemoryEmbeddingService.getInstance();
    private final MarketSimilaritySearchEngine marketSimilaritySearchEngine = MarketSimilaritySearchEngine.getInstance();
    private final ConcurrentHashMap<String, Long> recentNewsByTicker = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> recentCatalystByTicker = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentSignalByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> sectorHeat = new ConcurrentHashMap<>();
    private final Path journalPath = Path.of(System.getenv().getOrDefault("WORLD_MODEL_JOURNAL", "logs/world_model.csv"));

    private final int maxBarsPerTicker = envInt("WORLD_MODEL_MAX_BARS_PER_TICKER", 90);
    private final long activeLookbackMs = envLong("WORLD_MODEL_ACTIVE_LOOKBACK_MS", 20L * 60L * 1000L);
    private final long logIntervalMs = envLong("WORLD_MODEL_LOG_INTERVAL_MS", 120_000L);
    private final boolean journalingEnabled = !"false".equalsIgnoreCase(System.getenv().getOrDefault("WORLD_MODEL_JOURNALING_ENABLED", "true"));

    private volatile boolean started = false;
    private volatile long lastLoggedAt = 0L;
    private volatile long lastJournaledAt = 0L;
    private volatile WorldModelSnapshot snapshot = WorldModelSnapshot.unknown();

    private WorldModelAgent() {
    }

    public static WorldModelAgent getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        System.out.println("WORLD MODEL AGENT STARTED: lookbackMs=" + activeLookbackMs +
                " journal=" + journalPath +
                " maxBarsPerTicker=" + maxBarsPerTicker);
        journalHeaderIfNeeded();
        liveFeatureStore.start();
        marketFeatureBus.start();
        marketStateDatabase.start();
        marketMemoryEmbeddingService.start();
        marketSimilaritySearchEngine.start();
    }

    public WorldModelSnapshot currentSnapshot() {
        return snapshot;
    }

    public void observeSignal(MarketIntelligenceSignal signal) {
        if (signal == null) return;
        start();
        recentSignalByProvider.put(signal.getProvider(), System.currentTimeMillis());
        String ticker = normalize(signal.getTicker());
        if (!ticker.isBlank()) {
            recentNewsByTicker.put(ticker, System.currentTimeMillis());
            recentCatalystByTicker.put(ticker, Math.max(recentCatalystByTicker.getOrDefault(ticker, 0.0), signal.getPriority()));
            String sector = roughSector(ticker, signal.getHeadline() + " " + signal.getContent());
            sectorHeat.merge(sector, Math.max(0.05, signal.getPriority()), (oldValue, newValue) -> smooth(oldValue, newValue, 0.25));
        }
        recompute("signal:" + signal.getType());
    }

    public void observeNews(NewsEvent news) {
        if (news == null) return;
        start();
        String ticker = normalize(news.getTicker());
        if (!ticker.isBlank()) {
            recentNewsByTicker.put(ticker, System.currentTimeMillis());
            double catalyst = clamp(news.getCatalystScore());
            recentCatalystByTicker.put(ticker, Math.max(recentCatalystByTicker.getOrDefault(ticker, 0.0), catalyst));
            String sector = roughSector(ticker, (news.getHeadline() == null ? "" : news.getHeadline()) + " " + (news.getContent() == null ? "" : news.getContent()));
            sectorHeat.merge(sector, Math.max(0.05, catalyst), (oldValue, newValue) -> smooth(oldValue, newValue, 0.25));
        }
        recompute("news");
    }

    public void observeBar(String ticker, Bar bar) {
        String normalized = normalize(ticker);
        if (normalized.isBlank() || bar == null || bar.close <= 0.0) return;
        start();
        Deque<Bar> bars = barsByTicker.computeIfAbsent(normalized, ignored -> new ArrayDeque<>());
        synchronized (bars) {
            bars.addLast(bar);
            while (bars.size() > maxBarsPerTicker) bars.removeFirst();
        }
        marketFeatureBus.publishBar("WORLD_MODEL_BAR", normalized, bar);
        recompute("bar");
    }

    public double worldModelBoostFor(String ticker) {
        String normalized = normalize(ticker);
        if (normalized.isBlank()) return 0.0;
        WorldModelSnapshot s = snapshot;
        double boost = 0.0;
        if (s.getRegime() == MarketRegime.STRONG_UPTREND || s.getRegime() == MarketRegime.UPTREND) {
            boost += 0.04;
        }
        if (s.getParabolicHeatScore() >= 0.65) {
            boost += 0.03;
        }
        if (recentNewsByTicker.containsKey(normalized)) {
            boost += Math.min(0.05, recentCatalystByTicker.getOrDefault(normalized, 0.0) * 0.05);
        }
        return clamp(boost);
    }

    private void recompute(String trigger) {
        long now = System.currentTimeMillis();
        pruneOld(now);

        MarketRegimeSnapshot regimeSnapshot = MarketRegimeEngine.getInstance().currentSnapshot();
        double trend = normalizeSignedTrend(regimeSnapshot.getTrendScore());
        double volatility = clamp(regimeSnapshot.getVolatilityScore() / 0.025);
        double liquidity = clamp(Math.log10(Math.max(1.0, regimeSnapshot.getLiquidityScore())) / 9.0);

        int activeNews = 0;
        double catalystHeat = 0.0;
        for (Map.Entry<String, Long> entry : recentNewsByTicker.entrySet()) {
            if (now - entry.getValue() <= activeLookbackMs) {
                activeNews++;
                catalystHeat = Math.max(catalystHeat, recentCatalystByTicker.getOrDefault(entry.getKey(), 0.0));
            }
        }
        double newsFlow = clamp(activeNews / 30.0);
        EvidenceFusionEngine.FusionMarketSnapshot fusion = EvidenceFusionEngine.getInstance().marketSnapshot(activeLookbackMs);
        LiveTechnicalFeatureStore.MarketTechnicalSummary technicalSummary = liveFeatureStore.summarize(activeLookbackMs);
        catalystHeat = Math.max(catalystHeat, fusion.catalystHeat());
        double liveTechnicalScore = technicalSummary.technicalScore;
        double parabolicHeat = Math.max(Math.max(computeParabolicHeat(), fusion.parabolicHeat()), technicalSummary.parabolicScore);
        double smallCapLeadership = computeSmallCapLeadership();
        double largeCapLeadership = computeLargeCapLeadership();
        liquidity = Math.max(Math.max(liquidity, fusion.liquidityScore()), technicalSummary.liquidityScore);
        double fusedTechnicalScore = Math.max(fusion.technicalScore(), liveTechnicalScore);
        if (fusedTechnicalScore >= 0.55 && trend < 0.50) {
            trend = Math.min(1.0, trend + (fusedTechnicalScore - 0.50) * 0.20);
        }
        if (technicalSummary.trendScore > 0.55) {
            trend = Math.max(trend, technicalSummary.trendScore);
        }

        Set<String> activeProviders = new LinkedHashSet<>();
        for (Map.Entry<String, Long> entry : recentSignalByProvider.entrySet()) {
            if (now - entry.getValue() <= activeLookbackMs) activeProviders.add(entry.getKey());
        }
        activeProviders.addAll(fusion.activeProviders());
        if (technicalSummary.activeTickers > 0) activeProviders.add("LIVE_TECHNICAL_FEATURE_STORE");
        double dataConfidence = Math.max(clamp(activeProviders.size() / 5.0), fusion.dataConfidence());

        String summary = buildSummary(regimeSnapshot.getRegime(), trend, volatility, liquidity,
                smallCapLeadership, largeCapLeadership, catalystHeat, newsFlow, parabolicHeat, dataConfidence, activeNews, activeProviders.size()) +
                "; " + fusion.summary() + "; " + technicalSummary.summary() + "; " + marketFeatureBus.healthSummary();

        snapshot = new WorldModelSnapshot(
                regimeSnapshot.getRegime(),
                trend,
                volatility,
                liquidity,
                smallCapLeadership,
                largeCapLeadership,
                catalystHeat,
                newsFlow,
                parabolicHeat,
                dataConfidence,
                now,
                summary,
                copyTopSectorHeat()
        );
        marketStateDatabase.record(snapshot, technicalSummary);
        marketMemoryEmbeddingService.record(snapshot, technicalSummary);
        marketSimilaritySearchEngine.maybeSearchAndJournal(snapshot, technicalSummary);
        marketFeatureBus.recordWorldModelUpdate();
        maybeLog(trigger);
        maybeJournal();
    }

    private void pruneOld(long now) {
        for (Map.Entry<String, Long> entry : recentNewsByTicker.entrySet()) {
            if (now - entry.getValue() > activeLookbackMs * 2L) {
                recentNewsByTicker.remove(entry.getKey(), entry.getValue());
                recentCatalystByTicker.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, Long> entry : recentSignalByProvider.entrySet()) {
            if (now - entry.getValue() > activeLookbackMs * 2L) {
                recentSignalByProvider.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private double computeParabolicHeat() {
        double strongest = 0.0;
        int active = 0;
        for (Map.Entry<String, Deque<Bar>> entry : barsByTicker.entrySet()) {
            List<Bar> bars = copyBars(entry.getValue());
            if (bars.size() < 5) continue;
            Bar latest = bars.get(bars.size() - 1);
            Bar old = bars.get(Math.max(0, bars.size() - 5));
            if (latest.close <= 0 || old.close <= 0) continue;
            double move = Math.max(0.0, (latest.close - old.close) / old.close);
            double range = latest.close > 0 ? Math.max(0.0, (latest.high - latest.low) / latest.close) : 0.0;
            double volume = clamp(Math.log10(Math.max(1.0, latest.volume)) / 8.0);
            double score = clamp(move / 0.12 * 0.55 + range / 0.08 * 0.25 + volume * 0.20);
            if (score >= 0.40) active++;
            strongest = Math.max(strongest, score);
        }
        return clamp(strongest * 0.75 + Math.min(0.25, active / 20.0));
    }

    private double computeSmallCapLeadership() {
        return computeLeadershipScore(true);
    }

    private double computeLargeCapLeadership() {
        return computeLeadershipScore(false);
    }

    private double computeLeadershipScore(boolean smallCapProxy) {
        double sum = 0.0;
        int count = 0;
        for (Map.Entry<String, Deque<Bar>> entry : barsByTicker.entrySet()) {
            String ticker = entry.getKey();
            boolean isLarge = isLargeCapProxy(ticker);
            if (smallCapProxy == isLarge) continue;
            List<Bar> bars = copyBars(entry.getValue());
            if (bars.size() < 4) continue;
            Bar latest = bars.get(bars.size() - 1);
            Bar old = bars.get(Math.max(0, bars.size() - 4));
            if (latest.close <= 0 || old.close <= 0) continue;
            double move = (latest.close - old.close) / old.close;
            double rvolProxy = clamp(Math.log10(Math.max(1.0, latest.volume)) / 8.0);
            sum += clamp(((move + 0.03) / 0.08) * 0.60 + rvolProxy * 0.40);
            count++;
        }
        return count == 0 ? 0.0 : clamp(sum / count);
    }

    private List<Bar> copyBars(Deque<Bar> deque) {
        if (deque == null) return Collections.emptyList();
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    private Map<String, Double> copyTopSectorHeat() {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(sectorHeat.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Double> result = new LinkedHashMap<>();
        int max = Math.min(8, entries.size());
        for (int i = 0; i < max; i++) {
            result.put(entries.get(i).getKey(), clamp(entries.get(i).getValue()));
        }
        return result;
    }

    private void maybeLog(String trigger) {
        long now = System.currentTimeMillis();
        if (now - lastLoggedAt < logIntervalMs) return;
        lastLoggedAt = now;
        System.out.println("WORLD MODEL UPDATE: trigger=" + trigger + " " + snapshot.compactSummary());
    }

    private void maybeJournal() {
        if (!journalingEnabled) return;
        long now = System.currentTimeMillis();
        if (now - lastJournaledAt < envLong("WORLD_MODEL_JOURNAL_INTERVAL_MS", 60_000L)) return;
        lastJournaledAt = now;
        try {
            Files.createDirectories(journalPath.getParent() == null ? Path.of(".") : journalPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                WorldModelSnapshot s = snapshot;
                writer.write(now + "," +
                        csv(s.getRegime().name()) + "," +
                        fmt(s.getTrendScore()) + "," +
                        fmt(s.getVolatilityScore()) + "," +
                        fmt(s.getLiquidityScore()) + "," +
                        fmt(s.getSmallCapLeadershipScore()) + "," +
                        fmt(s.getLargeCapLeadershipScore()) + "," +
                        fmt(s.getCatalystHeatScore()) + "," +
                        fmt(s.getNewsFlowScore()) + "," +
                        fmt(s.getParabolicHeatScore()) + "," +
                        fmt(s.getDataConfidenceScore()) + "," +
                        csv(s.getSummary()));
                writer.newLine();
            }
        } catch (Exception e) {
            System.err.println("WORLD MODEL JOURNAL ERROR: " + e.getMessage());
        }
    }

    private void journalHeaderIfNeeded() {
        if (!journalingEnabled) return;
        try {
            Files.createDirectories(journalPath.getParent() == null ? Path.of(".") : journalPath.getParent());
            if (!Files.exists(journalPath) || Files.size(journalPath) == 0L) {
                try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write("timestamp,regime,trendScore,volatilityScore,liquidityScore,smallCapLeadershipScore,largeCapLeadershipScore,catalystHeatScore,newsFlowScore,parabolicHeatScore,dataConfidenceScore,summary");
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            System.err.println("WORLD MODEL HEADER ERROR: " + e.getMessage());
        }
    }

    private static String buildSummary(
            MarketRegime regime,
            double trend,
            double volatility,
            double liquidity,
            double smallCap,
            double largeCap,
            double catalystHeat,
            double newsFlow,
            double parabolicHeat,
            double dataConfidence,
            int activeNews,
            int providers
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("regime ").append(regime);
        if (smallCap > largeCap + 0.12) sb.append("; small caps leading");
        else if (largeCap > smallCap + 0.12) sb.append("; large caps leading");
        else sb.append("; leadership mixed");
        if (parabolicHeat >= 0.65) sb.append("; high parabolic heat");
        if (catalystHeat >= 0.70) sb.append("; strong catalyst tape");
        if (newsFlow >= 0.60) sb.append("; heavy news flow");
        if (volatility >= 0.65) sb.append("; volatility elevated");
        if (dataConfidence < 0.25) sb.append("; low external provider diversity");
        sb.append("; activeNews=").append(activeNews).append(" providers=").append(providers);
        return sb.toString();
    }

    private static String roughSector(String ticker, String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String symbol = normalize(ticker);
        if (t.contains("fda") || t.contains("clinical") || t.contains("trial") || t.contains("pdufa") || t.contains("biotech")) return "BIOTECH";
        if (t.contains("ai") || t.contains("semiconductor") || t.contains("chip") || "NVDA".equals(symbol) || "SMCI".equals(symbol) || "AMD".equals(symbol) || "MU".equals(symbol)) return "AI_SEMICONDUCTOR";
        if (t.contains("crypto") || t.contains("bitcoin") || t.contains("ethereum") || "COIN".equals(symbol) || "MSTR".equals(symbol)) return "CRYPTO_RELATED";
        if (t.contains("bank") || t.contains("financial") || "JPM".equals(symbol) || "BAC".equals(symbol)) return "FINANCIALS";
        if (t.contains("energy") || t.contains("oil") || t.contains("gas")) return "ENERGY";
        if (t.contains("ev") || t.contains("vehicle") || "TSLA".equals(symbol)) return "EV_AUTO";
        return "GENERAL";
    }

    private static boolean isLargeCapProxy(String ticker) {
        return "SPY".equals(ticker) || "QQQ".equals(ticker) || "AAPL".equals(ticker) || "MSFT".equals(ticker) ||
                "NVDA".equals(ticker) || "AMZN".equals(ticker) || "META".equals(ticker) || "GOOG".equals(ticker) ||
                "GOOGL".equals(ticker) || "TSLA".equals(ticker) || "AVGO".equals(ticker) || "BRK.B".equals(ticker);
    }

    private static double normalizeSignedTrend(double trend) {
        return clamp((trend + 0.035) / 0.070);
    }

    private static double smooth(double oldValue, double newValue, double alpha) {
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
