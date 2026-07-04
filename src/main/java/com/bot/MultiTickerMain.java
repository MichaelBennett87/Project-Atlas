package com.bot;

import com.bot.broker.AlpacaAccountService;
import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.AccountService;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.news.FinBertService;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.strategy.InstantNewsMomentumStrategy;
import com.bot.strategy.OpportunityRanker;
import com.bot.stream.AlpacaNewsWebSocketStream;
import com.bot.stream.BenzingaNewsWebSocketStream;
import com.bot.stream.BenzingaNewsRestPollingStream;
import com.bot.stream.BenzingaInstitutionalPressReleaseWebSocketStream;
import com.bot.stream.BenzingaInstitutionalPressReleaseRestPollingStream;
import com.bot.stream.FreshnessFilteredNewsRouter;
import com.bot.stream.MultiSourceNewsFreshnessEngine;
import com.bot.stream.PriceStreamRegistry;
import com.bot.intelligence.bus.ExternalDataSourceManager;
import com.bot.intelligence.bus.MarketIntelligenceBus;



public class MultiTickerMain {

    public static void main(String[] args) throws Exception {

        boolean tradingEnabled =
                "true".equalsIgnoreCase(System.getenv().getOrDefault("TRADING_ENABLED", "false"));

        boolean dryRun =
                !"false".equalsIgnoreCase(System.getenv().getOrDefault("DRY_RUN", "true"));

        boolean stockOrderExecutionEnabled =
                dryRun || (tradingEnabled && !dryRun);

        boolean relaxedDryRunRelevance =
                dryRun &&
                        !"false".equalsIgnoreCase(
                                System.getenv().getOrDefault("DRY_RUN_RELAXED_RELEVANCE", "true")
                        );

        System.out.println("MultiTickerMain started.");
        System.out.println("TRADING_ENABLED=" + tradingEnabled);
        System.out.println("DRY_RUN=" + dryRun);
        System.out.println("STOCK_ORDER_EXECUTION_ENABLED=" + stockOrderExecutionEnabled);
        System.out.println("SHORT_SELLING_ENABLED=false");
        System.out.println("OPTIONS_TRADING_ENABLED=false");
        System.out.println("DRY_RUN_RELAXED_RELEVANCE=" + relaxedDryRunRelevance);
        boolean benzingaEnabled =
                "true".equalsIgnoreCase(
                        System.getenv().getOrDefault(
                                "BENZINGA_NEWS_ENABLED",
                                hasBenzingaToken() ? "true" : "false"
                        )
                );

        String defaultNewsSourceMode;

        if (benzingaEnabled) {
            defaultNewsSourceMode = "DUAL_SOURCE";
        } else if (hasBenzingaPressReleaseToken()) {
            defaultNewsSourceMode = "BENZINGA_PRESS_RELEASE_ONLY";
        } else {
            defaultNewsSourceMode = "ALPACA_ONLY";
        }

        String newsSourceMode =
                System.getenv()
                        .getOrDefault(
                                "NEWS_SOURCE_MODE",
                                defaultNewsSourceMode
                        )
                        .trim()
                        .toUpperCase();

        if (newsSourceMode.equals("MIXED")) {
            newsSourceMode = "DUAL_SOURCE";
        }

        if (!newsSourceMode.equals("BENZINGA_ONLY") &&
                !newsSourceMode.equals("ALPACA_ONLY") &&
                !newsSourceMode.equals("DUAL_SOURCE") &&
                !newsSourceMode.equals("BENZINGA_PRESS_RELEASE_ONLY")) {
            System.out.println(
                    "Unknown NEWS_SOURCE_MODE=" +
                            newsSourceMode +
                            "; defaulting to " +
                            defaultNewsSourceMode
            );

            newsSourceMode = defaultNewsSourceMode;
        }

        boolean institutionalPressReleaseOnlyMode =
                newsSourceMode.equals("BENZINGA_PRESS_RELEASE_ONLY");

        boolean startBenzingaNews =
                benzingaEnabled &&
                        !institutionalPressReleaseOnlyMode &&
                        !newsSourceMode.equals("ALPACA_ONLY");

        boolean startAlpacaNews =
                !institutionalPressReleaseOnlyMode &&
                        (newsSourceMode.equals("ALPACA_ONLY") ||
                                newsSourceMode.equals("DUAL_SOURCE"));

        System.out.println("News intake mode: " + newsSourceMode);
        System.out.println("BENZINGA_NEWS_ENABLED=" + benzingaEnabled);
        System.out.println("START_BENZINGA_NEWS=" + startBenzingaNews);
        System.out.println("START_ALPACA_NEWS=" + startAlpacaNews);
        System.out.println("NEWS_MAX_PROVIDER_AGE_MINUTES=" + System.getenv().getOrDefault("NEWS_MAX_PROVIDER_AGE_MINUTES", "60"));
        System.out.println("NEWS_A_PLUS_MAX_PROVIDER_AGE_MINUTES=" + System.getenv().getOrDefault("NEWS_A_PLUS_MAX_PROVIDER_AGE_MINUTES", "30"));
        System.out.println("NEWS_ANALYST_MAX_PROVIDER_AGE_MINUTES=" + System.getenv().getOrDefault("NEWS_ANALYST_MAX_PROVIDER_AGE_MINUTES", "15"));
        boolean benzingaRestPollingEnabled =
                startBenzingaNews &&
                        "true".equalsIgnoreCase(
                                System.getenv().getOrDefault(
                                        "BENZINGA_REST_POLLING_ENABLED",
                                        "true"
                                )
                        );

        boolean benzingaPressReleasesEnabled =
                institutionalPressReleaseOnlyMode ||
                        "true".equalsIgnoreCase(
                                System.getenv().getOrDefault(
                                        "BENZINGA_PRESS_RELEASES_ENABLED",
                                        hasBenzingaPressReleaseToken() ? "true" : "false"
                                )
                        );

        boolean singleBenzingaWebSocketMode =
                "true".equalsIgnoreCase(
                        System.getenv().getOrDefault(
                                "BENZINGA_WS_SINGLE_CONNECTION_MODE",
                                "true"
                        )
                );

        boolean startBenzingaPressReleaseWebSocket =
                benzingaPressReleasesEnabled &&
                        "true".equalsIgnoreCase(
                                System.getenv().getOrDefault(
                                        "BENZINGA_PRESS_RELEASE_WS_ENABLED",
                                        "true"
                                )
                        );

        if (singleBenzingaWebSocketMode && startBenzingaNews && startBenzingaPressReleaseWebSocket) {
            startBenzingaPressReleaseWebSocket = false;
            System.out.println(
                    "BENZINGA_WS_SINGLE_CONNECTION_MODE=true: disabling separate press-release WebSocket " +
                            "to avoid 429 rate limits. Press releases remain covered by REST polling."
            );
        }

        boolean startBenzingaPressReleaseRestPolling =
                benzingaPressReleasesEnabled &&
                        "true".equalsIgnoreCase(
                                System.getenv().getOrDefault(
                                        "BENZINGA_PRESS_RELEASE_REST_POLLING_ENABLED",
                                        "true"
                                )
                        );

        System.out.println("BENZINGA_WS_SINGLE_CONNECTION_MODE=" + singleBenzingaWebSocketMode);
        System.out.println("BENZINGA_REST_POLLING_ENABLED=" + benzingaRestPollingEnabled);
        System.out.println("BENZINGA_REST_POLL_SECONDS=" + System.getenv().getOrDefault("BENZINGA_REST_POLL_SECONDS", "5"));
        System.out.println("BENZINGA_PRESS_RELEASES_ENABLED=" + benzingaPressReleasesEnabled);
        System.out.println("START_BENZINGA_PRESS_RELEASE_WS=" + startBenzingaPressReleaseWebSocket);
        System.out.println("START_BENZINGA_PRESS_RELEASE_REST=" + startBenzingaPressReleaseRestPolling);
        System.out.println("BENZINGA_PRESS_RELEASE_CHANNELS=" + System.getenv().getOrDefault("BENZINGA_PRESS_RELEASE_CHANNELS", "Press Releases"));
        System.out.println("BENZINGA_PRESS_RELEASE_REST_POLL_SECONDS=" + System.getenv().getOrDefault("BENZINGA_PRESS_RELEASE_REST_POLL_SECONDS", "3"));
        System.out.println("Execution strategy: InstantNewsMomentumStrategy");

        if (dryRun) {
            System.out.println("DRY RUN MODE ENABLED: simulated LONG stock buy/sell fills only.");
        } else if (tradingEnabled) {
            System.out.println("PAPER ORDER MODE ENABLED: LONG stock paper orders only.");
        } else {
            System.out.println("ORDER EXECUTION DISABLED: signals only.");
        }

        AlpacaBroker broker = new AlpacaBroker(dryRun);
        MarketDataCache marketData = new MarketDataCache();
        TradeJournal tradeJournal = new TradeJournal();

        OrderExecutor orderExecutor =
                new OrderExecutor(
                        broker,
                        tradeJournal,
                        stockOrderExecutionEnabled
                );

        PositionManager positionManager =
                new PositionManager(
                        marketData,
                        orderExecutor,
                        broker::getPrice
                );

        positionManager.syncFromBroker(broker.getOpenPositions());

        AccountService accountService = new AlpacaAccountService(broker);
        AdvancedRiskEngine riskEngine = new AdvancedRiskEngine(accountService, positionManager);
        FinBertService finbert = new FinBertService();
        OpportunityRanker opportunityRanker = new OpportunityRanker(broker);

        PriceStreamRegistry priceStreamRegistry =
                new PriceStreamRegistry(broker, marketData, positionManager, 1);

        priceStreamRegistry.startTrackingAll(
                positionManager.allPositions()
        );

        InstantNewsMomentumStrategy strategy =
                new InstantNewsMomentumStrategy(
                        broker,
                        finbert,
                        opportunityRanker,
                        riskEngine,
                        orderExecutor,
                        positionManager,
                        priceStreamRegistry,
                        dryRun,
                        relaxedDryRunRelevance
                );

        MultiSourceNewsFreshnessEngine freshnessEngine =
                new MultiSourceNewsFreshnessEngine();

        FreshnessFilteredNewsRouter freshnessRouter =
                new FreshnessFilteredNewsRouter(
                        freshnessEngine,
                        news -> handleLiveNews(news, strategy)
                );

        MarketIntelligenceBus marketIntelligenceBus = MarketIntelligenceBus.getInstance();
        marketIntelligenceBus.start();
        marketIntelligenceBus.setNewsDownstream(freshnessRouter::onNews);
        ExternalDataSourceManager externalDataSourceManager = new ExternalDataSourceManager();
        externalDataSourceManager.start();

        if (startAlpacaNews) {
            AlpacaNewsWebSocketStream alpacaNewsStream =
                    new AlpacaNewsWebSocketStream(marketIntelligenceBus::publishNews);

            alpacaNewsStream.start();
        } else {
            System.out.println(
                    "Alpaca news WebSocket disabled by NEWS_SOURCE_MODE=" +
                            newsSourceMode
            );
        }

        if (startBenzingaNews) {
            try {
                BenzingaNewsWebSocketStream benzingaNewsStream =
                        new BenzingaNewsWebSocketStream(marketIntelligenceBus::publishNews);

                benzingaNewsStream.start();

                if (benzingaRestPollingEnabled) {
                    BenzingaNewsRestPollingStream benzingaRestPollingStream =
                            new BenzingaNewsRestPollingStream(marketIntelligenceBus::publishNews);

                    benzingaRestPollingStream.start();
                } else {
                    System.out.println("Benzinga REST polling backup disabled by BENZINGA_REST_POLLING_ENABLED=false");
                }

            } catch (Exception e) {
                System.err.println(
                        "BENZINGA NEWS STARTUP FAILED: " +
                                e.getMessage()
                );

                if (newsSourceMode.equals("DUAL_SOURCE")) {
                    System.err.println(
                            "Continuing with Alpaca news only because NEWS_SOURCE_MODE=DUAL_SOURCE. " +
                                    "Check BENZINGA_NEWS_TOKEN and BENZINGA_NEWS_WS_URL."
                    );
                } else {
                    System.err.println(
                            "Benzinga-only mode is enabled, so Alpaca news will not be started as a fallback. " +
                                    "Fix BENZINGA_NEWS_TOKEN or BENZINGA_NEWS_WS_URL, then restart."
                    );
                }
            }
        } else {
            System.out.println(
                    institutionalPressReleaseOnlyMode
                            ? "Benzinga retail news WebSocket disabled by NEWS_SOURCE_MODE=BENZINGA_PRESS_RELEASE_ONLY"
                            : "Benzinga news WebSocket disabled by configuration."
            );
        }

        if (benzingaPressReleasesEnabled) {
            try {
                if (startBenzingaPressReleaseWebSocket) {
                    BenzingaInstitutionalPressReleaseWebSocketStream pressReleaseWebSocketStream =
                            new BenzingaInstitutionalPressReleaseWebSocketStream(marketIntelligenceBus::publishNews);

                    pressReleaseWebSocketStream.start();
                } else {
                    System.out.println("Benzinga institutional press-release WebSocket disabled by BENZINGA_PRESS_RELEASE_WS_ENABLED=false");
                }

                if (startBenzingaPressReleaseRestPolling) {
                    BenzingaInstitutionalPressReleaseRestPollingStream pressReleaseRestPollingStream =
                            new BenzingaInstitutionalPressReleaseRestPollingStream(marketIntelligenceBus::publishNews);

                    pressReleaseRestPollingStream.start();
                } else {
                    System.out.println("Benzinga institutional press-release REST polling disabled by BENZINGA_PRESS_RELEASE_REST_POLLING_ENABLED=false");
                }
            } catch (Exception e) {
                System.err.println(
                        "BENZINGA INSTITUTIONAL PRESS RELEASE STARTUP FAILED: " +
                                e.getMessage()
                );
                System.err.println(
                        "Set BENZINGA_PRESS_RELEASE_TOKEN and verify BENZINGA_PRESS_RELEASE_CHANNELS if the press-release feed should be active."
                );
            }
        } else {
            System.out.println("Benzinga institutional press-release feed disabled by configuration or missing token.");
        }

        while (true) {
            Thread.sleep(10_000L);
        }
    }

    private static boolean hasBenzingaToken() {
        String token =
                System.getenv("BENZINGA_NEWS_TOKEN");

        if (token != null && !token.isBlank()) {
            return true;
        }

        token =
                System.getenv("BENZINGA_API_KEY");

        return token != null && !token.isBlank();
    }

    private static boolean hasBenzingaPressReleaseToken() {
        String token =
                System.getenv("BENZINGA_PRESS_RELEASE_TOKEN");

        if (token != null && !token.isBlank()) {
            return true;
        }

        token =
                System.getenv("BENZINGA_INSTITUTIONAL_TOKEN");

        if (token != null && !token.isBlank()) {
            return true;
        }

        token =
                System.getenv("BENZINGA_INSTITUTIONAL_PRESS_RELEASE_TOKEN");

        return token != null && !token.isBlank();
    }

    private static void handleLiveNews(
            NewsEvent news,
            InstantNewsMomentumStrategy strategy
    ) {
        try {
            if (news == null) {
                return;
            }

            strategy.onNews(news);

        } catch (Exception e) {
            System.err.println("Failed handling live news: " + e.getMessage());
            e.printStackTrace();
        }
    }
}