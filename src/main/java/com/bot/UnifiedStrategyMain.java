package com.bot;

import com.bot.broker.AlpacaAccountService;
import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.execution.unified.UnifiedSignalRouter;
import com.bot.intelligence.MarketRegimeEngine;
import com.bot.intelligence.MarketFeatureBus;
import com.bot.intelligence.MarketStateDatabase2;
import com.bot.intelligence.OpportunityMemoryService;
import com.bot.intelligence.WorldModelAgent;
import com.bot.intelligence.PredictiveOpportunityRanker;
import com.bot.intelligence.StockMemoryService;
import com.bot.intelligence.bus.ExternalDataSourceManager;
import com.bot.intelligence.bus.MarketIntelligenceBus;
import com.bot.intelligence.bus.PolygonFirstMarketDataService;
import com.bot.journal.TradeJournal;
import com.bot.intelligence.SelfTrainingOptimizer;
import com.bot.master.MasterStrategyEngine;
import com.bot.master.MasterStrategyDecision;
import com.bot.master.StrategyAction;
import com.bot.master.CatalystQualityGate;
import com.bot.model.AccountService;
import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.news.FinBertService;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.scanner.FullMarketStrategyScanner;
import com.bot.scanner.MomentumDiscoveryEngine;
import com.bot.scanner.MomentumCandidateTracker;
import com.bot.stream.AlpacaNewsWebSocketStream;
import com.bot.stream.AlpacaSymbolFilter;
import com.bot.stream.BenzingaInstitutionalPressReleaseRestPollingStream;
import com.bot.stream.BenzingaInstitutionalPressReleaseWebSocketStream;
import com.bot.stream.BenzingaNewsRestPollingStream;
import com.bot.stream.BenzingaNewsWebSocketStream;
import com.bot.stream.FreshnessFilteredNewsRouter;
import com.bot.stream.MultiSourceNewsFreshnessEngine;
import com.bot.stream.NewsPriorityGate;
import com.bot.stream.PriceStreamRegistry;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class UnifiedStrategyMain {

    private static final long DEFAULT_MARKET_NEWS_POLL_MS = 30_000L;
    private static final long DEFAULT_HEARTBEAT_MS = 10_000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);
    private static final AtomicBoolean SHUTDOWN_STARTED = new AtomicBoolean(false);
    private static final List<Runnable> MANAGED_STOPS = Collections.synchronizedList(new ArrayList<>());
    private static final List<Thread> BACKGROUND_THREADS = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        RUNNING.set(true);
        installUnifiedShutdownHook();
        startNightlyAutonomousShutdownWatcher();

        boolean tradingEnabled = "true".equalsIgnoreCase(env("TRADING_ENABLED", "false"));
        boolean dryRun = !"false".equalsIgnoreCase(env("DRY_RUN", "true"));
        boolean allowOrders = tradingEnabled && !dryRun;
        boolean signalMode = !allowOrders;

        System.out.println("Unified Strategy Main started.");
        System.out.println("Strategies: MARKET_INTELLIGENCE_AI + expanded strategy pack: Panic Reversal, Momentum News Runner, Short Squeeze, Short Alpha Breakdown, Gap Fill, VWAP Reclaim, Failed Breakdown, Opening Range Breakout/Breakdown, High-Tight Flag, VWAP Pullback, Inside-Bar Expansion, Range Expansion, Volume Climax Reversal, Parabolic Exhaustion Short, Parabolic Bi-Directional Momentum Agent, Red-to-Green, Green-to-Red Short, Trend Pullback, Relative Strength Breakout, Range Mean Reversion, Low-Price Momentum Ignition, Earnings Continuation, Contract Award Momentum, FDA Approval Momentum, Pre-Catalyst Prediction Agent, Offering Fade Short, Failed VWAP Short, Liquidity Sweep Reversal");
        System.out.println("TRADING_ENABLED=" + tradingEnabled + " DRY_RUN=" + dryRun + " allowOrders=" + allowOrders);

        if (dryRun) {
            System.out.println("DRY RUN MODE ENABLED: signals and simulated fills only.");
        } else if (allowOrders) {
            System.out.println("PAPER ORDER MODE ENABLED: live Alpaca paper orders allowed.");
        } else {
            System.out.println("ORDER EXECUTION DISABLED: unified signal processing only.");
        }

        Path liveTradingLock = acquireLiveTradingLock(allowOrders);

        AlpacaBroker broker = new AlpacaBroker(dryRun);
        MarketDataCache marketData = new MarketDataCache();
        TradeJournal journal = new TradeJournal();
        OrderExecutor orderExecutor = new OrderExecutor(broker, journal, allowOrders);

        PositionManager positionManager = new PositionManager(marketData, orderExecutor, broker::getPrice);
        positionManager.syncFromBroker(broker.getOpenPositions());

        AccountService accountService = new AlpacaAccountService(broker);
        AdvancedRiskEngine riskEngine = new AdvancedRiskEngine(accountService, positionManager);
        FinBertService finBertService = new FinBertService();

        MasterStrategyEngine master = new MasterStrategyEngine(
                riskEngine,
                orderExecutor,
                positionManager,
                marketData,
                finBertService
        );

        System.out.println("QUANT INTELLIGENCE LIVE INTEGRATION ENABLED");
        System.out.println("UnifiedStrategyMain is the single live runner.");
        System.out.println("QuantIntelligenceMain is now only a compatibility wrapper that delegates to UnifiedStrategyMain.");
        System.out.println("Feature journal: " + env("FEATURE_JOURNAL_PATH", "logs/market_features.csv"));
        System.out.println("Outcome journal: " + env("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv"));
        System.out.println("Probability model: HeuristicProbabilityModel");
        System.out.println("Adaptive policy path: " + env("AI_POLICY_PATH", "logs/ai_policy.properties"));
        System.out.println("AI self-training enabled: " + env("AI_SELF_TRAINING_ENABLED", "true"));
        System.out.println("Autonomous source evolution: OFFLINE ONLY. Run com.bot.intelligence.AutonomousCodeEvolutionMain after trading to regenerate bounded AI policy source.");
        if (!"false".equalsIgnoreCase(env("AI_SELF_TRAINING_ENABLED", "true"))) {
            new SelfTrainingOptimizer().start();
        }

        PriceStreamRegistry priceStreamRegistry = new PriceStreamRegistry(
                broker,
                marketData,
                positionManager,
                envInt("UNIFIED_PRICE_POLL_SECONDS", 1)
        );
        registerManagedStop("price-stream-registry", priceStreamRegistry::stopAll);

        priceStreamRegistry.startTrackingAll(positionManager.allPositions());

        UnifiedSignalRouter router = new UnifiedSignalRouter(
                master,
                allowOrders,
                priceStreamRegistry
        );

        AtomicLong cachedEquity = new AtomicLong(Double.doubleToLongBits(loadAccountEquity(accountService, 100_000.00)));

        priceStreamRegistry.addBarListener((ticker, bar) -> {
            try {
                if (ticker != null && bar != null) {
                    marketData.addBar(ticker, bar);
                    MarketFeatureBus.getInstance().publishBar("UNIFIED_PRICE_TRACKING_CANDIDATE", ticker, bar);
                    MomentumDiscoveryEngine.MomentumDiscoveryProfile profile = new MomentumDiscoveryEngine().evaluate(ticker, marketData, bar);
                    MomentumCandidateTracker.getInstance().observeBar(ticker, bar, profile);
                    if (!profile.pass && !MomentumCandidateTracker.getInstance().shouldRoute(ticker, profile)) {
                        return;
                    }
                }
                router.onTickerUpdate(ticker, Double.longBitsToDouble(cachedEquity.get()));
            } catch (Exception e) {
                System.err.println("UNIFIED BAR RE-EVALUATION ERROR: ticker=" + ticker + " error=" + e.getMessage());
            }
        });

        MultiSourceNewsFreshnessEngine freshnessEngine = new MultiSourceNewsFreshnessEngine();
        FreshnessFilteredNewsRouter freshnessRouter = new FreshnessFilteredNewsRouter(
                freshnessEngine,
                news -> handleLiveNews(news, broker, marketData, router, accountService, cachedEquity, priceStreamRegistry)
        );

        MarketIntelligenceBus marketIntelligenceBus = MarketIntelligenceBus.getInstance();
        marketIntelligenceBus.start();
        marketIntelligenceBus.setNewsDownstream(freshnessRouter::onNews);
        WorldModelAgent worldModelAgent = WorldModelAgent.getInstance();
        OpportunityMemoryService opportunityMemoryService = OpportunityMemoryService.getInstance();
        worldModelAgent.start();
        opportunityMemoryService.start();
        marketIntelligenceBus.registerSignalConsumer(worldModelAgent::observeSignal);
        marketIntelligenceBus.registerSignalConsumer(opportunityMemoryService::observeSignal);
        registerManagedStop("market-intelligence-bus", marketIntelligenceBus::stop);

        ExternalDataSourceManager externalDataSourceManager = new ExternalDataSourceManager();
        externalDataSourceManager.start();
        registerManagedStop("external-data-source-manager", externalDataSourceManager::stop);

        startNewsIntake(marketIntelligenceBus::publishNews);
        startBroadMarketNewsPolling(broker, marketIntelligenceBus::publishNews);
        startFullMarketStrategyScanning(broker, marketData, router, cachedEquity);
        startPositionResyncLoop(broker, positionManager, priceStreamRegistry);
        startHeartbeat(positionManager, priceStreamRegistry, cachedEquity, signalMode);

        System.out.println("Unified strategy engine is LIVE and will stay active.");
        System.out.println("News feeds are connected. Full-market strategy scanner is also running on the configured throttled cadence for non-news setups.");

        keepAliveForever();
    }

    private static void handleLiveNews(
            NewsEvent news,
            AlpacaBroker broker,
            MarketDataCache marketData,
            UnifiedSignalRouter router,
            AccountService accountService,
            AtomicLong cachedEquity,
            PriceStreamRegistry priceStreamRegistry
    ) {
        try {
            if (news == null || news.getTicker() == null || news.getTicker().isBlank()) {
                return;
            }

            String ticker = AlpacaSymbolFilter.normalize(news.getTicker());
            news.setTicker(ticker);
            StockMemoryService.getInstance().observeNews(news);
            OpportunityMemoryService.getInstance().observeNews(news);
            WorldModelAgent.getInstance().observeNews(news);

            boolean syntheticMarketStateOpportunity = CatalystQualityGate.isSyntheticMarketStateOpportunity(news);
            boolean highPriorityInterrupt = NewsPriorityGate.isHighPriorityCatalyst(news) || syntheticMarketStateOpportunity;
            if (syntheticMarketStateOpportunity && !passesSyntheticMomentumPreGate(ticker)) {
                System.out.println("UNIFIED SYNTHETIC STATE NEWS REJECTED BEFORE STRATEGY: ticker=" + ticker +
                        " reason=NO_TRUE_VOLUME_VOLATILITY_IGNITION headline=" + news.getHeadline());
                return;
            }
            if (syntheticMarketStateOpportunity && news.getCatalystScore() <= 0.0) {
                news.setCatalystScore(CatalystQualityGate.tradeableCatalystScore(news));
            }
            if (highPriorityInterrupt) {
                System.out.println("UNIFIED HIGH PRIORITY CATALYST PATH: ticker=" + ticker + " source=" + news.getSource() + " headline=" + news.getHeadline());
            }

            String preNlpRejectReason = CatalystQualityGate.preNlpRejectReason(news);
            if (preNlpRejectReason != null && !highPriorityInterrupt) {
                System.out.println("UNIFIED NEWS REJECTED BEFORE NLP: ticker=" + ticker + " reason=" + preNlpRejectReason + " headline=" + news.getHeadline());
                return;
            }

            String tickerMismatchReason = headlineTickerMismatchReason(ticker, news);
            if (tickerMismatchReason != null && !syntheticMarketStateOpportunity) {
                System.out.println("UNIFIED NEWS REJECTED BEFORE STRATEGY: ticker=" + ticker + " reason=" + tickerMismatchReason + " headline=" + news.getHeadline());
                return;
            }

            String catalystRejectReason = CatalystQualityGate.rejectReason(news);
            if (catalystRejectReason != null && !highPriorityInterrupt) {
                System.out.println("UNIFIED NEWS REJECTED BEFORE STRATEGY: ticker=" + ticker + " reason=" + catalystRejectReason + " headline=" + news.getHeadline());
                return;
            }

            double catalystScore = CatalystQualityGate.tradeableCatalystScore(news);
            if (syntheticMarketStateOpportunity) {
                catalystScore = Math.max(catalystScore, envDouble("STATE_OPPORTUNITY_SYNTHETIC_CATALYST_SCORE", 0.42));
            }
            double minCatalystScore = highPriorityInterrupt
                    ? envDouble("MIN_HIGH_PRIORITY_STREAM_CATALYST_SCORE", 0.18)
                    : envDouble("MIN_STREAM_CATALYST_SCORE", 0.35);
            if (catalystScore < minCatalystScore) {
                double aiTriageFloor = highPriorityInterrupt
                        ? envDouble("OPENAI_NEWS_TRIAGE_HIGH_PRIORITY_MIN_CATALYST", 0.12)
                        : envDouble("OPENAI_NEWS_TRIAGE_MIN_CATALYST", 0.22);
                boolean forceAiTriage = envBool("OPENAI_TRIAGE_BORDERLINE_NEWS_BEFORE_REJECT", true)
                        && catalystScore >= aiTriageFloor
                        && AlpacaSymbolFilter.isEligibleStockSymbol(ticker);
                if (!forceAiTriage) {
                    System.out.println("UNIFIED NEWS REJECTED BEFORE STRATEGY: ticker=" + ticker + " reason=CATALYST_SCORE_TOO_LOW score=" + catalystScore + " min=" + minCatalystScore + " headline=" + news.getHeadline());
                    return;
                }
                System.out.println("UNIFIED BORDERLINE NEWS ESCALATED TO STRATEGY/AI: ticker=" + ticker + " score=" + catalystScore + " min=" + minCatalystScore + " triageFloor=" + aiTriageFloor + " headline=" + news.getHeadline());
            }

            news.setCatalystScore(catalystScore);
            StockMemoryService.getInstance().observeNews(news);
            double predictiveOpportunityScore = PredictiveOpportunityRanker.getInstance().score(ticker);
            System.out.println("UNIFIED LIVE NEWS: " + ticker + " catalystScore=" + String.format("%.2f", catalystScore) + " predictiveScore=" + String.format("%.2f", predictiveOpportunityScore) + " priority=" + NewsPriorityGate.priorityLabel(news) + " headline=" + news.getHeadline());

            boolean eligibleForStockPriceTracking = AlpacaSymbolFilter.isEligibleStockSymbol(ticker);
            if (!eligibleForStockPriceTracking) {
                System.out.println("UNIFIED NEWS REJECTED BEFORE STRATEGY: ticker=" + ticker + " reason=NOT_ALPACA_US_STOCK_SYMBOL_OR_MARKET_PROXY");
                return;
            }

            boolean hasValidMarketData = addFreshBarIfAvailable(broker, marketData, ticker);
            if (!hasValidMarketData) {
                hasValidMarketData = addEmergencyPolygonBarIfAvailable(marketData, ticker);
            }
            if (!hasValidMarketData) {
                System.out.println("UNIFIED NEWS REJECTED BEFORE STRATEGY: ticker=" + ticker + " reason=NO_VALID_MARKET_DATA headline=" + news.getHeadline());
                return;
            }

            if (envBool("UNIFIED_NEWS_REQUIRE_MOMENTUM_DISCOVERY", true)) {
                Bar latestBar = latestBar(marketData, ticker);
                MomentumDiscoveryEngine.MomentumDiscoveryProfile discovery = new MomentumDiscoveryEngine().evaluate(ticker, marketData, latestBar);
                boolean allowHighPriorityResearch = highPriorityInterrupt && envBool("UNIFIED_HIGH_PRIORITY_NEWS_CAN_RESEARCH_WITHOUT_DISCOVERY", false);
                if (!discovery.pass && !allowHighPriorityResearch) {
                    boolean tracked = MomentumCandidateTracker.getInstance().registerCatalyst(
                            ticker,
                            news.getHeadline(),
                            catalystScore,
                            predictiveOpportunityScore,
                            highPriorityInterrupt,
                            "news_failed_initial_discovery: " + discovery.reason
                    );
                    if (tracked && priceStreamRegistry != null && !priceStreamRegistry.isTracking(ticker)) {
                        priceStreamRegistry.startTracking(ticker);
                        System.out.println("UNIFIED NEWS CANDIDATE TRACKING STARTED: ticker=" + ticker +
                                " reason=STRONG_CATALYST_WAITING_FOR_MOMENTUM " + discovery.reason +
                                " headline=" + news.getHeadline());
                    } else {
                        System.out.println("UNIFIED NEWS REJECTED BEFORE STRATEGY: ticker=" + ticker +
                                " reason=NEWS_FAILED_MOMENTUM_DISCOVERY " + discovery.reason +
                                " headline=" + news.getHeadline());
                    }
                    return;
                }
                if (discovery.pass) {
                    System.out.println("UNIFIED NEWS MOMENTUM DISCOVERY PASSED: ticker=" + ticker +
                            " score=" + String.format("%.3f", discovery.score) + " " + discovery.reason);
                }
            }

            double equity = loadAccountEquity(accountService, Double.longBitsToDouble(cachedEquity.get()));
            cachedEquity.set(Double.doubleToLongBits(equity));

            MasterStrategyDecision newsDecision = router.onNews(news, equity);
            MasterStrategyDecision tickDecision = router.onTickerUpdate(ticker, equity);

            boolean buyDecision = (newsDecision != null && newsDecision.getAction() == StrategyAction.BUY)
                    || (tickDecision != null && tickDecision.getAction() == StrategyAction.BUY);
            boolean trackNonBuyNews = envBool("UNIFIED_NEWS_START_PRICE_TRACKING_FOR_NON_BUY", false);
            if (eligibleForStockPriceTracking && priceStreamRegistry != null && !priceStreamRegistry.isTracking(ticker)) {
                if (buyDecision || trackNonBuyNews) {
                    priceStreamRegistry.startTracking(ticker);
                } else {
                    System.out.println("UNIFIED PRICE TRACKING SKIPPED: ticker=" + ticker +
                            " reason=NO_BUY_DECISION_MOMENTUM_FIRST headline=" + news.getHeadline());
                }
            }
        } catch (Exception e) {
            System.err.println("UNIFIED LIVE NEWS HANDLER ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private static Bar latestBar(MarketDataCache marketData, String ticker) {
        if (marketData == null || ticker == null || ticker.isBlank()) {
            return null;
        }
        List<Bar> bars = marketData.recentBars(ticker.trim().toUpperCase(), 1);
        if (bars == null || bars.isEmpty()) {
            return null;
        }
        return bars.get(bars.size() - 1);
    }

    private static boolean passesSyntheticMomentumPreGate(String ticker) {
        MarketStateDatabase2.State state = MarketStateDatabase2.getInstance().snapshot(ticker);
        if (state == null) {
            return false;
        }
        double minScore = envDouble("SYNTHETIC_STATE_NEWS_MIN_SCORE", 0.72);
        double minVolume = envDouble("SYNTHETIC_STATE_NEWS_MIN_VOLUME", 250_000.0);
        double minAbsReturn = envDouble("SYNTHETIC_STATE_NEWS_MIN_ABS_RETURN_PCT", 2.0);
        double minRange = envDouble("SYNTHETIC_STATE_NEWS_MIN_RANGE_PCT", 2.5);
        double minParabolic = envDouble("SYNTHETIC_STATE_NEWS_MIN_PARABOLIC_SCORE", 0.55);
        boolean pass = state.opportunityScore >= minScore
                && state.volume >= minVolume
                && Math.abs(state.returnPct) >= minAbsReturn
                && state.rangePct >= minRange
                && state.parabolicScore >= minParabolic;
        if (!pass) {
            System.out.println("SYNTHETIC STATE MOMENTUM PRE-GATE FAILED: ticker=" + ticker +
                    " score=" + state.opportunityScore + " minScore=" + minScore +
                    " volume=" + state.volume + " minVolume=" + minVolume +
                    " returnPct=" + state.returnPct + " minAbsReturn=" + minAbsReturn +
                    " rangePct=" + state.rangePct + " minRange=" + minRange +
                    " parabolic=" + state.parabolicScore + " minParabolic=" + minParabolic);
        }
        return pass;
    }

    private static boolean addEmergencyPolygonBarIfAvailable(MarketDataCache marketData, String ticker) {
        try {
            Bar emergencyBar = PolygonFirstMarketDataService.getInstance().getEmergencyBar(ticker);
            if (emergencyBar == null || emergencyBar.close <= 0.0) {
                System.out.println("UNIFIED EMERGENCY MARKET DATA FAILED: ticker=" + ticker + " provider=POLYGON_FIRST");
                return false;
            }
            marketData.addBar(ticker, emergencyBar);
            StockMemoryService.getInstance().observeBar(ticker, emergencyBar);
            OpportunityMemoryService.getInstance().observeBar(ticker, emergencyBar);
            MarketRegimeEngine.getInstance().observeBar(ticker, emergencyBar);
            WorldModelAgent.getInstance().observeBar(ticker, emergencyBar);
            System.out.println("UNIFIED EMERGENCY MARKET DATA USED: ticker=" + ticker + " provider=POLYGON_FIRST close=" + emergencyBar.close + " volume=" + emergencyBar.volume);
            return true;
        } catch (Exception e) {
            System.out.println("UNIFIED EMERGENCY MARKET DATA ERROR: ticker=" + ticker + " error=" + e.getMessage());
            return false;
        }
    }

    private static boolean addFreshBarIfAvailable(
            AlpacaBroker broker,
            MarketDataCache marketData,
            String ticker
    ) {
        try {
            if (!AlpacaSymbolFilter.isEligibleStockSymbol(ticker)) {
                System.out.println("UNIFIED BAR UPDATE SKIPPED: ticker=" + ticker + " reason=NOT_ALPACA_US_STOCK_SYMBOL");
                return false;
            }

            long maxAgeMs = envLong("UNIFIED_NEWS_BAR_MAX_AGE_MS", 10 * 60_000L);
            Bar bar = broker.getLatestBar(ticker);

            boolean alpacaUsable = false;
            if (bar != null && bar.close > 0) {
                long ageMs = bar.timestamp > 0 ? Math.abs(System.currentTimeMillis() - bar.timestamp) : 0L;
                alpacaUsable = bar.timestamp <= 0 || ageMs <= maxAgeMs;
                if (!alpacaUsable) {
                    System.out.println("UNIFIED BAR UPDATE SKIPPED: ticker=" + ticker + " reason=STALE_ALPACA_BAR_TRYING_POLYGON ageMs=" + ageMs);
                }
            }

            if (!alpacaUsable) {
                Bar polygonBar = PolygonFirstMarketDataService.getInstance().getFreshBar(ticker, maxAgeMs);
                if (polygonBar != null && polygonBar.close > 0) {
                    bar = polygonBar;
                    System.out.println("UNIFIED MARKET DATA FALLBACK USED: ticker=" + ticker + " provider=POLYGON_FIRST close=" + polygonBar.close);
                }
            }

            if (bar == null || bar.close <= 0) {
                return false;
            }

            long ageMs = bar.timestamp > 0 ? Math.abs(System.currentTimeMillis() - bar.timestamp) : 0L;
            if (bar.timestamp > 0 && ageMs > maxAgeMs) {
                System.out.println("UNIFIED BAR UPDATE SKIPPED: ticker=" + ticker + " reason=STALE_BAR_ALL_PROVIDERS ageMs=" + ageMs);
                return false;
            }

            marketData.addBar(ticker, bar);
            StockMemoryService.getInstance().observeBar(ticker, bar);
            OpportunityMemoryService.getInstance().observeBar(ticker, bar);
            MarketRegimeEngine.getInstance().observeBar(ticker, bar);
            WorldModelAgent.getInstance().observeBar(ticker, bar);
            return true;
        } catch (Exception e) {
            System.err.println("UNIFIED BAR UPDATE ERROR: ticker=" + ticker + " error=" + e.getMessage());
            return false;
        }
    }

    private static String headlineTickerMismatchReason(String ticker, NewsEvent news) {
        if (ticker == null || ticker.isBlank() || news == null) {
            return null;
        }
        String headline = news.getHeadline() == null ? "" : news.getHeadline().toLowerCase(java.util.Locale.ROOT);
        if (headline.isBlank()) {
            return null;
        }

        String expected = impliedTickerFromHeadline(headline);
        if (expected == null || expected.isBlank()) {
            return null;
        }

        String normalized = AlpacaSymbolFilter.normalize(ticker);
        if (expected.equals(normalized)) {
            return null;
        }

        // Allow dual-class symbols for Alphabet/Google.
        if (("GOOG".equals(expected) || "GOOGL".equals(expected)) && ("GOOG".equals(normalized) || "GOOGL".equals(normalized))) {
            return null;
        }

        return "TICKER_HEADLINE_MISMATCH_EXPECTED_" + expected;
    }

    private static String impliedTickerFromHeadline(String headline) {
        if (headline.contains("nokia")) return "NOK";
        if (headline.contains("amazon") || headline.contains("aws ")) return "AMZN";
        if (headline.contains("tesla")) return "TSLA";
        if (headline.contains("micron")) return "MU";
        if (headline.contains("fedex")) return "FDX";
        if (headline.contains("adobe")) return "ADBE";
        if (headline.contains("uber")) return "UBER";
        if (headline.contains("alphabet") || headline.contains("google")) return "GOOG";
        if (headline.contains("nvidia")) return "NVDA";
        if (headline.contains("apple")) return "AAPL";
        if (headline.contains("microsoft")) return "MSFT";
        return null;
    }

    private static void startNewsIntake(java.util.function.Consumer<NewsEvent> newsSink) {
        boolean benzingaEnabled = "true".equalsIgnoreCase(env("BENZINGA_NEWS_ENABLED", hasBenzingaToken() ? "true" : "false"));

        String defaultNewsSourceMode;
        if (benzingaEnabled) {
            defaultNewsSourceMode = "DUAL_SOURCE";
        } else if (hasBenzingaPressReleaseToken()) {
            defaultNewsSourceMode = "BENZINGA_PRESS_RELEASE_ONLY";
        } else {
            defaultNewsSourceMode = "ALPACA_ONLY";
        }

        String newsSourceMode = env("NEWS_SOURCE_MODE", defaultNewsSourceMode).trim().toUpperCase();
        if (newsSourceMode.equals("MIXED")) {
            newsSourceMode = "DUAL_SOURCE";
        }

        if (!newsSourceMode.equals("BENZINGA_ONLY") &&
                !newsSourceMode.equals("ALPACA_ONLY") &&
                !newsSourceMode.equals("DUAL_SOURCE") &&
                !newsSourceMode.equals("BENZINGA_PRESS_RELEASE_ONLY")) {
            System.out.println("Unknown NEWS_SOURCE_MODE=" + newsSourceMode + "; defaulting to " + defaultNewsSourceMode);
            newsSourceMode = defaultNewsSourceMode;
        }

        boolean institutionalPressReleaseOnlyMode = newsSourceMode.equals("BENZINGA_PRESS_RELEASE_ONLY");
        boolean startAlpacaNews = !institutionalPressReleaseOnlyMode &&
                (newsSourceMode.equals("ALPACA_ONLY") || newsSourceMode.equals("DUAL_SOURCE"));
        boolean startBenzingaNews = benzingaEnabled &&
                !institutionalPressReleaseOnlyMode &&
                !newsSourceMode.equals("ALPACA_ONLY");

        boolean benzingaRestPollingEnabled = startBenzingaNews &&
                "true".equalsIgnoreCase(env("BENZINGA_REST_POLLING_ENABLED", "true"));

        boolean benzingaPressReleasesEnabled = institutionalPressReleaseOnlyMode ||
                "true".equalsIgnoreCase(env("BENZINGA_PRESS_RELEASES_ENABLED", hasBenzingaPressReleaseToken() ? "true" : "false"));

        boolean singleBenzingaWebSocketMode =
                "true".equalsIgnoreCase(env("BENZINGA_WS_SINGLE_CONNECTION_MODE", "true"));

        boolean startBenzingaPressReleaseWebSocket = benzingaPressReleasesEnabled &&
                "true".equalsIgnoreCase(env("BENZINGA_PRESS_RELEASE_WS_ENABLED", "true"));

        if (singleBenzingaWebSocketMode && startBenzingaNews && startBenzingaPressReleaseWebSocket) {
            startBenzingaPressReleaseWebSocket = false;
            System.out.println(
                    "BENZINGA_WS_SINGLE_CONNECTION_MODE=true: disabling separate press-release WebSocket " +
                            "to avoid 429 rate limits. Press releases remain covered by REST polling."
            );
        }

        boolean startBenzingaPressReleaseRestPolling = benzingaPressReleasesEnabled &&
                "true".equalsIgnoreCase(env("BENZINGA_PRESS_RELEASE_REST_POLLING_ENABLED", "true"));

        System.out.println("Unified news intake mode: " + newsSourceMode);
        System.out.println("START_ALPACA_NEWS=" + startAlpacaNews);
        System.out.println("START_BENZINGA_NEWS=" + startBenzingaNews);
        System.out.println("BENZINGA_WS_SINGLE_CONNECTION_MODE=" + singleBenzingaWebSocketMode);
        System.out.println("START_BENZINGA_PRESS_RELEASE_WS=" + startBenzingaPressReleaseWebSocket);
        System.out.println("START_BENZINGA_PRESS_RELEASE_REST=" + startBenzingaPressReleaseRestPolling);

        if (startAlpacaNews) {
            try {
                AlpacaNewsWebSocketStream alpacaNewsStream = new AlpacaNewsWebSocketStream(newsSink);
                registerManagedStop("alpaca-news-websocket", alpacaNewsStream::stop);
                alpacaNewsStream.start();
            } catch (Exception e) {
                System.err.println("ALPACA NEWS STARTUP FAILED: " + e.getMessage());
            }
        }

        if (startBenzingaNews) {
            try {
                BenzingaNewsWebSocketStream benzingaNewsStream = new BenzingaNewsWebSocketStream(newsSink);
                registerManagedStop("benzinga-news-websocket", benzingaNewsStream::stop);
                benzingaNewsStream.start();

                if (benzingaRestPollingEnabled) {
                    BenzingaNewsRestPollingStream restPollingStream = new BenzingaNewsRestPollingStream(newsSink);
                    registerManagedStop("benzinga-news-rest-polling", restPollingStream::stop);
                    restPollingStream.start();
                }
            } catch (Exception e) {
                System.err.println("BENZINGA NEWS STARTUP FAILED: " + e.getMessage());
            }
        }

        if (benzingaPressReleasesEnabled) {
            try {
                if (startBenzingaPressReleaseWebSocket) {
                    BenzingaInstitutionalPressReleaseWebSocketStream pressReleaseWebSocketStream =
                            new BenzingaInstitutionalPressReleaseWebSocketStream(newsSink);
                    registerManagedStop("benzinga-press-release-websocket", pressReleaseWebSocketStream::stop);
                    pressReleaseWebSocketStream.start();
                }

                if (startBenzingaPressReleaseRestPolling) {
                    BenzingaInstitutionalPressReleaseRestPollingStream pressReleaseRestPollingStream =
                            new BenzingaInstitutionalPressReleaseRestPollingStream(newsSink);
                    registerManagedStop("benzinga-press-release-rest-polling", pressReleaseRestPollingStream::stop);
                    pressReleaseRestPollingStream.start();
                }
            } catch (Exception e) {
                System.err.println("BENZINGA PRESS RELEASE STARTUP FAILED: " + e.getMessage());
            }
        }
    }

    private static void startBroadMarketNewsPolling(
            AlpacaBroker broker,
            java.util.function.Consumer<NewsEvent> newsSink
    ) {
        if (!"true".equalsIgnoreCase(env("UNIFIED_BROAD_NEWS_POLLING_ENABLED", "true"))) {
            System.out.println("Unified broad market news polling disabled.");
            return;
        }

        long pollMs = envLong("UNIFIED_BROAD_NEWS_POLL_MS", DEFAULT_MARKET_NEWS_POLL_MS);
        int limit = envInt("UNIFIED_BROAD_NEWS_LIMIT", 20);

        Thread thread = new Thread(() -> {
            java.util.Set<String> routedArticleKeys = new java.util.LinkedHashSet<>();
            java.util.Set<String> rejectedArticleKeys = new java.util.LinkedHashSet<>();
            int maxCacheSize = envInt("UNIFIED_BROAD_NEWS_ARTICLE_CACHE_MAX", 5_000);

            while (RUNNING.get()) {
                try {
                    List<NewsEvent> events = broker.getLatestMarketNews(limit);
                    for (NewsEvent event : events) {
                        String articleKey = articleWideKey(event);
                        if (!articleKey.isBlank() && (routedArticleKeys.contains(articleKey) || rejectedArticleKeys.contains(articleKey))) {
                            continue;
                        }

                        String articleRejectReason = articleWideRejectReason(event);
                        if (articleRejectReason != null) {
                            if (!articleKey.isBlank()) {
                                rejectedArticleKeys.add(articleKey);
                                trimLinkedHashSet(rejectedArticleKeys, maxCacheSize);
                            }
                            System.out.println(
                                    "UNIFIED BROAD NEWS REJECTED BEFORE_TICKER_ROUTING: reason=" +
                                            articleRejectReason +
                                            " headline=" +
                                            (event == null ? "" : event.getHeadline())
                            );
                            continue;
                        }

                        if (!articleKey.isBlank()) {
                            routedArticleKeys.add(articleKey);
                            trimLinkedHashSet(routedArticleKeys, maxCacheSize);
                        }
                        newsSink.accept(event);
                    }
                    Thread.sleep(Math.max(1_000L, pollMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("UNIFIED BROAD NEWS POLLING ERROR: " + e.getMessage());
                    sleepQuietly(Math.max(5_000L, pollMs));
                }
            }
        });

        thread.setName("unified-broad-market-news-polling");
        thread.setDaemon(true);
        registerBackgroundThread(thread);
        thread.start();

        System.out.println("Unified broad market news polling started: pollMs=" + pollMs + " limit=" + limit + " priority=LOW_PRIORITY_REST_BACKUP");
    }



    private static void trimLinkedHashSet(java.util.Set<String> set, int maxSize) {
        if (set == null || maxSize <= 0) {
            return;
        }
        while (set.size() > maxSize) {
            java.util.Iterator<String> iterator = set.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private static String articleWideRejectReason(NewsEvent event) {
        if (event == null) {
            return "EMPTY_NEWS_EVENT";
        }

        String preNlpReason = CatalystQualityGate.preNlpRejectReason(event);
        if (preNlpReason != null) {
            return preNlpReason;
        }

        return CatalystQualityGate.rejectReason(event);
    }

    private static String articleWideKey(NewsEvent event) {
        if (event == null || event.getHeadline() == null) {
            return "";
        }

        return event.getHeadline()
                .trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static void startFullMarketStrategyScanning(
            AlpacaBroker broker,
            MarketDataCache marketData,
            UnifiedSignalRouter router,
            AtomicLong cachedEquity
    ) {
        if (!"true".equalsIgnoreCase(env("FULL_MARKET_STRATEGY_SCANNER_ENABLED", "true"))) {
            System.out.println("Full-market strategy scanner disabled.");
            return;
        }

        try {
            FullMarketStrategyScanner scanner = new FullMarketStrategyScanner(
                    broker,
                    marketData,
                    router,
                    cachedEquity
            );
            registerManagedStop("full-market-strategy-scanner", scanner::stop);
            scanner.start();
        } catch (Exception e) {
            System.err.println("FULL MARKET STRATEGY SCANNER STARTUP FAILED: " + e.getMessage());
        }
    }

    private static void startPositionResyncLoop(
            AlpacaBroker broker,
            PositionManager positionManager,
            PriceStreamRegistry priceStreamRegistry
    ) {
        long resyncMs = envLong("UNIFIED_POSITION_RESYNC_MS", 30_000L);

        Thread thread = new Thread(() -> {
            while (RUNNING.get()) {
                try {
                    positionManager.syncFromBroker(broker.getOpenPositions());
                    if (priceStreamRegistry.activeStreamCount() < positionManager.openPositionCount()) {
                        priceStreamRegistry.startTrackingAll(positionManager.allPositions());
                    }
                    Thread.sleep(Math.max(5_000L, resyncMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("UNIFIED POSITION RESYNC ERROR: " + e.getMessage());
                    sleepQuietly(10_000L);
                }
            }
        });

        thread.setName("unified-position-resync");
        thread.setDaemon(true);
        registerBackgroundThread(thread);
        thread.start();
    }

    private static void startHeartbeat(
            PositionManager positionManager,
            PriceStreamRegistry priceStreamRegistry,
            AtomicLong cachedEquity,
            boolean signalMode
    ) {
        long heartbeatMs = envLong("UNIFIED_HEARTBEAT_MS", DEFAULT_HEARTBEAT_MS);

        Thread thread = new Thread(() -> {
            while (RUNNING.get()) {
                try {
                    System.out.println(
                            "UNIFIED HEARTBEAT: active=true" +
                                    " mode=" + (signalMode ? "SIGNALS_ONLY" : "ORDER_EXECUTION") +
                                    " openPositions=" + positionManager.allPositions().size() +
                                    " activePriceStreams=" + priceStreamRegistry.activeStreamCount() +
                                    " cachedEquity=" + String.format("%.2f", Double.longBitsToDouble(cachedEquity.get()))
                    );
                    Thread.sleep(Math.max(1_000L, heartbeatMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("UNIFIED HEARTBEAT ERROR: " + e.getMessage());
                    sleepQuietly(5_000L);
                }
            }
        });

        thread.setName("unified-heartbeat");
        thread.setDaemon(true);
        registerBackgroundThread(thread);
        thread.start();
    }

    private static void startNightlyAutonomousShutdownWatcher() {
        if ("false".equalsIgnoreCase(env("AUTO_SHUTDOWN_AT_8PM", "true"))) {
            System.out.println("AUTO_SHUTDOWN_AT_8PM=false; nightly autonomous shutdown watcher disabled.");
            return;
        }

        Thread thread = new Thread(() -> {
            ZoneId zone = ZoneId.of(env("AUTONOMOUS_SCHEDULE_ZONE", "America/New_York"));
            LocalTime shutdownTime = parseLocalTime(env("AUTONOMOUS_SHUTDOWN_TIME", "20:00"), LocalTime.of(20, 0));

            while (true) {
                try {
                    ZonedDateTime now = ZonedDateTime.now(zone);
                    ZonedDateTime nextShutdown = now.with(shutdownTime);
                    if (!nextShutdown.isAfter(now)) {
                        nextShutdown = nextShutdown.plusDays(1);
                    }

                    long sleepMs = Math.max(1_000L, Duration.between(now, nextShutdown).toMillis());
                    System.out.println("Nightly autonomous shutdown scheduled for " + nextShutdown + " zone=" + zone);
                    Thread.sleep(sleepMs);

                    System.out.println("NIGHTLY AUTONOMOUS SHUTDOWN: UnifiedStrategyMain exiting so AutonomousCodeEvolutionMain can optimize offline.");
                    System.out.println("Set AUTO_SHUTDOWN_AT_8PM=false to disable this behavior.");
                    System.exit(0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("NIGHTLY SHUTDOWN WATCHER ERROR: " + e.getMessage());
                    sleepQuietly(60_000L);
                }
            }
        });

        thread.setName("nightly-autonomous-shutdown-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private static LocalTime parseLocalTime(String value, LocalTime fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalTime.parse(value.trim());
        } catch (Exception e) {
            System.err.println("Invalid local time value '" + value + "'; using fallback=" + fallback);
            return fallback;
        }
    }

    private static Path acquireLiveTradingLock(boolean allowOrders) {
        Path lockPath = Path.of(env("LIVE_TRADING_LOCK_PATH", "logs/live_trading.lock"));
        if (!allowOrders) {
            return lockPath;
        }
        try {
            Path parent = lockPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(lockPath,
                    "UnifiedStrategyMain live order execution active since " + System.currentTimeMillis() + System.lineSeparator());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(lockPath);
                    System.out.println("LIVE TRADING LOCK RELEASED: " + lockPath);
                } catch (Exception e) {
                    System.err.println("LIVE TRADING LOCK RELEASE FAILED: " + e.getMessage());
                }
            }, "live-trading-lock-release"));
            System.out.println("LIVE TRADING LOCK ACQUIRED: " + lockPath);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create live trading lock at " + lockPath + ": " + e.getMessage(), e);
        }
        return lockPath;
    }

    private static double loadAccountEquity(AccountService accountService, double fallback) {
        try {
            double equity = accountService.getEquity();
            return equity > 0 ? equity : fallback;
        } catch (Exception e) {
            System.err.println("UNIFIED ACCOUNT EQUITY ERROR: " + e.getMessage() + " usingFallback=" + fallback);
            return fallback;
        }
    }

    private static void keepAliveForever() {
        while (RUNNING.get()) {
            sleepQuietly(1_000L);
        }
    }

    private static void installUnifiedShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> requestShutdown("JVM shutdown / IntelliJ stop"), "unified-strategy-clean-shutdown"));
    }

    private static void requestShutdown(String reason) {
        if (!SHUTDOWN_STARTED.compareAndSet(false, true)) {
            return;
        }
        RUNNING.set(false);
        System.out.println("UNIFIED STRATEGY SHUTDOWN REQUESTED: reason=" + reason);
        synchronized (MANAGED_STOPS) {
            for (int i = MANAGED_STOPS.size() - 1; i >= 0; i--) {
                try {
                    MANAGED_STOPS.get(i).run();
                } catch (Exception e) {
                    System.err.println("UNIFIED MANAGED STOP ERROR: " + e.getMessage());
                }
            }
        }
        synchronized (BACKGROUND_THREADS) {
            for (Thread thread : BACKGROUND_THREADS) {
                try {
                    if (thread != null) {
                        thread.interrupt();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void registerManagedStop(String name, Runnable stopAction) {
        if (stopAction == null) {
            return;
        }
        MANAGED_STOPS.add(() -> {
            System.out.println("UNIFIED MANAGED STOP: " + name);
            stopAction.run();
        });
    }

    private static void registerBackgroundThread(Thread thread) {
        if (thread != null) {
            BACKGROUND_THREADS.add(thread);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasBenzingaToken() {
        String token = System.getenv("BENZINGA_NEWS_TOKEN");
        if (token != null && !token.isBlank()) {
            return true;
        }

        token = System.getenv("BENZINGA_API_KEY");
        return token != null && !token.isBlank();
    }

    private static boolean hasBenzingaPressReleaseToken() {
        String token = System.getenv("BENZINGA_PRESS_RELEASE_TOKEN");
        if (token != null && !token.isBlank()) {
            return true;
        }

        token = System.getenv("BENZINGA_INSTITUTIONAL_TOKEN");
        if (token != null && !token.isBlank()) {
            return true;
        }

        token = System.getenv("BENZINGA_INSTITUTIONAL_PRESS_RELEASE_TOKEN");
        return token != null && !token.isBlank();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
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


    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
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
}
