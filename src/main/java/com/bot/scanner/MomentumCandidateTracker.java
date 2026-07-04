package com.bot.scanner;

import com.bot.model.Bar;
import com.bot.stream.AlpacaSymbolFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived catalyst/momentum watch pool.
 *
 * A true momentum setup often starts with a catalyst before the tape has enough
 * bars to pass the normal ignition gate. The old pipeline rejected those names
 * immediately from a single startup bar. This tracker keeps strong catalysts
 * alive for a few minutes, feeds every new bar into discovery, and only allows
 * execution routing once live momentum actually starts to develop.
 */
public final class MomentumCandidateTracker {

    private static final MomentumCandidateTracker INSTANCE = new MomentumCandidateTracker();

    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final double minCatalystScore;
    private final double minHighPriorityScore;
    private final int maxCandidates;
    private final SharedRollingBarHistoryService sharedBarHistory = SharedRollingBarHistoryService.getInstance();
    private final OpportunityContextRegistry opportunityContextRegistry = OpportunityContextRegistry.getInstance();

    private MomentumCandidateTracker() {
        this.ttlMs = longEnv("MOMENTUM_CANDIDATE_TTL_MS", 10 * 60_000L);
        this.minCatalystScore = doubleEnv("MOMENTUM_CANDIDATE_MIN_CATALYST_SCORE", 0.40);
        this.minHighPriorityScore = doubleEnv("MOMENTUM_CANDIDATE_HIGH_PRIORITY_MIN_CATALYST_SCORE", 0.30);
        this.maxCandidates = intEnv("MOMENTUM_CANDIDATE_MAX", 75);
    }

    public static MomentumCandidateTracker getInstance() {
        return INSTANCE;
    }

    public boolean registerCatalyst(String ticker,
                                    String headline,
                                    double catalystScore,
                                    double predictiveScore,
                                    boolean highPriority,
                                    String reason) {
        String symbol = normalize(ticker);
        if (symbol.isEmpty() || !AlpacaSymbolFilter.isEligibleStockSymbol(symbol)) {
            return false;
        }
        double required = highPriority ? minHighPriorityScore : minCatalystScore;
        if (catalystScore < required) {
            return false;
        }
        pruneExpired();
        if (candidates.size() >= maxCandidates && !candidates.containsKey(symbol)) {
            evictWeakest();
        }
        Candidate candidate = candidates.computeIfAbsent(symbol, Candidate::new);
        candidate.headline = headline == null ? "" : headline;
        candidate.catalystScore = Math.max(candidate.catalystScore, catalystScore);
        candidate.predictiveScore = Math.max(candidate.predictiveScore, predictiveScore);
        candidate.highPriority = candidate.highPriority || highPriority;
        candidate.reason = reason == null ? "" : reason;
        candidate.lastUpdatedAt = System.currentTimeMillis();
        candidate.stage = LifecycleStage.DISCOVERED;
        opportunityContextRegistry.markCatalyst(symbol, highPriority ? "HIGH_PRIORITY_CATALYST" : "CATALYST", candidate.headline, candidate.catalystScore, candidate.predictiveScore, candidate.highPriority);
        opportunityContextRegistry.markLifecycle(symbol, candidate.stage);
        System.out.println("MOMENTUM CANDIDATE TRACKED: ticker=" + symbol
                + " catalystScore=" + fmt(candidate.catalystScore)
                + " predictiveScore=" + fmt(candidate.predictiveScore)
                + " highPriority=" + candidate.highPriority
                + " stage=" + candidate.stage
                + " ttlMs=" + ttlMs
                + " reason=" + candidate.reason
                + " headline=" + candidate.headline);
        return true;
    }

    public boolean registerDiscoveryCandidate(String ticker,
                                             String source,
                                             double discoveryScore,
                                             double relativeVolume,
                                             double dollarVolume,
                                             String reason) {
        String symbol = normalize(ticker);
        if (symbol.isEmpty() || !AlpacaSymbolFilter.isEligibleStockSymbol(symbol)) {
            return false;
        }
        pruneExpired();
        if (candidates.size() >= maxCandidates && !candidates.containsKey(symbol)) {
            evictWeakest();
        }
        Candidate candidate = candidates.computeIfAbsent(symbol, Candidate::new);
        candidate.reason = reason == null ? "" : reason;
        candidate.headline = source == null ? candidate.headline : source;
        candidate.bestDiscoveryScore = Math.max(candidate.bestDiscoveryScore, discoveryScore);
        candidate.bestRvol = Math.max(candidate.bestRvol, relativeVolume);
        candidate.bestDollarVolume = Math.max(candidate.bestDollarVolume, dollarVolume);
        candidate.lastUpdatedAt = System.currentTimeMillis();
        if (candidate.stage == LifecycleStage.DISCOVERED && candidate.barsObserved > 0) {
            candidate.stage = LifecycleStage.OBSERVED;
        }
        opportunityContextRegistry.markLifecycle(symbol, candidate.stage);
        return true;
    }

    public void observeBar(String ticker, Bar bar, MomentumDiscoveryEngine.MomentumDiscoveryProfile profile) {
        String symbol = normalize(ticker);
        Candidate candidate = candidates.get(symbol);
        if (candidate == null || bar == null) {
            return;
        }
        if (isExpired(candidate)) {
            candidates.remove(symbol);
            return;
        }
        TechnicalFeatureSnapshot beforeFeatures = TechnicalFeatureService.getInstance().snapshot(symbol);
        int beforeSharedBars = sharedBarHistory.count(symbol);
        opportunityContextRegistry.observeBar(symbol, bar, "MOMENTUM_CANDIDATE_TRACKER");
        int afterSharedBars = sharedBarHistory.count(symbol);
        boolean newObservation = candidate.observe(bar) || afterSharedBars > beforeSharedBars;
        candidate.barsObserved = Math.max(candidate.barsObserved, afterSharedBars);
        candidate.lastUpdatedAt = System.currentTimeMillis();
        candidate.lastPrice = bar.close;
        candidate.lastVolume = bar.volume;
        if (!newObservation && candidate.barsObserved > 0 && profile == null) {
            return;
        }
        if (profile != null) {
            candidate.bestDiscoveryScore = Math.max(candidate.bestDiscoveryScore, profile.score);
            candidate.bestRvol = Math.max(candidate.bestRvol, profile.relativeVolume);
            candidate.bestDollarVolume = Math.max(candidate.bestDollarVolume, profile.dollarVolume);
            candidate.lastDiscoveryReason = profile.reason;
            candidate.stage = classifyStage(candidate, profile);
            opportunityContextRegistry.markLifecycle(symbol, candidate.stage);
            System.out.println("MOMENTUM CANDIDATE STAGE: ticker=" + symbol
                    + " stage=" + candidate.stage
                    + " barsObserved=" + Math.max(candidate.barsObserved, sharedBarHistory.count(symbol))
                    + " ctx=" + System.identityHashCode(opportunityContextRegistry.getOrCreate(symbol))
                    + " " + sharedBarHistory.diagnostics(symbol)
                    + " score=" + fmt(profile.score)
                    + " best=" + fmt(candidate.bestDiscoveryScore)
                    + " rvol=" + fmt(profile.relativeVolume)
                    + " dollarVolume=" + fmt(profile.dollarVolume)
                    + " reason=" + profile.reason);
        } else if (candidate.barsObserved > 0) {
            candidate.stage = LifecycleStage.OBSERVED;
            opportunityContextRegistry.markLifecycle(symbol, candidate.stage);
        }
    }

    public boolean shouldRoute(String ticker, MomentumDiscoveryEngine.MomentumDiscoveryProfile profile) {
        String symbol = normalize(ticker);
        Candidate candidate = candidates.get(symbol);
        if (candidate == null || profile == null) {
            return false;
        }
        if (isExpired(candidate)) {
            candidates.remove(symbol);
            return false;
        }

        // Strong catalyst names get a lower *research routing* threshold, not a
        // lower buy threshold. The master strategy still has to create a BUY.
        double catalystBoost = clamp01(candidate.catalystScore) * 0.18 + clamp01(candidate.predictiveScore) * 0.08;
        double effectiveScore = clamp01(profile.score + catalystBoost);
        int observedBars = Math.max(candidate.barsObserved, sharedBarHistory.count(symbol));
        boolean enoughBars = observedBars >= intEnv("MOMENTUM_CANDIDATE_MIN_BARS_BEFORE_ROUTE", 2);
        boolean highPrioritySingleBar = candidate.highPriority
                && observedBars >= 1
                && effectiveScore >= doubleEnv("MOMENTUM_CANDIDATE_HIGH_PRIORITY_SINGLE_BAR_ROUTE_SCORE", 0.48)
                && (profile.relativeVolume >= 1.25 || profile.dollarVolume >= adaptiveDollarVolumeTarget(candidate.lastPrice) * 0.30);
        boolean normalRoute = enoughBars
                && effectiveScore >= doubleEnv("MOMENTUM_CANDIDATE_ROUTE_SCORE", 0.50)
                && (profile.relativeVolume >= 1.20 || profile.dollarVolume >= adaptiveDollarVolumeTarget(candidate.lastPrice) * 0.35);
        boolean route = profile.pass || highPrioritySingleBar || normalRoute;
        if (route) {
            System.out.println("MOMENTUM CANDIDATE ROUTE: ticker=" + symbol
                    + " effectiveScore=" + fmt(effectiveScore)
                    + " discoveryScore=" + fmt(profile.score)
                    + " catalystScore=" + fmt(candidate.catalystScore)
                    + " barsObserved=" + observedBars
                    + " stage=" + candidate.stage
                    + " rvol=" + fmt(profile.relativeVolume)
                    + " dollarVolume=" + fmt(profile.dollarVolume)
                    + " reason=" + profile.reason);
        }
        return route;
    }

    public List<String> topSymbols(int limit) {
        pruneExpired();
        List<Candidate> list = new ArrayList<>(candidates.values());
        list.sort(Comparator.comparingDouble(Candidate::priorityScore).reversed());
        List<String> out = new ArrayList<>();
        for (Candidate c : list) {
            if (out.size() >= limit) break;
            out.add(c.ticker);
        }
        return out;
    }

    public int activeCount() {
        pruneExpired();
        return candidates.size();
    }

    public boolean isTracked(String ticker) {
        String symbol = normalize(ticker);
        Candidate candidate = candidates.get(symbol);
        if (candidate == null) return false;
        if (isExpired(candidate)) {
            candidates.remove(symbol);
            return false;
        }
        return true;
    }


    private LifecycleStage classifyStage(Candidate candidate, MomentumDiscoveryEngine.MomentumDiscoveryProfile profile) {
        if (candidate == null) return LifecycleStage.DISCOVERED;
        if (profile == null) return candidate.barsObserved > 0 ? LifecycleStage.OBSERVED : LifecycleStage.DISCOVERED;
        double catalystBoost = clamp01(candidate.catalystScore) * 0.18 + clamp01(candidate.predictiveScore) * 0.08;
        double effectiveScore = clamp01(profile.score + catalystBoost);
        if (profile.pass || effectiveScore >= doubleEnv("MOMENTUM_CANDIDATE_ENTRY_STAGE_SCORE", 0.58)) {
            return LifecycleStage.ENTRY_CANDIDATE;
        }
        if (effectiveScore >= doubleEnv("MOMENTUM_CANDIDATE_IGNITION_STAGE_SCORE", 0.50)
                || profile.relativeVolume >= doubleEnv("MOMENTUM_CANDIDATE_IGNITION_STAGE_RVOL", 1.75)) {
            return LifecycleStage.IGNITION;
        }
        if (effectiveScore >= doubleEnv("MOMENTUM_CANDIDATE_BUILDING_STAGE_SCORE", 0.38)
                || profile.relativeVolume >= doubleEnv("MOMENTUM_CANDIDATE_BUILDING_STAGE_RVOL", 1.25)
                || profile.dollarVolume >= adaptiveDollarVolumeTarget(candidate.lastPrice) * 0.25) {
            return LifecycleStage.MOMENTUM_BUILDING;
        }
        return candidate.barsObserved > 0 ? LifecycleStage.OBSERVED : LifecycleStage.DISCOVERED;
    }

    private void pruneExpired() {
        candidates.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    private boolean isExpired(Candidate c) {
        return c == null || System.currentTimeMillis() - c.createdAt > ttlMs;
    }

    private void evictWeakest() {
        String weakest = candidates.values().stream()
                .min(Comparator.comparingDouble(Candidate::priorityScore))
                .map(c -> c.ticker)
                .orElse(null);
        if (weakest != null) {
            candidates.remove(weakest);
        }
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static double adaptiveDollarVolumeTarget(double price) {
        if (price >= 250.0) return 80_000.0;
        if (price >= 100.0) return 110_000.0;
        if (price >= 25.0) return 150_000.0;
        if (price >= 5.0) return 100_000.0;
        if (price >= 1.0) return 65_000.0;
        return 30_000.0;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static long longEnv(String key, long fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        try { return Long.parseLong(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    private static int intEnv(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    private static double doubleEnv(String key, double fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        try { return Double.parseDouble(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    public enum LifecycleStage {
        DISCOVERED,
        OBSERVED,
        MOMENTUM_BUILDING,
        IGNITION,
        ENTRY_CANDIDATE,
        EXECUTED,
        MANAGED,
        EXITED,
        LEARNED
    }

    private static final class Candidate {
        final String ticker;
        final long createdAt = System.currentTimeMillis();
        long lastUpdatedAt = createdAt;
        String headline = "";
        String reason = "";
        double catalystScore;
        double predictiveScore;
        boolean highPriority;
        int barsObserved;
        double bestDiscoveryScore;
        double bestRvol;
        double bestDollarVolume;
        double lastPrice;
        long lastVolume;
        long lastBarTimestamp = Long.MIN_VALUE;
        double lastBarClose = Double.NaN;
        long lastBarVolume = Long.MIN_VALUE;
        String lastDiscoveryReason = "";
        LifecycleStage stage = LifecycleStage.DISCOVERED;

        Candidate(String ticker) {
            this.ticker = ticker;
        }

        boolean observe(Bar bar) {
            if (bar == null || bar.close <= 0.0) return false;
            boolean same = lastBarTimestamp == bar.timestamp
                    && Math.abs(lastBarClose - bar.close) < 0.000001
                    && lastBarVolume == bar.volume;
            lastBarTimestamp = bar.timestamp;
            lastBarClose = bar.close;
            lastBarVolume = bar.volume;
            if (!same) {
                barsObserved++;
            }
            return !same;
        }

        double priorityScore() {
            return catalystScore * 0.45
                    + predictiveScore * 0.15
                    + bestDiscoveryScore * 0.25
                    + clamp01(bestRvol / 5.0) * 0.10
                    + (highPriority ? 0.10 : 0.0);
        }
    }
}
