package com.bot.scanner;

import com.bot.broker.AlpacaBroker;
import com.bot.execution.unified.UnifiedSignalRouter;
import com.bot.intelligence.DynamicUniverseBuilder;
import com.bot.intelligence.MarketRegimeEngine;
import com.bot.intelligence.OpportunityMemoryService;
import com.bot.intelligence.WorldModelAgent;
import com.bot.intelligence.MarketFeatureBus;
import com.bot.intelligence.ParabolicTopVolumeTracker;
import com.bot.intelligence.StockMemoryService;
import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.TradeDirection;
import com.bot.stream.AlpacaSymbolFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Continuous market-wide scanner for non-news strategy discovery.
 *
 * News is only one information stream. This scanner keeps the entire Alpaca
 * tradable US equity universe available to every unified strategy by rotating
 * through the universe every few seconds, batch-fetching latest bars, updating
 * MarketDataCache, and invoking UnifiedSignalRouter.onTickerUpdate(...). The
 * master engine then lets every strategy independently compete on the updated
 * market-only setup: panic reversal, VWAP reclaim, failed breakdown, short
 * squeeze, gap fill, momentum/feature intelligence, etc.
 */
public class FullMarketStrategyScanner {

    private static final int DEFAULT_SYMBOLS_PER_CYCLE = 50;
    private static final int DEFAULT_RAW_SYMBOLS_PER_CYCLE = 250;
    private static final int DEFAULT_MAX_API_BATCHES_PER_CYCLE = 3;
    private static final int DEFAULT_BAR_CACHE_MAX_SIZE = 12_000;
    private static final long DEFAULT_BAR_CACHE_TTL_MS = 90_000L;
    private static final int MAX_NO_DATA_STRIKES = 3;
    private static final long DEFAULT_MIN_SCAN_MS = 8_000L;
    private static final long DEFAULT_MAX_SCAN_MS = 12_000L;
    private static final long DEFAULT_UNIVERSE_REFRESH_MS = 15 * 60_000L;

    private final AlpacaBroker broker;
    private final MarketDataCache marketData;
    private final UnifiedSignalRouter router;
    private final AtomicLong cachedEquity;
    private final int symbolsPerCycle;
    private final int rawSymbolsPerCycle;
    private final long minScanMs;
    private final long maxScanMs;
    private final long universeRefreshMs;
    private final long minLatestBarVolume;
    private final long maxLatestBarAgeMs;
    private final long barCacheTtlMs;
    private final int maxApiBatchesPerCycle;
    private final int barCacheMaxSize;
    private final double minScanPrice;
    private final double maxScanPrice;
    private final boolean dynamicUniverseEnabled;
    private final int dynamicUniverseMaxSymbols;
    private final double minScannerMomentumScore;
    private final double minDiscoveryCandidateScore;
    private final int discoveryTopCandidatesPerCycle;
    private final int executionTopCandidatesPerCycle;
    private final DynamicUniverseBuilder dynamicUniverseBuilder = new DynamicUniverseBuilder();
    private final StockMemoryService stockMemoryService = StockMemoryService.getInstance();
    private final ParabolicTopVolumeTracker parabolicTopVolumeTracker = ParabolicTopVolumeTracker.getInstance();
    private final MarketRegimeEngine marketRegimeEngine = MarketRegimeEngine.getInstance();
    private final WorldModelAgent worldModelAgent = WorldModelAgent.getInstance();
    private final OpportunityMemoryService opportunityMemoryService = OpportunityMemoryService.getInstance();
    private final MarketFeatureBus marketFeatureBus = MarketFeatureBus.getInstance();
    private final MomentumDiscoveryEngine momentumDiscoveryEngine = new MomentumDiscoveryEngine();
    private final MomentumCandidateTracker momentumCandidateTracker = MomentumCandidateTracker.getInstance();
    private final LiveMomentumLeaderboard liveMomentumLeaderboard = new LiveMomentumLeaderboard();
    private final EntryStagingAgent entryStagingAgent = new EntryStagingAgent();
    private final SharedRollingBarHistoryService sharedBarHistory = SharedRollingBarHistoryService.getInstance();
    private final OpportunityContextRegistry opportunityContextRegistry = OpportunityContextRegistry.getInstance();
    private final TechnicalFeatureService technicalFeatureService = TechnicalFeatureService.getInstance();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean observationSchedulerStarted = new AtomicBoolean(false);
    private final AtomicInteger cursor = new AtomicInteger(0);

    private volatile List<String> universe = new ArrayList<>();
    private final Set<String> activeSymbols = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> noDataStrikes = new ConcurrentHashMap<>();
    private final Map<String, CachedBar> barCache = new ConcurrentHashMap<>();
    private final Map<String, List<Bar>> scannerBarHistory = new ConcurrentHashMap<>();
    private volatile long lastUniverseRefreshAt = 0L;

    public FullMarketStrategyScanner(
            AlpacaBroker broker,
            MarketDataCache marketData,
            UnifiedSignalRouter router,
            AtomicLong cachedEquity
    ) {
        this.broker = broker;
        this.marketData = marketData;
        this.router = router;
        this.cachedEquity = cachedEquity;
        this.symbolsPerCycle = Math.max(1, envInt("FULL_MARKET_SCAN_SYMBOLS_PER_CYCLE", DEFAULT_SYMBOLS_PER_CYCLE));
        this.rawSymbolsPerCycle = Math.max(this.symbolsPerCycle, envInt("FULL_MARKET_SCAN_RAW_SYMBOLS_PER_CYCLE", DEFAULT_RAW_SYMBOLS_PER_CYCLE));
        this.minScanMs = Math.max(500L, envLong("FULL_MARKET_SCAN_MIN_MS", DEFAULT_MIN_SCAN_MS));
        this.maxScanMs = Math.max(this.minScanMs, envLong("FULL_MARKET_SCAN_MAX_MS", DEFAULT_MAX_SCAN_MS));
        this.universeRefreshMs = Math.max(60_000L, envLong("FULL_MARKET_UNIVERSE_REFRESH_MS", DEFAULT_UNIVERSE_REFRESH_MS));
        this.minLatestBarVolume = Math.max(0L, envLong("FULL_MARKET_MIN_LATEST_BAR_VOLUME", 0L));
        this.maxLatestBarAgeMs = Math.max(60_000L, envLong("FULL_MARKET_MAX_LATEST_BAR_AGE_MS", 10 * 60_000L));
        this.barCacheTtlMs = Math.max(5_000L, envLong("FULL_MARKET_BAR_CACHE_TTL_MS", DEFAULT_BAR_CACHE_TTL_MS));
        this.maxApiBatchesPerCycle = Math.max(1, envInt("FULL_MARKET_SCAN_MAX_API_BATCHES_PER_CYCLE", DEFAULT_MAX_API_BATCHES_PER_CYCLE));
        this.barCacheMaxSize = Math.max(100, envInt("FULL_MARKET_BAR_CACHE_MAX_SIZE", DEFAULT_BAR_CACHE_MAX_SIZE));
        this.minScanPrice = Math.max(0.0, envDouble("FULL_MARKET_SCAN_MIN_PRICE", 0.50));
        this.maxScanPrice = Math.max(this.minScanPrice, envDouble("FULL_MARKET_SCAN_MAX_PRICE", 150.0));
        this.dynamicUniverseEnabled = envBoolean("DYNAMIC_UNIVERSE_ENABLED", false);
        this.dynamicUniverseMaxSymbols = Math.max(50, envInt("DYNAMIC_UNIVERSE_MAX_SYMBOLS", 120));
        this.minScannerMomentumScore = Math.max(0.0, envDouble("FULL_MARKET_MIN_SCANNER_MOMENTUM_SCORE", 0.42));
        this.minDiscoveryCandidateScore = Math.max(0.0, envDouble("FULL_MARKET_DISCOVERY_CANDIDATE_SCORE", 0.20));
        this.discoveryTopCandidatesPerCycle = Math.max(10, envInt("FULL_MARKET_DISCOVERY_TOP_CANDIDATES", 100));
        this.executionTopCandidatesPerCycle = Math.max(1, envInt("FULL_MARKET_EXECUTION_TOP_CANDIDATES", Math.max(symbolsPerCycle, 100)));
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        marketFeatureBus.start();

        Thread thread = new Thread(this::runLoop);
        thread.setName("full-market-strategy-scanner");
        thread.setDaemon(true);
        thread.start();
        startOpportunityObservationScheduler();

        System.out.println("FULL MARKET STRATEGY SCANNER STARTED: symbolsPerCycle=" + symbolsPerCycle +
                " rawSymbolsPerCycle=" + rawSymbolsPerCycle +
                " scanMs=" + minScanMs + "-" + maxScanMs +
                " legacyMinLatestBarVolume=" + minLatestBarVolume +
                " maxLatestBarAgeMs=" + maxLatestBarAgeMs +
                " cacheTtlMs=" + barCacheTtlMs +
                " maxApiBatchesPerCycle=" + maxApiBatchesPerCycle +
                " priceRange=" + minScanPrice + "-" + maxScanPrice +
                " dynamicUniverse=" + dynamicUniverseEnabled +
                " dynamicUniverseMax=" + dynamicUniverseMaxSymbols +
                " minScannerMomentumScore=" + minScannerMomentumScore +
                " discoveryCandidateScore=" + minDiscoveryCandidateScore +
                " discoveryTopCandidates=" + discoveryTopCandidatesPerCycle +
                " executionTopCandidates=" + executionTopCandidatesPerCycle +
                " mode=MOMENTUM_FIRST_IGNITION_DISCOVERY");
        System.out.println("FULL MARKET STRATEGY SCANNER: builds a ranked live momentum leaderboard first; top discovery names are observed, strict execution names reach unified strategies.");
    }

    public void stop() {
        started.set(false);
    }


    private void startOpportunityObservationScheduler() {
        if (!observationSchedulerStarted.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::runOpportunityObservationLoop);
        thread.setName("opportunity-observation-scheduler");
        thread.setDaemon(true);
        thread.start();
        System.out.println("OPPORTUNITY OBSERVATION SCHEDULER STARTED: refreshMs="
                + envLong("OPPORTUNITY_OBSERVATION_REFRESH_MS", 5_000L)
                + " maxSymbols=" + envInt("OPPORTUNITY_OBSERVATION_MAX_SYMBOLS", 80)
                + " source=shared OpportunityContext registry");
    }

    /**
     * Dedicated observation loop for already-discovered opportunities.
     *
     * The full-market scanner rotates through thousands of symbols, so a symbol
     * can be discovered and then not naturally reappear for a long time. That
     * caused entry staging to see bars=1/5 even though the symbol was in the
     * opportunity pool. This loop refreshes the active opportunity registry on
     * its own cadence and appends fresh/latest-bar snapshots into the same
     * OpportunityContext used by scanner, tracker, and staging.
     */
    private void runOpportunityObservationLoop() {
        long refreshMs = Math.max(1_000L, envLong("OPPORTUNITY_OBSERVATION_REFRESH_MS", 5_000L));
        while (started.get()) {
            try {
                refreshActiveOpportunityContexts();
            } catch (Exception e) {
                System.err.println("OPPORTUNITY OBSERVATION SCHEDULER ERROR: " + e.getMessage());
            }
            sleepQuietly(refreshMs);
        }
    }

    private void refreshActiveOpportunityContexts() {
        if (broker == null || broker.isMarketDataBackoffActive()) {
            return;
        }
        int max = Math.max(1, envInt("OPPORTUNITY_OBSERVATION_MAX_SYMBOLS", 80));
        Set<String> symbols = new LinkedHashSet<>();
        symbols.addAll(momentumCandidateTracker.topSymbols(max));
        symbols.addAll(entryStagingAgent.topSymbols(max));
        symbols.addAll(opportunityContextRegistry.topSymbols(max));
        symbols.addAll(parabolicTopVolumeTracker.topSymbols(Math.max(25, max)));
        symbols.addAll(activeSymbols);
        symbols.removeIf(s -> s == null || s.isBlank() || !AlpacaSymbolFilter.isEligibleStockSymbol(s));
        if (symbols.isEmpty()) {
            return;
        }
        List<String> refresh = new ArrayList<>(symbols);
        if (refresh.size() > max) {
            refresh = new ArrayList<>(refresh.subList(0, max));
        }

        int observed = 0;
        int changed = 0;
        int staged = 0;
        int routed = 0;
        Map<String, Bar> latest;
        try {
            latest = broker.getLatestBars(refresh);
        } catch (Exception e) {
            System.err.println("OPPORTUNITY OBSERVATION REFRESH ERROR: requested=" + refresh.size() + " error=" + e.getMessage());
            return;
        }
        double equity = cachedEquity == null ? 0.0 : cachedEquity.get() / 100.0;
        for (String ticker : refresh) {
            Bar bar = latest.get(ticker);
            if (bar == null || bar.close <= 0.0 || !isPriceTradableBar(ticker, bar)) {
                continue;
            }
            observed++;
            int before = sharedBarHistory.count(ticker);
            observeDiscoveryBar(ticker, bar);
            recordScannerHistory(ticker, bar);
            int after = sharedBarHistory.count(ticker);
            if (after > before) changed++;

            MomentumDiscoveryEngine.MomentumDiscoveryProfile profile = momentumDiscoveryEngine.evaluate(ticker, marketData, bar);
            momentumCandidateTracker.observeBar(ticker, bar, profile);
            boolean tracked = momentumCandidateTracker.isTracked(ticker);
            boolean routeCandidate = tracked && momentumCandidateTracker.shouldRoute(ticker, profile);
            boolean fresh = isFreshTradableBar(ticker, bar);
            boolean executionReady = fresh && (profile.pass || routeCandidate);

            if (tracked || executionReady || after >= envInt("ENTRY_STAGING_MIN_BARS", 5)) {
                EntryStagingAgent.Decision decision = entryStagingAgent.assess(ticker, bar, marketData, profile, false, inferScannerDirection(profile));
                staged++;
                executionReady = executionReady && decision.routeNow();
            }
            if (executionReady) {
                routed++;
                evaluateBar(ticker, bar, equity);
            }
        }
        if (envBoolean("OPPORTUNITY_OBSERVATION_LOG", true) && (observed > 0 || !refresh.isEmpty())) {
            System.out.println("OPPORTUNITY OBSERVATION REFRESH: requested=" + refresh.size()
                    + " observed=" + observed
                    + " changed=" + changed
                    + " staged=" + staged
                    + " routed=" + routed
                    + " contexts=" + opportunityContextRegistry.activeCount()
                    + " tracker=" + momentumCandidateTracker.activeCount()
                    + " staging=" + entryStagingAgent.activeCount()
                    + " top=" + opportunityContextRegistry.topSymbols(5));
        }
    }

    private void runLoop() {
        while (started.get()) {
            try {
                refreshUniverseIfNeeded();
                runScanCycle();
                sleepRandomScanDelay();
            } catch (Exception e) {
                System.err.println("FULL MARKET STRATEGY SCANNER ERROR: " + e.getMessage());
                sleepQuietly(Math.max(5_000L, maxScanMs));
            }
        }
    }

    private void refreshUniverseIfNeeded() {
        long now = System.currentTimeMillis();
        if (!universe.isEmpty() && now - lastUniverseRefreshAt < universeRefreshMs) {
            return;
        }

        List<String> loaded = broker.getTradableStockSymbols();
        Set<String> deduped = new LinkedHashSet<>();
        for (String symbol : loaded) {
            String normalized = AlpacaSymbolFilter.normalize(symbol);
            if (AlpacaSymbolFilter.isEligibleStockSymbol(normalized)) {
                deduped.add(normalized);
            }
        }

        List<String> fullUniverse = new ArrayList<>(deduped);
        Collections.sort(fullUniverse);
        List<String> nextUniverse = dynamicUniverseEnabled
                ? dynamicUniverseBuilder.build(fullUniverse, activeSymbols, dynamicUniverseMaxSymbols)
                : fullUniverse;
        universe = nextUniverse;
        lastUniverseRefreshAt = now;
        cursor.set(0);
        activeSymbols.clear();
        noDataStrikes.clear();
        barCache.clear();
        // Keep scannerBarHistory across universe refreshes. Clearing it here
        // erased the only prior observations for scanner-discovered symbols and
        // caused the next pass to restart at bars=1/5 with velocity=0.000%.

        System.out.println("FULL MARKET STRATEGY SCANNER UNIVERSE READY: symbols=" + universe.size() + " dynamic=" + dynamicUniverseEnabled + " fullTradable=" + fullUniverse.size());
    }

    private void runScanCycle() {
        if (broker.isMarketDataBackoffActive()) {
            long remainingMs = broker.marketDataBackoffRemainingMs();
            System.out.println("FULL MARKET STRATEGY SCAN SKIPPED: Alpaca market-data backoff active remainingMs=" + remainingMs);
            sleepQuietly(Math.min(30_000L, Math.max(2_000L, remainingMs)));
            return;
        }

        double equity = Double.longBitsToDouble(cachedEquity.get());
        int requested = 0;
        int barsAdded = 0;
        int evaluated = 0;
        int filtered = 0;
        int cacheHits = 0;
        int validBars = 0;
        int observedBars = 0;
        int momentumQualified = 0;
        int noData = 0;
        int staleOrPriceRejected = 0;
        int staleObserved = 0;
        int candidateTracked = 0;
        int candidateRouted = 0;
        int discoveryCandidates = 0;
        int executionQualified = 0;
        int stagedWait = 0;
        int stagedBuyNow = 0;
        int stagedShortNow = 0;
        int stagedReject = 0;
        Map<String, Integer> rejectionReasons = new LinkedHashMap<>();
        List<ScanCandidate> cycleLeaderboard = new ArrayList<>();

        // First evaluate recently active cached symbols without calling Alpaca.
        long now = System.currentTimeMillis();
        List<String> activeSnapshot = new ArrayList<>(activeSymbols);
        for (String ticker : activeSnapshot) {
            if (evaluated >= symbolsPerCycle) {
                break;
            }
            CachedBar cached = barCache.get(ticker);
            if (cached == null || now - cached.cachedAt > barCacheTtlMs) {
                continue;
            }
            if (evaluateBar(ticker, cached.bar, equity)) {
                evaluated++;
                cacheHits++;
            } else {
                filtered++;
            }
        }


        /*
         * Dedicated lifecycle refresh pass.
         *
         * The scanner previously registered discovery candidates, but then only
         * revisited them when the normal 5,000-symbol rotation happened to land
         * on the same ticker again. That left scanner candidates stuck at
         * bars=1/5 and velocity=0.000% even though they were in the candidate
         * pool. This pass actively refreshes the current opportunity pool every
         * cycle before rotating to new symbols.
         */
        int lifecycleRefreshRequested = 0;
        int lifecycleRefreshObserved = 0;
        int lifecycleRefreshRouted = 0;
        Set<String> lifecycleRefreshSymbols = new LinkedHashSet<>();
        lifecycleRefreshSymbols.addAll(momentumCandidateTracker.topSymbols(envInt("FULL_MARKET_LIFECYCLE_REFRESH_TRACKER_TOP", 120)));
        lifecycleRefreshSymbols.addAll(entryStagingAgent.topSymbols(envInt("FULL_MARKET_LIFECYCLE_REFRESH_STAGING_TOP", 120)));
        lifecycleRefreshSymbols.addAll(opportunityContextRegistry.topSymbols(envInt("FULL_MARKET_LIFECYCLE_REFRESH_CONTEXT_TOP", 120)));
        lifecycleRefreshSymbols.removeIf(s -> s == null || s.isBlank() || !AlpacaSymbolFilter.isEligibleStockSymbol(s));
        int lifecycleMax = Math.max(0, envInt("FULL_MARKET_LIFECYCLE_REFRESH_MAX_SYMBOLS", 120));
        if (!lifecycleRefreshSymbols.isEmpty() && lifecycleMax > 0 && !broker.isMarketDataBackoffActive()) {
            List<String> refreshList = new ArrayList<>(lifecycleRefreshSymbols);
            if (refreshList.size() > lifecycleMax) {
                refreshList = new ArrayList<>(refreshList.subList(0, lifecycleMax));
            }
            lifecycleRefreshRequested = refreshList.size();
            requested += refreshList.size();
            try {
                Map<String, Bar> lifecycleBars = broker.getLatestBars(refreshList);
                for (String ticker : refreshList) {
                    Bar bar = lifecycleBars.get(ticker);
                    if (bar == null || bar.close <= 0.0) {
                        recordNoDataStrike(ticker);
                        noData++;
                        filtered++;
                        continue;
                    }
                    validBars++;
                    boolean freshTradable = isFreshTradableBar(ticker, bar);
                    boolean priceTradable = isPriceTradableBar(ticker, bar);
                    if (!priceTradable) {
                        staleOrPriceRejected++;
                        filtered++;
                        rejectionReasons.merge("LIFECYCLE_PRICE_OR_SYMBOL", 1, Integer::sum);
                        continue;
                    }

                    CachedBar previousCached = previousCachedBarForVelocity(ticker, bar);
                    observeDiscoveryBar(ticker, bar);
                    recordScannerHistory(ticker, bar);
                    observedBars++;
                    lifecycleRefreshObserved++;
                    if (!freshTradable) {
                        staleObserved++;
                        staleOrPriceRejected++;
                    }

                    MomentumDiscoveryEngine.MomentumDiscoveryProfile profile = momentumDiscoveryEngine.evaluate(ticker, marketData, bar);
                    TechnicalFeatureSnapshot sharedFeatures = technicalFeatureService.snapshot(ticker);
                    momentumCandidateTracker.observeBar(ticker, bar, profile);
                    boolean trackedCandidate = momentumCandidateTracker.isTracked(ticker);
                    if (trackedCandidate) candidateTracked++;
                    boolean trackedCandidateRoute = momentumCandidateTracker.shouldRoute(ticker, profile);

                    double historyRankScore = scannerDiscoveryRankScore(ticker, bar, previousCached);
                    double velocityBoost = Math.min(0.18, Math.max(Math.abs(sharedFeatures.oneBarVelocityPct), Math.abs(sharedFeatures.threeBarVelocityPct)) / 4.0);
                    double rankScore = Math.max(historyRankScore + velocityBoost, profile.score);
                    boolean aggressiveTopVolumeRoute = freshTradable && isAggressiveTopVolumeRoute(ticker, bar, sharedFeatures, rankScore, profile);
                    boolean strictExecutionReady = freshTradable
                            && (profile.pass || trackedCandidateRoute || rankScore >= minScannerMomentumScore || aggressiveTopVolumeRoute);
                    EntryStagingAgent.Decision stagingDecision = EntryStagingAgent.Decision.wait(ticker, "lifecycle refresh observing");
                    if (strictExecutionReady || trackedCandidate) {
                        stagingDecision = entryStagingAgent.assess(ticker, bar, marketData, profile, false, inferScannerDirection(profile));
                        switch (stagingDecision.action) {
                            case BUY_NOW -> stagedBuyNow++;
                            case SHORT_NOW -> stagedShortNow++;
                            case REJECT -> stagedReject++;
                            default -> stagedWait++;
                        }
                        boolean stagingAllowsRoute = stagingDecision.routeNow()
                                || (aggressiveTopVolumeRoute && sharedFeatures != null && sharedFeatures.bars >= envInt("TOP_VOLUME_SCANNER_ROUTE_MIN_BARS", 1));
                        strictExecutionReady = strictExecutionReady && stagingAllowsRoute;
                    }

                    candidatesAddLifecycle(cycleLeaderboard, ticker, bar, rankScore, profile, stagingDecision, strictExecutionReady, trackedCandidate, freshTradable, sharedFeatures);

                    if (strictExecutionReady) {
                        lifecycleRefreshRouted++;
                        executionQualified++;
                        momentumQualified++;
                        if (evaluateBar(ticker, bar, equity)) {
                            evaluated++;
                            barsAdded++;
                        } else {
                            filtered++;
                        }
                    } else {
                        filtered++;
                        rejectionReasons.merge("LIFECYCLE_OBSERVING", 1, Integer::sum);
                    }
                }
            } catch (Exception e) {
                System.err.println("FULL MARKET LIFECYCLE REFRESH ERROR: requested=" + lifecycleRefreshRequested + " error=" + e.getMessage());
            }
            if (envBoolean("FULL_MARKET_LIFECYCLE_REFRESH_LOG", true)) {
                System.out.println("FULL MARKET LIFECYCLE REFRESH: requested=" + lifecycleRefreshRequested
                        + " observed=" + lifecycleRefreshObserved
                        + " routed=" + lifecycleRefreshRouted
                        + " candidatePool=" + momentumCandidateTracker.activeCount()
                        + " stagingActive=" + entryStagingAgent.activeCount()
                        + " contexts=" + opportunityContextRegistry.activeCount()
                        + " top=" + opportunityContextRegistry.topSymbols(5));
            }
        }

        int apiBatches = 0;
        while (evaluated < symbolsPerCycle && apiBatches < maxApiBatchesPerCycle) {
            if (broker.isMarketDataBackoffActive()) {
                break;
            }

            List<String> symbols = nextSymbolBatch();
            if (symbols.isEmpty()) {
                break;
            }

            requested += symbols.size();
            apiBatches++;

            Map<String, Bar> bars;
            try {
                bars = broker.getLatestBars(symbols);
            } catch (Exception e) {
                System.err.println("FULL MARKET STRATEGY SCANNER BATCH ERROR: requested=" + symbols.size() + " error=" + e.getMessage());
                break;
            }

            List<ScanCandidate> candidates = new ArrayList<>();
            for (String ticker : symbols) {
                try {
                    stockMemoryService.observeScanCandidate(ticker);
                    Bar bar = bars.get(ticker);
                    if (bar == null || bar.close <= 0) {
                        recordNoDataStrike(ticker);
                        filtered++;
                        noData++;
                        continue;
                    }
                    validBars++;
                    boolean freshTradable = isFreshTradableBar(ticker, bar);
                    boolean priceTradable = isPriceTradableBar(ticker, bar);

                    if (!priceTradable) {
                        filtered++;
                        staleOrPriceRejected++;
                        rejectionReasons.merge("PRICE_OR_SYMBOL", 1, Integer::sum);
                        continue;
                    }

                    // Snapshot the previous bar BEFORE publishing/caching this bar.
                    // The old code cached first, so rank velocity often compared the
                    // latest bar to itself and printed vel=0.000% for real movers.
                    CachedBar previousCached = previousCachedBarForVelocity(ticker, bar);

                    // Discovery is rank-based now. Even stale bars are useful for
                    // baseline/history and for deciding which names deserve future
                    // observation. Only fresh bars or lifecycle-routed candidates
                    // are allowed to reach execution.
                    observeDiscoveryBar(ticker, bar);
                    recordScannerHistory(ticker, bar);
                    observedBars++;
                    if (!freshTradable) {
                        staleObserved++;
                        staleOrPriceRejected++;
                    }

                    MomentumDiscoveryEngine.MomentumDiscoveryProfile profile = momentumDiscoveryEngine.evaluate(ticker, marketData, bar);
                    SharedRollingBarHistoryService.Velocity sharedVelocity = sharedBarHistory.velocity(ticker);
                    TechnicalFeatureSnapshot sharedFeatures = technicalFeatureService.snapshot(ticker);
                    momentumCandidateTracker.observeBar(ticker, bar, profile);
                    boolean trackedCandidateRoute = momentumCandidateTracker.shouldRoute(ticker, profile);
                    boolean trackedCandidate = momentumCandidateTracker.isTracked(ticker);
                    if (trackedCandidate) {
                        candidateTracked++;
                    }

                    double historyRankScore = scannerDiscoveryRankScore(ticker, bar, previousCached);
                    double velocityBoost = Math.min(0.18, Math.max(Math.abs(sharedFeatures.oneBarVelocityPct), Math.abs(sharedFeatures.threeBarVelocityPct)) / 4.0);
                    double rankScore = Math.max(historyRankScore + velocityBoost, profile.score);
                    if (rankScore >= minDiscoveryCandidateScore || profile.relativeVolume >= 1.20 || profile.dollarVolume >= adaptiveDollarVolumeTarget(bar.close) * 0.35) {
                        momentumCandidateTracker.registerDiscoveryCandidate(ticker, "scanner_leaderboard", rankScore, profile.relativeVolume, profile.dollarVolume,
                                "scanner rankScore=" + fmt(rankScore) + " bars=" + sharedFeatures.bars + " v1=" + fmt(sharedFeatures.oneBarVelocityPct) + "% v3=" + fmt(sharedFeatures.threeBarVelocityPct) + "% rvol=" + fmt(sharedFeatures.relativeVolume));
                        activeSymbols.add(ticker);
                        trackedCandidate = true;
                    }
                    boolean aggressiveTopVolumeRoute = freshTradable && isAggressiveTopVolumeRoute(ticker, bar, sharedFeatures, rankScore, profile);
                    boolean strictExecutionReady = freshTradable
                            && (profile.pass || trackedCandidateRoute || rankScore >= minScannerMomentumScore || aggressiveTopVolumeRoute);
                    if (trackedCandidateRoute && !profile.pass) {
                        candidateRouted++;
                        rankScore = Math.max(rankScore, envDouble("MOMENTUM_CANDIDATE_ROUTED_MIN_SCANNER_SCORE", minScannerMomentumScore));
                        strictExecutionReady = true;
                    }

                    EntryStagingAgent.Decision stagingDecision = EntryStagingAgent.Decision.wait(ticker, "not staged");
                    if (strictExecutionReady) {
                        stagingDecision = entryStagingAgent.assess(
                                ticker,
                                bar,
                                marketData,
                                profile,
                                false,
                                inferScannerDirection(profile)
                        );
                        switch (stagingDecision.action) {
                            case BUY_NOW -> stagedBuyNow++;
                            case SHORT_NOW -> stagedShortNow++;
                            case REJECT -> stagedReject++;
                            default -> stagedWait++;
                        }
                        boolean stagingAllowsRoute = stagingDecision.routeNow()
                                || (aggressiveTopVolumeRoute && sharedFeatures != null && sharedFeatures.bars >= envInt("TOP_VOLUME_SCANNER_ROUTE_MIN_BARS", 1));
                        strictExecutionReady = strictExecutionReady && stagingAllowsRoute;
                        if (!strictExecutionReady) {
                            String key = stagingDecision.action == EntryStagingAgent.Action.WAIT_FOR_REVERSAL
                                    ? "ENTRY_WAIT_FOR_REVERSAL"
                                    : (stagingDecision.action == EntryStagingAgent.Action.REJECT ? "ENTRY_STAGING_REJECT" : "ENTRY_WAIT_FOR_RECOVERY");
                            rejectionReasons.merge(key, 1, Integer::sum);
                        }
                    }

                    // Add every observable name to the leaderboard. This prevents
                    // the old zero-candidate failure mode where a quiet tape or stale
                    // latest-bar timestamps made the discovery gate behave like a
                    // hard execution gate.
                    candidates.add(new ScanCandidate(ticker, bar, rankScore, profile.reason + " staging=" + stagingDecision.action + ":" + stagingDecision.reason,
                            strictExecutionReady, trackedCandidate, freshTradable,
                            sharedFeatures.relativeVolume > 0.0 ? sharedFeatures.relativeVolume : profile.relativeVolume,
                            bestVelocity(Math.abs(sharedFeatures.oneBarVelocityPct) > Math.abs(profile.liveVelocityPct) ? sharedFeatures.oneBarVelocityPct : profile.liveVelocityPct, ticker, bar, 1),
                            bestVelocity(Math.abs(sharedFeatures.threeBarVelocityPct) > Math.abs(profile.fastVelocityPct) ? sharedFeatures.threeBarVelocityPct : profile.fastVelocityPct, ticker, bar, 4),
                            sharedFeatures.rangePct > 0.0 ? sharedFeatures.rangePct : profile.rangePct,
                            sharedFeatures.dollarVolume > 0.0 ? sharedFeatures.dollarVolume : profile.dollarVolume));

                    if (!strictExecutionReady) {
                        rejectionReasons.merge(classifyDiscoveryRejection(profile, rankScore, freshTradable, priceTradable), 1, Integer::sum);
                    }
                } catch (Exception e) {
                    String detail = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    if (detail.contains("404") || detail.toLowerCase().contains("invalid symbol") || detail.contains("400")) {
                        AlpacaSymbolFilter.rejectPermanently(ticker, "scanner_symbol_error " + detail);
                        recordNoDataStrike(ticker);
                    } else if (detail.toLowerCase().contains("alpaca account")) {
                        System.out.println("FULL MARKET STRATEGY SCANNER ACCOUNT CACHE WAIT: ticker=" + ticker + " detail=" + detail);
                    } else {
                        System.err.println("FULL MARKET STRATEGY SCANNER SYMBOL ERROR: ticker=" + ticker + " error=" + detail);
                    }
                }
            }

            // Preserve the batch top list for cycle-level logging and then
            // process this batch's ranked leaders.
            candidates.sort(Comparator.comparingDouble((ScanCandidate c) -> c.score).reversed());
            cycleLeaderboard.addAll(candidates);
            LiveMomentumLeaderboard.LeaderboardResult batchLeaders = liveMomentumLeaderboard.rank(toLeaderboardEntries(candidates), discoveryTopCandidatesPerCycle, executionTopCandidatesPerCycle);
            if (!batchLeaders.sorted.isEmpty() && envBoolean("FULL_MARKET_SCAN_PRINT_MOMENTUM_TOP", true)) {
                System.out.println("LIVE MOMENTUM LEADERBOARD BATCH: "
                        + liveMomentumLeaderboard.summarize(batchLeaders.sorted, Math.min(12, batchLeaders.sorted.size())));
            }

            int discoveryRank = 0;
            int executionRank = 0;
            for (LiveMomentumLeaderboard.Entry ranked : batchLeaders.sorted) {
                discoveryRank++;
                cacheBar(ranked.ticker, ranked.bar);

                if (discoveryRank <= discoveryTopCandidatesPerCycle) {
                    discoveryCandidates++;
                }

                if (!ranked.executionReady) {
                    if (discoveryRank <= discoveryTopCandidatesPerCycle && envBoolean("FULL_MARKET_SCAN_PRINT_DISCOVERY_WATCH", false)) {
                        System.out.println("MOMENTUM DISCOVERY WATCH: ticker=" + ranked.ticker
                                + " rank=" + discoveryRank
                                + " score=" + String.format(java.util.Locale.US, "%.3f", ranked.score)
                                + " rvol=" + String.format(java.util.Locale.US, "%.3f", ranked.relativeVolume)
                                + " velocity=" + String.format(java.util.Locale.US, "%.3f", ranked.liveVelocityPct) + "%"
                                + " fresh=" + ranked.fresh
                                + " tracked=" + ranked.tracked
                                + " " + ranked.reason);
                    }
                    filtered++;
                    continue;
                }

                executionRank++;
                executionQualified++;
                momentumQualified++;
                if (evaluated >= symbolsPerCycle || executionRank > executionTopCandidatesPerCycle) {
                    filtered++;
                    continue;
                }
                if (envBoolean("FULL_MARKET_SCAN_PRINT_DISCOVERY_ACCEPTS", true)) {
                    System.out.println("MOMENTUM DISCOVERY ACCEPTED: ticker=" + ranked.ticker
                            + " rank=" + discoveryRank
                            + " executionRank=" + executionRank
                            + " score=" + String.format(java.util.Locale.US, "%.3f", ranked.score)
                            + " rvol=" + String.format(java.util.Locale.US, "%.3f", ranked.relativeVolume)
                            + " velocity=" + String.format(java.util.Locale.US, "%.3f", ranked.liveVelocityPct) + "%"
                            + " fresh=" + ranked.fresh
                            + " tracked=" + ranked.tracked
                            + " " + ranked.reason);
                }
                barsAdded++;
                if (evaluateBar(ranked.ticker, ranked.bar, equity)) {
                    evaluated++;
                } else {
                    filtered++;
                }
            }
        }

        if (!cycleLeaderboard.isEmpty() && envBoolean("FULL_MARKET_SCAN_PRINT_CYCLE_LEADERBOARD", true)) {
            List<ScanCandidate> sortedCycle = new ArrayList<>(cycleLeaderboard);
            sortedCycle.sort(Comparator.comparingDouble((ScanCandidate c) -> c.score).reversed());
            LiveMomentumLeaderboard.LeaderboardResult cycleLeaders = liveMomentumLeaderboard.rank(toLeaderboardEntries(sortedCycle), discoveryTopCandidatesPerCycle, executionTopCandidatesPerCycle);
            System.out.println("LIVE MOMENTUM LEADERBOARD CYCLE: "
                    + liveMomentumLeaderboard.summarize(cycleLeaders.sorted, Math.min(20, cycleLeaders.sorted.size())));
        }

        System.out.println("FULL MARKET STRATEGY SCAN CYCLE: requested=" + requested +
                " validBars=" + validBars +
                " observedBars=" + observedBars +
                " discoveryCandidates=" + discoveryCandidates +
                " executionQualified=" + executionQualified +
                " momentumQualified=" + momentumQualified +
                " routed=" + evaluated +
                " filtered=" + filtered +
                " noData=" + noData +
                " staleOrPriceRejected=" + staleOrPriceRejected +
                " cacheHits=" + cacheHits +
                " active=" + activeSymbols.size() +
                " cache=" + barCache.size() +
                " universe=" + universe.size() +
                " cursor=" + cursor.get() +
                " staleObserved=" + staleObserved +
                " candidateTracked=" + candidateTracked +
                " candidateRouted=" + candidateRouted +
                " candidatePool=" + momentumCandidateTracker.activeCount() +
                " entryStagingActive=" + entryStagingAgent.activeCount() +
                " opportunityContexts=" + opportunityContextRegistry.activeCount() +
                " stagedWait=" + stagedWait +
                " stagedBuyNow=" + stagedBuyNow +
                " stagedShortNow=" + stagedShortNow +
                " stagedReject=" + stagedReject +
                " topRejects=" + summarizeRejections(rejectionReasons, 5));
    }

    private boolean isAggressiveTopVolumeRoute(String ticker,
                                                Bar bar,
                                                TechnicalFeatureSnapshot features,
                                                double rankScore,
                                                MomentumDiscoveryEngine.MomentumDiscoveryProfile profile) {
        if (!envBoolean("TOP_VOLUME_SCANNER_ROUTE_ENABLED", true)) {
            return false;
        }
        if (ticker == null || ticker.isBlank() || bar == null || bar.close <= 0.0) {
            return false;
        }
        String normalized = ticker.trim().toUpperCase();
        double dollarVolume = Math.max(bar.close * Math.max(0L, bar.volume),
                features == null ? 0.0 : features.dollarVolume);
        double absVelocity = features == null ? 0.0 : Math.max(Math.abs(features.oneBarVelocityPct), Math.abs(features.threeBarVelocityPct));
        double range = features == null ? 0.0 : features.rangePct;
        double rvol = features == null ? (profile == null ? 0.0 : profile.relativeVolume) : features.relativeVolume;
        boolean topVolume = parabolicTopVolumeTracker.isTopVolumeTicker(normalized, envInt("TOP_VOLUME_SCANNER_ROUTE_TOP_RANK", 250));
        boolean violentEnough = absVelocity >= envDouble("TOP_VOLUME_SCANNER_ROUTE_MIN_ABS_VELOCITY_PCT", 0.015)
                || range >= envDouble("TOP_VOLUME_SCANNER_ROUTE_MIN_RANGE_PCT", 0.025)
                || rvol >= envDouble("TOP_VOLUME_SCANNER_ROUTE_MIN_RVOL", 0.90)
                || rankScore >= envDouble("TOP_VOLUME_SCANNER_ROUTE_MIN_SCORE", 0.22);
        boolean liquidEnough = dollarVolume >= envDouble("TOP_VOLUME_SCANNER_ROUTE_MIN_DOLLAR_VOLUME", 25_000.0)
                || bar.volume >= envLong("TOP_VOLUME_SCANNER_ROUTE_MIN_VOLUME", 2_500L);
        // Volume-first mode intentionally routes many top-volume names to staging;
        // staging decides long dip-recovery vs short apex/failure, not the scanner.
        return liquidEnough && (violentEnough || topVolume || dollarVolume >= envDouble("TOP_VOLUME_SCANNER_ROUTE_FORCE_DOLLAR_VOLUME", 75_000.0));
    }

    private void observeDiscoveryBar(String ticker, Bar bar) {
        if (ticker == null || ticker.isBlank() || bar == null || bar.close <= 0.0) {
            return;
        }
        String normalized = ticker.trim().toUpperCase();
        cacheBar(normalized, bar);
        opportunityContextRegistry.observeBar(normalized, bar, "FULL_MARKET_SCANNER_DISCOVERY_OBSERVE");
        marketData.addBar(normalized, bar);
        stockMemoryService.observeBar(normalized, bar);
        parabolicTopVolumeTracker.observeBar(normalized, bar);
        marketRegimeEngine.observeBar(normalized, bar);
        worldModelAgent.observeBar(normalized, bar);
        opportunityMemoryService.observeBar(normalized, bar);
        marketFeatureBus.publishBar("FULL_MARKET_SCANNER_DISCOVERY_OBSERVE", normalized, bar);
    }

    private boolean isFreshTradableBar(String ticker, Bar bar) {
        if (!isPriceTradableBar(ticker, bar)) {
            return false;
        }
        long normalizedTimestampMs = MarketFeatureBus.normalizeEpochMillis(bar.timestamp);
        long barAgeMs = Math.abs(System.currentTimeMillis() - normalizedTimestampMs);
        return normalizedTimestampMs <= 0 || barAgeMs <= maxLatestBarAgeMs;
    }

    private boolean isPriceTradableBar(String ticker, Bar bar) {
        if (ticker == null || ticker.isBlank() || bar == null || bar.close <= 0.0) {
            return false;
        }
        String normalized = ticker.trim().toUpperCase();
        if (!AlpacaSymbolFilter.isEligibleStockSymbol(normalized)) {
            return false;
        }
        return bar.close >= minScanPrice && bar.close <= maxScanPrice;
    }

    private boolean evaluateBar(String ticker, Bar bar, double equity) {
        if (ticker == null || ticker.isBlank() || bar == null || bar.close <= 0) {
            return false;
        }
        String normalized = ticker.trim().toUpperCase();
        if (!AlpacaSymbolFilter.isEligibleStockSymbol(normalized)) {
            return false;
        }
        stockMemoryService.observeScanCandidate(normalized);
        marketFeatureBus.publishBar("FULL_MARKET_SCANNER_EVALUATE", normalized, bar);
        clearNoDataStrike(normalized);
        if (bar.close < minScanPrice || bar.close > maxScanPrice) {
            return false;
        }
        // No static share-count gate here. MomentumDiscoveryEngine already
        // applied adaptive RVOL/dollar-volume/liquidity scoring before this
        // point. A fixed 25k-share filter caused high-priced movers and early
        // ignition setups to be discarded incorrectly.
        if (minLatestBarVolume > 0 && bar.volume < minLatestBarVolume) {
            return false;
        }
        long normalizedTimestampMs = MarketFeatureBus.normalizeEpochMillis(bar.timestamp);
        long barAgeMs = Math.abs(System.currentTimeMillis() - normalizedTimestampMs);
        if (normalizedTimestampMs > 0 && barAgeMs > maxLatestBarAgeMs) {
            return false;
        }

        activeSymbols.add(normalized);
        marketData.addBar(normalized, bar);
        stockMemoryService.observeBar(normalized, bar);
        parabolicTopVolumeTracker.observeBar(normalized, bar);
        marketRegimeEngine.observeBar(normalized, bar);
        worldModelAgent.observeBar(normalized, bar);
        opportunityMemoryService.observeBar(normalized, bar);
        try {
            router.onTickerUpdate(normalized, equity);
        } catch (Exception e) {
            String detail = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            if (detail.contains("404") || detail.toLowerCase().contains("invalid symbol") || detail.contains("400")) {
                AlpacaSymbolFilter.rejectPermanently(normalized, "scanner_router_error " + detail);
                activeSymbols.remove(normalized);
                recordNoDataStrike(normalized);
            } else if (detail.toLowerCase().contains("alpaca account")) {
                System.out.println("FULL MARKET STRATEGY SCANNER ACCOUNT CACHE WAIT: ticker=" + normalized + " detail=" + detail);
            } else {
                System.err.println("FULL MARKET STRATEGY SCANNER ROUTER ERROR: ticker=" + normalized + " error=" + detail);
            }
        }
        return true;
    }


    private CachedBar previousCachedBarForVelocity(String ticker, Bar latest) {
        String normalized = ticker == null ? "" : ticker.trim().toUpperCase();
        if (normalized.isEmpty()) return null;
        List<Bar> history = scannerBarHistory.get(normalized);
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                Bar b = history.get(i);
                if (b == null || b.close <= 0.0) continue;
                if (latest == null || b.timestamp != latest.timestamp || Math.abs(b.close - latest.close) > 0.000001) {
                    return new CachedBar(b, System.currentTimeMillis());
                }
            }
        }
        return barCache.get(normalized);
    }

    private void recordScannerHistory(String ticker, Bar latest) {
        if (ticker == null || ticker.isBlank() || latest == null || latest.close <= 0.0) return;
        String normalized = ticker.trim().toUpperCase();
        List<Bar> history = scannerBarHistory.computeIfAbsent(normalized, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (history) {
            if (!history.isEmpty()) {
                Bar last = history.get(history.size() - 1);
                if (last != null && last.timestamp == latest.timestamp && Math.abs(last.close - latest.close) < 0.000001) {
                    history.set(history.size() - 1, latest);
                    return;
                }
            }
            history.add(latest);
            while (history.size() > envInt("FULL_MARKET_SCANNER_HISTORY_BARS", 30)) {
                history.remove(0);
            }
        }
    }

    private double bestVelocity(double profileVelocity, String ticker, Bar latest, int lookbackBars) {
        double historyVelocity = historyVelocityPct(ticker, latest, lookbackBars);
        return Math.abs(historyVelocity) > Math.abs(profileVelocity) ? historyVelocity : profileVelocity;
    }

    private double historyVelocityPct(String ticker, Bar latest, int lookbackBars) {
        if (ticker == null || latest == null || latest.close <= 0.0) return 0.0;
        double shared = sharedBarHistory.velocityPct(ticker, lookbackBars);
        if (Math.abs(shared) > 0.000001) return shared;
        List<Bar> history = scannerBarHistory.get(ticker.trim().toUpperCase());
        if (history == null || history.isEmpty()) return 0.0;
        List<Bar> copy;
        synchronized (history) {
            copy = new ArrayList<>(history);
        }
        copy.removeIf(b -> b == null || b.close <= 0.0);
        if (copy.isEmpty()) return 0.0;
        copy.sort(Comparator.comparingLong(b -> b.timestamp));
        Bar last = copy.get(copy.size() - 1);
        if (latest.timestamp != last.timestamp || Math.abs(latest.close - last.close) > 0.000001) {
            copy.add(latest);
        }
        if (copy.size() < 2) return 0.0;
        int idx = Math.max(0, copy.size() - 1 - Math.max(1, lookbackBars));
        Bar prior = copy.get(idx);
        if (prior == null || prior.close <= 0.0) return 0.0;
        return ((latest.close - prior.close) / prior.close) * 100.0;
    }

    private double scannerDiscoveryRankScore(String ticker, Bar latest, CachedBar previousCached) {
        if (ticker == null || ticker.isBlank() || latest == null || latest.close <= 0.0) {
            return 0.0;
        }
        String normalized = ticker.trim().toUpperCase();
        if (latest.close < minScanPrice || latest.close > maxScanPrice) {
            return 0.0;
        }
        // Static share-volume is optional legacy protection only. Default is 0;
        // adaptive RVOL/dollar-volume scoring below is the primary liquidity test.
        if (minLatestBarVolume > 0 && latest.volume < minLatestBarVolume) {
            return 0.0;
        }
        CachedBar previous = previousCached;
        double priceVelocity = 0.0;
        double rangePct = 0.0;
        double volumeExpansion = 0.0;
        if (previous != null && previous.bar != null && previous.bar.close > 0.0) {
            priceVelocity = Math.abs((latest.close - previous.bar.close) / previous.bar.close) * 100.0;
            long priorVolume = Math.max(1L, previous.bar.volume);
            volumeExpansion = latest.volume / (double) priorVolume;
        }
        if (latest.low > 0.0 && latest.high > latest.low) {
            rangePct = ((latest.high - latest.low) / latest.low) * 100.0;
        }

        double dollarVolume = latest.close * latest.volume;
        double adaptiveDollarTarget = adaptiveDollarVolumeTarget(latest.close);
        double dollarVolumeScore = clamp01(Math.log10(Math.max(1.0, dollarVolume)) / Math.log10(Math.max(50_000.0, adaptiveDollarTarget * 30.0)));
        double volumeScore = clamp01(Math.log10(Math.max(1.0, latest.volume)) / Math.log10(1_000_000.0));
        double expansionScore = clamp01((volumeExpansion - 1.0) / 3.0);
        double velocityScore = clamp01(priceVelocity / 1.15);
        double rangeScore = clamp01(rangePct / 2.5);
        double adaptiveLiquidityScore = clamp01(dollarVolume / adaptiveDollarTarget);

        return clamp01(volumeScore * 0.12
                + dollarVolumeScore * 0.22
                + adaptiveLiquidityScore * 0.14
                + expansionScore * 0.22
                + velocityScore * 0.20
                + rangeScore * 0.10);
    }


    private static double adaptiveDollarVolumeTarget(double price) {
        if (price >= 250.0) return 80_000.0;
        if (price >= 100.0) return 110_000.0;
        if (price >= 25.0) return 150_000.0;
        if (price >= 5.0) return 100_000.0;
        if (price >= 1.0) return 65_000.0;
        return 30_000.0;
    }



    private void candidatesAddLifecycle(List<ScanCandidate> cycleLeaderboard,
                                        String ticker,
                                        Bar bar,
                                        double rankScore,
                                        MomentumDiscoveryEngine.MomentumDiscoveryProfile profile,
                                        EntryStagingAgent.Decision stagingDecision,
                                        boolean executionReady,
                                        boolean trackedCandidate,
                                        boolean freshTradable,
                                        TechnicalFeatureSnapshot sharedFeatures) {
        if (cycleLeaderboard == null || ticker == null || ticker.isBlank() || bar == null || bar.close <= 0.0) {
            return;
        }
        MomentumDiscoveryEngine.MomentumDiscoveryProfile safeProfile = profile;
        TechnicalFeatureSnapshot safeFeatures = sharedFeatures;
        double relativeVolume = safeFeatures != null && safeFeatures.relativeVolume > 0.0
                ? safeFeatures.relativeVolume
                : (safeProfile == null ? 0.0 : safeProfile.relativeVolume);
        double liveVelocity = safeFeatures != null && Math.abs(safeFeatures.oneBarVelocityPct) > 0.000001
                ? safeFeatures.oneBarVelocityPct
                : (safeProfile == null ? 0.0 : safeProfile.liveVelocityPct);
        double fastVelocity = safeFeatures != null && Math.abs(safeFeatures.threeBarVelocityPct) > 0.000001
                ? safeFeatures.threeBarVelocityPct
                : (safeProfile == null ? 0.0 : safeProfile.fastVelocityPct);
        double range = safeFeatures != null && safeFeatures.rangePct > 0.0
                ? safeFeatures.rangePct
                : (safeProfile == null ? 0.0 : safeProfile.rangePct);
        double dollarVolume = safeFeatures != null && safeFeatures.dollarVolume > 0.0
                ? safeFeatures.dollarVolume
                : (safeProfile == null ? 0.0 : safeProfile.dollarVolume);
        String reason = (safeProfile == null ? "" : safeProfile.reason)
                + " staging=" + (stagingDecision == null ? "NONE" : stagingDecision.action + ":" + stagingDecision.reason)
                + " sharedBars=" + (safeFeatures == null ? 0 : safeFeatures.bars)
                + " sharedV1=" + fmt(liveVelocity) + "%"
                + " sharedV3=" + fmt(fastVelocity) + "%";
        cycleLeaderboard.add(new ScanCandidate(ticker, bar, rankScore, reason,
                executionReady, trackedCandidate, freshTradable,
                relativeVolume, liveVelocity, fastVelocity, range, dollarVolume));
    }

    private List<LiveMomentumLeaderboard.Entry> toLeaderboardEntries(List<ScanCandidate> candidates) {
        List<LiveMomentumLeaderboard.Entry> entries = new ArrayList<>();
        if (candidates == null) {
            return entries;
        }
        for (ScanCandidate c : candidates) {
            if (c == null) continue;
            entries.add(new LiveMomentumLeaderboard.Entry(
                    c.ticker,
                    c.bar,
                    c.score,
                    c.relativeVolume,
                    c.liveVelocityPct,
                    c.fastVelocityPct,
                    c.rangePct,
                    c.dollarVolume,
                    c.freshTradable,
                    c.executionReady,
                    c.trackedCandidate,
                    c.reason
            ));
        }
        return entries;
    }

    private String summarizeCandidates(List<ScanCandidate> candidates, int limit) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < candidates.size() && i < limit; i++) {
            if (i > 0) sb.append(", ");
            ScanCandidate c = candidates.get(i);
            sb.append(c.ticker).append(":").append(String.format(java.util.Locale.US, "%.3f", c.score));
        }
        sb.append("]");
        return sb.toString();
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.US, "%.3f", Double.isFinite(value) ? value : 0.0);
    }

    private void cacheBar(String ticker, Bar bar) {
        if (ticker == null || ticker.isBlank() || bar == null || bar.close <= 0) {
            return;
        }
        String normalized = ticker.trim().toUpperCase();
        barCache.put(normalized, new CachedBar(bar, System.currentTimeMillis()));
        if (barCache.size() > barCacheMaxSize) {
            pruneBarCache();
        }
    }

    private void pruneBarCache() {
        long now = System.currentTimeMillis();
        barCache.entrySet().removeIf(entry -> now - entry.getValue().cachedAt > barCacheTtlMs * 2L);
        if (barCache.size() <= barCacheMaxSize) {
            return;
        }
        List<Map.Entry<String, CachedBar>> entries = new ArrayList<>(barCache.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().cachedAt, b.getValue().cachedAt));
        int removeCount = Math.max(0, barCache.size() - barCacheMaxSize);
        for (int i = 0; i < removeCount && i < entries.size(); i++) {
            barCache.remove(entries.get(i).getKey());
        }
    }

    private List<String> nextSymbolBatch() {
        List<String> currentUniverse = universe;
        if (currentUniverse == null || currentUniverse.isEmpty()) {
            return Collections.emptyList();
        }

        int size = currentUniverse.size();
        int start = Math.floorMod(cursor.getAndAdd(rawSymbolsPerCycle), size);
        LinkedHashSet<String> batch = new LinkedHashSet<>(rawSymbolsPerCycle);

        // Prioritize symbols already in the unified opportunity lifecycle so
        // scanner-discovered names keep getting fresh bars instead of staying
        // frozen at bars=1/5 while the universe cursor moves on.
        for (String lifecycle : opportunityContextRegistry.topSymbols(Math.max(25, symbolsPerCycle))) {
            if (batch.size() >= symbolsPerCycle) {
                break;
            }
            if (lifecycle != null && !lifecycle.isBlank() && !isTemporarilyBad(lifecycle)) {
                batch.add(lifecycle);
            }
        }

        // Prioritize symbols that recently produced usable bars, then keep rotating
        // through the broader universe to discover new active names. The LinkedHashSet
        // avoids O(n) duplicate checks and prevents active symbols from being fetched
        // twice in the same Alpaca batch.
        for (String tracked : momentumCandidateTracker.topSymbols(Math.max(25, symbolsPerCycle))) {
            if (batch.size() >= symbolsPerCycle) {
                break;
            }
            if (tracked != null && !tracked.isBlank() && !isTemporarilyBad(tracked)) {
                batch.add(tracked);
            }
        }

        for (String topVolume : parabolicTopVolumeTracker.topSymbols(Math.max(25, symbolsPerCycle))) {
            if (batch.size() >= symbolsPerCycle) {
                break;
            }
            if (topVolume != null && !topVolume.isBlank() && !isTemporarilyBad(topVolume)) {
                batch.add(topVolume);
            }
        }

        for (String staged : entryStagingAgent.topSymbols(Math.max(25, symbolsPerCycle))) {
            if (batch.size() >= symbolsPerCycle) {
                break;
            }
            if (staged != null && !staged.isBlank() && !isTemporarilyBad(staged)) {
                batch.add(staged);
            }
        }

        for (String active : activeSymbols) {
            if (batch.size() >= symbolsPerCycle) {
                break;
            }
            if (active != null && !active.isBlank() && !isTemporarilyBad(active)) {
                batch.add(active);
            }
        }

        for (int i = 0; batch.size() < rawSymbolsPerCycle && i < size; i++) {
            String symbol = currentUniverse.get((start + i) % size);
            if (isTemporarilyBad(symbol)) {
                continue;
            }
            batch.add(symbol);
        }

        return new ArrayList<>(batch);
    }


    private void recordNoDataStrike(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }
        int strikes = noDataStrikes.merge(ticker, 1, Integer::sum);
        if (strikes >= MAX_NO_DATA_STRIKES) {
            activeSymbols.remove(ticker);
        }
    }

    private void clearNoDataStrike(String ticker) {
        if (ticker != null && !ticker.isBlank()) {
            noDataStrikes.remove(ticker);
        }
    }


    private static TradeDirection inferScannerDirection(MomentumDiscoveryEngine.MomentumDiscoveryProfile profile) {
        if (profile == null) {
            return TradeDirection.LONG_STOCK;
        }
        if (profile.liveVelocityPct < -0.20 || profile.fastVelocityPct < -0.35) {
            return TradeDirection.SHORT_STOCK;
        }
        return TradeDirection.LONG_STOCK;
    }


    private static String classifyDiscoveryRejection(MomentumDiscoveryEngine.MomentumDiscoveryProfile profile,
                                                     double scanScore,
                                                     boolean freshTradable,
                                                     boolean priceTradable) {
        if (!priceTradable) return "PRICE_OR_SYMBOL";
        if (!freshTradable) return "STALE_BAR";
        if (profile == null) return "NO_PROFILE";
        String reason = profile.reason == null ? "" : profile.reason.toLowerCase(java.util.Locale.ROOT);
        if (reason.contains("liquidity") || reason.contains("dollarvolume")) return "LIQUIDITY";
        if (reason.contains("rvol") || reason.contains("relative")) return "RVOL";
        if (reason.contains("velocity")) return "PRICE_VELOCITY";
        if (reason.contains("range") || reason.contains("atr")) return "RANGE_ATR";
        if (scanScore < 0.10) return "NO_MOMENTUM";
        return "SCORE_BELOW_DISCOVERY";
    }

    private static String summarizeRejections(Map<String, Integer> reasons, int limit) {
        if (reasons == null || reasons.isEmpty()) {
            return "[]";
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(reasons.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size() && i < limit; i++) {
            if (i > 0) sb.append(", ");
            Map.Entry<String, Integer> e = list.get(i);
            sb.append(e.getKey()).append(":").append(e.getValue());
        }
        sb.append("]");
        return sb.toString();
    }

    private static final class ScanCandidate {
        private final String ticker;
        private final Bar bar;
        private final double score;
        private final String reason;
        private final boolean executionReady;
        private final boolean trackedCandidate;
        private final boolean freshTradable;
        private final double relativeVolume;
        private final double liveVelocityPct;
        private final double fastVelocityPct;
        private final double rangePct;
        private final double dollarVolume;

        private ScanCandidate(String ticker, Bar bar, double score, String reason,
                              boolean executionReady, boolean trackedCandidate, boolean freshTradable,
                              double relativeVolume, double liveVelocityPct, double fastVelocityPct,
                              double rangePct, double dollarVolume) {
            this.ticker = ticker;
            this.bar = bar;
            this.score = score;
            this.reason = reason == null ? "" : reason;
            this.executionReady = executionReady;
            this.trackedCandidate = trackedCandidate;
            this.freshTradable = freshTradable;
            this.relativeVolume = relativeVolume;
            this.liveVelocityPct = liveVelocityPct;
            this.fastVelocityPct = fastVelocityPct;
            this.rangePct = rangePct;
            this.dollarVolume = dollarVolume;
        }
    }

    private static final class CachedBar {
        private final Bar bar;
        private final long cachedAt;

        private CachedBar(Bar bar, long cachedAt) {
            this.bar = bar;
            this.cachedAt = cachedAt;
        }
    }

    private boolean isTemporarilyBad(String ticker) {
        return noDataStrikes.getOrDefault(ticker, 0) >= MAX_NO_DATA_STRIKES;
    }

    private void sleepRandomScanDelay() {
        long delay = ThreadLocalRandom.current().nextLong(minScanMs, maxScanMs + 1L);
        sleepQuietly(delay);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim());
    }
}
