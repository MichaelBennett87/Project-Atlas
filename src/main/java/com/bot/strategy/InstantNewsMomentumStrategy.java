package com.bot.strategy;

import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.SignalJournal;
import com.bot.model.AdaptivePositionSizeProfile;
import com.bot.model.CatalystQualityDecision;
import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.EntryDecision;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.model.RelevanceDecision;
import com.bot.model.TradeDirection;
import com.bot.news.FinBertService;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.sentiment.SentimentScore;
import com.bot.stream.PriceStreamRegistry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InstantNewsMomentumStrategy {

    private final AlpacaBroker broker;
    private final FinBertService finbert;
    private final OpportunityRanker opportunityRanker;
    private final AdvancedRiskEngine risk;
    private final OrderExecutor orders;
    private final PositionManager positions;
    private final PriceStreamRegistry priceStreamRegistry;

    private final CatalystClassifier catalystClassifier;
    private final CatalystQualityFilter catalystQualityFilter;
    private final EntryDecisionService entryDecisionService;
    private final TickerRelevanceFilter tickerRelevanceFilter;
    private final TradeDirectionService tradeDirectionService;
    private final SignalJournal signalJournal;

    private static final int PENDING_CONFIRMATION_MAX_CHECKS =
            20;

    private static final int PENDING_CONFIRMATION_CHECK_SECONDS =
            15;

    private final boolean dryRunMode;
    private final boolean relaxedDryRunRelevance;

    private final Set<String> pendingConfirmationWatches =
            ConcurrentHashMap.newKeySet();

    private final Set<String> executionInFlightTickers =
            ConcurrentHashMap.newKeySet();

    public InstantNewsMomentumStrategy(
            AlpacaBroker broker,
            FinBertService finbert,
            OpportunityRanker opportunityRanker,
            AdvancedRiskEngine risk,
            OrderExecutor orders,
            PositionManager positions,
            PriceStreamRegistry priceStreamRegistry
    ) {
        this(
                broker,
                finbert,
                opportunityRanker,
                risk,
                orders,
                positions,
                priceStreamRegistry,
                false,
                false
        );
    }

    public InstantNewsMomentumStrategy(
            AlpacaBroker broker,
            FinBertService finbert,
            OpportunityRanker opportunityRanker,
            AdvancedRiskEngine risk,
            OrderExecutor orders,
            PositionManager positions,
            PriceStreamRegistry priceStreamRegistry,
            boolean dryRunMode,
            boolean relaxedDryRunRelevance
    ) {
        this.broker = broker;
        this.finbert = finbert;
        this.opportunityRanker = opportunityRanker;
        this.risk = risk;
        this.orders = orders;
        this.positions = positions;
        this.priceStreamRegistry = priceStreamRegistry;
        this.dryRunMode = dryRunMode;
        this.relaxedDryRunRelevance = relaxedDryRunRelevance;

        this.catalystClassifier = new CatalystClassifier();
        this.catalystQualityFilter = new CatalystQualityFilter();
        this.entryDecisionService = new EntryDecisionService();
        this.tickerRelevanceFilter = new TickerRelevanceFilter();
        this.tradeDirectionService = new TradeDirectionService();
        this.signalJournal = new SignalJournal();
    }

    public synchronized void onNews(NewsEvent news) throws Exception {
        if (news == null) {
            return;
        }

        long ageMs =
                System.currentTimeMillis() - news.getTimestamp();

        System.out.println("REAL-TIME NEWS RECEIVED:");
        System.out.println("Ticker: " + news.getTicker());
        System.out.println("Age ms: " + ageMs);
        System.out.println("Manual Test: false");
        System.out.println("Headline: " + news.getHeadline());

        RelevanceDecision relevanceDecision =
                tickerRelevanceFilter.evaluate(news);

        int relevanceScore =
                tickerRelevanceFilter.relevanceScore(news);

        System.out.println(
                "RELEVANCE CHECK: " +
                        news.getTicker() +
                        " decision=" +
                        relevanceDecision +
                        " score=" +
                        relevanceScore
        );

        if (relevanceDecision == RelevanceDecision.NOT_RELEVANT) {
            if (relaxedDryRunRelevance) {
                System.out.println(
                        "DRY RUN RELAXED RELEVANCE: allowing headline through pipeline " +
                                news.getTicker() +
                                " originalRelevanceScore=" +
                                relevanceScore
                );

                relevanceDecision =
                        RelevanceDecision.PRIMARY_SUBJECT;

            } else {
                System.out.println(
                        "INSTANT REJECTED: ticker not relevant enough " +
                                news.getTicker() +
                                " relevanceScore=" +
                                relevanceScore
                );

                printEntrySummary(
                        news,
                        relevanceDecision,
                        null,
                        null,
                        null,
                        -999.0,
                        TradeDirection.NO_TRADE,
                        null,
                        null,
                        "REJECTED",
                        "TICKER_NOT_RELEVANT"
                );

                signalJournal.recordDetailed(
                        news,
                        null,
                        null,
                        null,
                        -999.0,
                        ageMs,
                        "REJECTED",
                        "TICKER_NOT_RELEVANT",
                        TradeDirection.NO_TRADE
                );

                return;
            }
        }

        SentimentScore sentiment =
                finbert.analyze(
                        news.fullText()
                );

        CatalystResult catalyst =
                catalystClassifier.classify(
                        news
                );

        CatalystQualityDecision qualityDecision =
                catalystQualityFilter.evaluate(
                        catalyst,
                        sentiment
                );

        double finalScore =
                calculateFinalScore(
                        sentiment,
                        catalyst,
                        qualityDecision
                );

        NewsOpportunity opportunity =
                new NewsOpportunity(
                        news,
                        sentiment,
                        catalyst,
                        finalScore,
                        qualityDecision.passed,
                        qualityDecision.reason
                );

        TradeDirection direction =
                tradeDirectionService.resolve(
                        catalyst,
                        sentiment
                );

        System.out.println(
                "AUTOMATIC CANDIDATE: " +
                        news.getTicker() +
                        " long=" +
                        (direction == TradeDirection.LONG_STOCK) +
                        " short=" +
                        (direction == TradeDirection.SHORT_STOCK)
        );

        System.out.println(
                "TRADE DIRECTION: " +
                        news.getTicker() +
                        " direction=" +
                        direction
        );

        if (!qualityDecision.passed) {
            System.out.println(
                    "INSTANT REJECTED: " +
                            qualityDecision.reason
            );

            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    null,
                    finalScore,
                    direction,
                    null,
                    null,
                    "REJECTED",
                    qualityDecision.reason
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    null,
                    finalScore,
                    ageMs,
                    "REJECTED",
                    qualityDecision.reason,
                    direction
            );

            return;
        }

        List<RankedOpportunity> rankedOpportunities =
                opportunityRanker.rank(
                        List.of(opportunity)
                );

        if (rankedOpportunities.isEmpty()) {
            System.out.println(
                    "INSTANT REJECTED: ranking rejected opportunity"
            );

            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    null,
                    finalScore,
                    direction,
                    null,
                    null,
                    "REJECTED",
                    "RANKING_REJECTED"
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    null,
                    finalScore,
                    ageMs,
                    "REJECTED",
                    "RANKING_REJECTED",
                    direction
            );

            return;
        }

        RankedOpportunity ranked =
                rankedOpportunities.get(0);

        EntryDecision entryDecision =
                entryDecisionService.decide(
                        ranked,
                        relevanceDecision
                );

        System.out.println("INSTANT RANKED OPPORTUNITY:");
        System.out.println("Ticker: " + news.getTicker());
        System.out.println("Relevance Decision: " + relevanceDecision);
        System.out.println("Entry Decision: " + entryDecision);
        System.out.println("Rank Score: " + ranked.rankScore);
        System.out.println("Reason: " + ranked.reason);
        System.out.println("Market Quality: " + ranked.marketQuality);
        System.out.println("Catalyst: " + catalyst);
        System.out.println("Sentiment: " + sentiment);
        System.out.println("Headline: " + news.getHeadline());

        if (entryDecision == EntryDecision.REJECT) {
            String entryRejectReason =
                    buildEntryDecisionReason(
                            entryDecision,
                            ranked,
                            relevanceDecision
                    );

            System.out.println(
                    "INSTANT REJECTED: entry decision rejected " +
                            news.getTicker() +
                            " reason=" +
                            entryRejectReason
            );

            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    ranked,
                    finalScore,
                    direction,
                    ranked,
                    entryDecision,
                    "REJECTED",
                    entryRejectReason
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "REJECTED",
                    entryRejectReason == null || entryRejectReason.isBlank()
                            ? "ENTRY_DECISION_REJECTED"
                            : entryRejectReason,
                    direction
            );

            return;
        }

        if (entryDecision == EntryDecision.PENDING_CONFIRMATION) {
            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    ranked,
                    finalScore,
                    direction,
                    ranked,
                    entryDecision,
                    "PENDING",
                    "PENDING_CONFIRMATION_" + relevanceDecision
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "PENDING",
                    "PENDING_CONFIRMATION_" + relevanceDecision,
                    direction
            );

            System.out.println(
                    "INSTANT SIGNAL PENDING CONFIRMATION: " +
                            news.getTicker() +
                            " relevance=" +
                            relevanceDecision
            );

            startPendingConfirmationWatch(
                    ranked,
                    relevanceDecision,
                    ageMs,
                    direction
            );

            return;
        }

        priceStreamRegistry.startTracking(
                news.getTicker()
        );

        if (!risk.allowNewTrade(news.getTicker())) {
            System.out.println(
                    "INSTANT REJECTED: risk engine blocked trade"
            );

            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    ranked,
                    finalScore,
                    direction,
                    ranked,
                    entryDecision,
                    "REJECTED",
                    "RISK_ENGINE_BLOCKED"
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "REJECTED",
                    "RISK_ENGINE_BLOCKED",
                    direction
            );

            return;
        }

        int baseQuantity =
                risk.calculateQuantity(
                        news.getTicker(),
                        catalyst,
                        sentiment,
                        strategyNameFor(direction)
                );

        if (baseQuantity <= 0) {
            System.out.println(
                    "INSTANT REJECTED: zero base quantity"
            );

            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    ranked,
                    finalScore,
                    direction,
                    ranked,
                    entryDecision,
                    "REJECTED",
                    "ZERO_BASE_QUANTITY"
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "REJECTED",
                    "ZERO_BASE_QUANTITY",
                    direction
            );

            return;
        }

        AdaptivePositionSizeProfile sizeProfile =
                entryDecisionService.adaptiveSize(
                        ranked,
                        relevanceDecision,
                        baseQuantity
                );

        int quantity =
                sizeProfile.finalQuantity;

        System.out.println("ADAPTIVE POSITION SIZE:");
        System.out.println("Ticker: " + news.getTicker());
        System.out.println("Base quantity: " + baseQuantity);
        System.out.println("Final quantity: " + quantity);
        System.out.println("Sizing profile: " + sizeProfile);

        if (quantity <= 0) {
            System.out.println(
                    "INSTANT REJECTED: adaptive quantity is zero"
            );

            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    ranked,
                    finalScore,
                    direction,
                    ranked,
                    entryDecision,
                    "REJECTED",
                    "ZERO_ADAPTIVE_QUANTITY"
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "REJECTED",
                    "ZERO_ADAPTIVE_QUANTITY",
                    direction
            );

            return;
        }

        if (direction == TradeDirection.SHORT_STOCK) {
            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    ranked,
                    finalScore,
                    direction,
                    ranked,
                    entryDecision,
                    "EXECUTION_ATTEMPT",
                    "SHORT_ORDER_SUBMISSION"
            );

            executeShort(
                    news,
                    sentiment,
                    catalyst,
                    ranked,
                    ageMs,
                    quantity,
                    direction
            );

            return;
        }

        if (direction == TradeDirection.LONG_STOCK) {
            printEntrySummary(
                    news,
                    relevanceDecision,
                    sentiment,
                    catalyst,
                    ranked,
                    finalScore,
                    direction,
                    ranked,
                    entryDecision,
                    "EXECUTION_ATTEMPT",
                    "LONG_ORDER_SUBMISSION"
            );

            executeLong(
                    news,
                    sentiment,
                    catalyst,
                    ranked,
                    ageMs,
                    quantity,
                    direction
            );

            return;
        }

        printEntrySummary(
                news,
                relevanceDecision,
                sentiment,
                catalyst,
                ranked,
                finalScore,
                direction,
                ranked,
                entryDecision,
                "REJECTED",
                "NO_TRADE_DIRECTION"
        );

        signalJournal.recordDetailed(
                news,
                sentiment,
                catalyst,
                ranked.marketQuality,
                ranked.rankScore,
                ageMs,
                "REJECTED",
                "NO_TRADE_DIRECTION",
                direction
        );
    }

    private String buildEntryDecisionReason(
            EntryDecision entryDecision,
            RankedOpportunity ranked,
            RelevanceDecision relevanceDecision
    ) {
        if (entryDecision != EntryDecision.REJECT) {
            return entryDecision == null
                    ? "UNKNOWN_ENTRY_DECISION"
                    : entryDecision.name();
        }

        String serviceReason =
                entryDecisionService.getLastDecisionReason();

        if (serviceReason != null && !serviceReason.isBlank()) {
            return serviceReason;
        }

        if (relevanceDecision == RelevanceDecision.NOT_RELEVANT) {
            return "ENTRY_REJECTED_NOT_RELEVANT";
        }

        if (ranked == null || ranked.opportunity == null) {
            return "ENTRY_REJECTED_MISSING_RANKED_OPPORTUNITY";
        }

        if (!ranked.opportunity.qualityPassed) {
            return "ENTRY_REJECTED_QUALITY_FILTER";
        }

        if (ranked.opportunity.sentiment == null) {
            return "ENTRY_REJECTED_MISSING_SENTIMENT";
        }

        if (ranked.opportunity.catalyst == null) {
            return "ENTRY_REJECTED_MISSING_CATALYST";
        }

        return "ENTRY_DECISION_REJECTED";
    }

    private void printEntrySummary(
            NewsEvent news,
            RelevanceDecision relevanceDecision,
            SentimentScore sentiment,
            CatalystResult catalyst,
            RankedOpportunity rankedForMarketQuality,
            double finalScore,
            TradeDirection direction,
            RankedOpportunity rankedForRank,
            EntryDecision entryDecision,
            String finalDecision,
            String reason
    ) {
        System.out.println("========== ENTRY SUMMARY ==========");
        System.out.println("ticker=" + (news == null ? "UNKNOWN" : news.getTicker()));
        System.out.println("headline=" + (news == null ? "" : news.getHeadline()));
        System.out.println("relevance=" + relevanceDecision);
        System.out.println("catalyst=" + catalyst);
        System.out.println("sentiment=" + sentiment);
        System.out.println("marketQuality=" + (rankedForMarketQuality == null ? null : rankedForMarketQuality.marketQuality));
        System.out.println("rankScore=" + (rankedForRank == null ? -999.0 : rankedForRank.rankScore));
        System.out.println("rankReason=" + (rankedForRank == null ? null : rankedForRank.reason));
        System.out.println("finalScore=" + finalScore);
        System.out.println("direction=" + direction);
        System.out.println("entryDecision=" + entryDecision);
        System.out.println("finalDecision=" + finalDecision);
        System.out.println("reason=" + reason);
        System.out.println("===================================");
    }

    private void startPendingConfirmationWatch(
            RankedOpportunity originalRanked,
            RelevanceDecision relevanceDecision,
            long originalAgeMs,
            TradeDirection direction
    ) {
        if (originalRanked == null ||
                originalRanked.opportunity == null ||
                originalRanked.opportunity.news == null ||
                direction != TradeDirection.LONG_STOCK) {
            return;
        }

        NewsEvent news =
                originalRanked.opportunity.news;

        CatalystResult catalyst =
                originalRanked.opportunity.catalyst;

        if (!shouldWatchPendingConfirmation(catalyst)) {
            return;
        }

        String watchKey =
                news.getTicker() + ":" + news.getId();

        if (!pendingConfirmationWatches.add(watchKey)) {
            return;
        }

        priceStreamRegistry.startTracking(news.getTicker());

        Thread watcher =
                new Thread(
                        () -> runPendingConfirmationWatch(
                                watchKey,
                                originalRanked,
                                relevanceDecision,
                                originalAgeMs,
                                direction
                        )
                );

        watcher.setName(
                "pending-confirmation-watch-" + news.getTicker()
        );
        watcher.setDaemon(true);
        watcher.start();

        System.out.println(
                "PENDING CONFIRMATION WATCH STARTED: " +
                        news.getTicker() +
                        " checks=" +
                        PENDING_CONFIRMATION_MAX_CHECKS +
                        " intervalSeconds=" +
                        PENDING_CONFIRMATION_CHECK_SECONDS
        );
    }

    private void runPendingConfirmationWatch(
            String watchKey,
            RankedOpportunity originalRanked,
            RelevanceDecision relevanceDecision,
            long originalAgeMs,
            TradeDirection direction
    ) {
        try {
            for (int i = 1; i <= PENDING_CONFIRMATION_MAX_CHECKS; i++) {
                sleepSeconds(PENDING_CONFIRMATION_CHECK_SECONDS);

                List<RankedOpportunity> refreshedRankings =
                        opportunityRanker.rank(
                                List.of(originalRanked.opportunity)
                        );

                if (refreshedRankings.isEmpty()) {
                    System.out.println(
                            "PENDING WATCH STILL WAITING: " +
                                    originalRanked.opportunity.news.getTicker() +
                                    " check=" +
                                    i +
                                    " reason=no ranked opportunity yet"
                    );
                    continue;
                }

                RankedOpportunity refreshed =
                        refreshedRankings.get(0);

                EntryDecision refreshedDecision =
                        entryDecisionService.decide(
                                refreshed,
                                relevanceDecision
                        );

                System.out.println(
                        "PENDING WATCH CHECK: " +
                                originalRanked.opportunity.news.getTicker() +
                                " check=" +
                                i +
                                " decision=" +
                                refreshedDecision +
                                " marketQuality=" +
                                refreshed.marketQuality
                );

                if (refreshedDecision != EntryDecision.IMMEDIATE_ENTRY) {
                    continue;
                }

                if (!risk.allowNewTrade(
                        originalRanked.opportunity.news.getTicker()
                )) {
                    System.out.println(
                            "PENDING WATCH PROMOTION BLOCKED BY RISK: " +
                                    originalRanked.opportunity.news.getTicker()
                    );
                    return;
                }

                int baseQuantity =
                        risk.calculateQuantity(
                                originalRanked.opportunity.news.getTicker(),
                                originalRanked.opportunity.catalyst,
                                originalRanked.opportunity.sentiment,
                                strategyNameFor(direction)
                        );

                if (baseQuantity <= 0) {
                    System.out.println(
                            "PENDING WATCH PROMOTION BLOCKED: zero base quantity " +
                                    originalRanked.opportunity.news.getTicker()
                    );
                    return;
                }

                AdaptivePositionSizeProfile sizeProfile =
                        entryDecisionService.adaptiveSize(
                                refreshed,
                                relevanceDecision,
                                baseQuantity
                        );

                int quantity =
                        sizeProfile.finalQuantity;

                if (quantity <= 0) {
                    System.out.println(
                            "PENDING WATCH PROMOTION BLOCKED: zero adaptive quantity " +
                                    originalRanked.opportunity.news.getTicker()
                    );
                    return;
                }

                System.out.println(
                        "PENDING WATCH PROMOTED TO BUY: " +
                                originalRanked.opportunity.news.getTicker() +
                                " qty=" +
                                quantity +
                                " sizing=" +
                                sizeProfile
                );

                executeLong(
                        originalRanked.opportunity.news,
                        originalRanked.opportunity.sentiment,
                        originalRanked.opportunity.catalyst,
                        refreshed,
                        originalAgeMs,
                        quantity,
                        direction
                );

                return;
            }

            System.out.println(
                    "PENDING CONFIRMATION WATCH EXPIRED: " +
                            originalRanked.opportunity.news.getTicker()
            );
        } finally {
            pendingConfirmationWatches.remove(watchKey);

            String ticker = originalRanked.opportunity.news.getTicker();
            if (ticker != null && !positions.hasPosition(ticker)) {
                priceStreamRegistry.stopTracking(ticker);
            }
        }
    }

    private boolean shouldWatchPendingConfirmation(
            CatalystResult catalyst
    ) {
        if (catalyst == null || catalyst.type == null) {
            return false;
        }

        CatalystType type =
                catalyst.type;

        return type == CatalystType.NASDAQ_COMPLIANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE_EXTENSION ||
                type == CatalystType.NYSE_COMPLIANCE ||
                type == CatalystType.EXCHANGE_COMPLIANCE ||
                type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.FDA_REGISTRATION ||
                type == CatalystType.MERGER_ACQUISITION ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.PARTNERSHIP ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.ANALYST_UPGRADE ||
                type == CatalystType.PRICE_TARGET_RAISE;
    }

    private void sleepSeconds(
            int seconds
    ) {
        try {
            Thread.sleep(Math.max(1, seconds) * 1000L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private void executeLong(
            NewsEvent news,
            SentimentScore sentiment,
            CatalystResult catalyst,
            RankedOpportunity ranked,
            long ageMs,
            int quantity,
            TradeDirection direction
    ) {
        if (!executionAllowed()) {
            System.out.println(
                    "TRADE SIGNAL DETECTED BUT NOT EXECUTED: " +
                            news.getTicker() +
                            " qty=" +
                            quantity +
                            " reason=TRADING_ENABLED is not true" +
                            " entryMode=INSTANT_WEBSOCKET"
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "SIGNAL_ONLY",
                    "TRADING_DISABLED_INSTANT_WEBSOCKET",
                    direction
            );

            return;
        }

        String executionKey =
                news.getTicker() == null
                        ? "UNKNOWN"
                        : news.getTicker().trim().toUpperCase();

        if (!executionInFlightTickers.add(executionKey)) {
            System.out.println(
                    "LONG EXECUTION SKIPPED: another order is already in flight for " +
                            executionKey
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "REJECTED",
                    "ORDER_ALREADY_IN_FLIGHT",
                    direction
            );

            return;
        }

        boolean filled;

        try {
            filled =
                    orders.buyMarketAndWaitForFill(
                            news.getTicker(),
                            quantity
                    );
        } catch (Exception e) {
            executionInFlightTickers.remove(executionKey);

            if (!positions.hasPosition(news.getTicker())) {
                priceStreamRegistry.stopTracking(news.getTicker());
            }

            System.out.println(
                    "LONG ORDER SUBMISSION ERROR: " +
                            executionKey +
                            " error=" +
                            e.getMessage()
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "REJECTED",
                    "ORDER_SUBMISSION_ERROR",
                    direction
            );

            return;
        }

        if (!filled) {
            executionInFlightTickers.remove(executionKey);

            if (!positions.hasPosition(news.getTicker())) {
                priceStreamRegistry.stopTracking(news.getTicker());
            }

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "REJECTED",
                    "ORDER_NOT_FILLED",
                    direction
            );

            return;
        }

        double entryPrice =
                risk.lastPrice(
                        news.getTicker()
                );

        if (entryPrice <= 0.0 && ranked.marketQuality != null) {
            entryPrice = ranked.marketQuality.latestTradePrice > 0.0
                    ? ranked.marketQuality.latestTradePrice
                    : ranked.marketQuality.price;
        }

        if (entryPrice <= 0.0) {
            executionInFlightTickers.remove(executionKey);
            priceStreamRegistry.stopTracking(news.getTicker());

            System.out.println(
                    "LONG POSITION TRACKING FAILED: missing entry price after filled order " +
                            executionKey
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "EXECUTED",
                    "ORDER_FILLED_BUT_POSITION_TRACKING_ENTRY_PRICE_MISSING",
                    direction
            );

            return;
        }

        int trackedQuantity = quantity;
        int signedBrokerQuantity = orders.getSignedBrokerPositionQuantity(news.getTicker());
        if (signedBrokerQuantity > 0) {
            trackedQuantity = signedBrokerQuantity;
        }

        if (trackedQuantity != quantity) {
            System.out.println(
                    "INSTANT LONG POSITION TRACKING QTY RESYNC: " +
                            news.getTicker() +
                            " requestedQty=" +
                            quantity +
                            " brokerSignedQty=" +
                            signedBrokerQuantity +
                            " trackedQty=" +
                            trackedQuantity
            );
        }

        positions.openLong(
                news.getTicker(),
                entryPrice,
                trackedQuantity,
                strategyNameFor(direction),
                ranked == null ? 0.0 : ranked.rankScore,
                ranked == null ? 0.0 : ranked.rankScore
        );

        executionInFlightTickers.remove(executionKey);

        signalJournal.recordDetailed(
                news,
                sentiment,
                catalyst,
                ranked.marketQuality,
                ranked.rankScore,
                ageMs,
                "EXECUTED",
                dryRunMode ? "DRY_RUN_ORDER_FILLED_INSTANT_WEBSOCKET" : "ORDER_FILLED_INSTANT_WEBSOCKET",
                direction
        );
    }

    private void executeShort(
            NewsEvent news,
            SentimentScore sentiment,
            CatalystResult catalyst,
            RankedOpportunity ranked,
            long ageMs,
            int quantity,
            TradeDirection direction
    ) {
        if (!executionAllowed()) {
            System.out.println(
                    "SHORT SIGNAL DETECTED BUT NOT EXECUTED: " +
                            news.getTicker() +
                            " qty=" +
                            quantity +
                            " reason=TRADING_ENABLED is not true" +
                            " entryMode=INSTANT_WEBSOCKET"
            );

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "SIGNAL_ONLY",
                    "TRADING_DISABLED_SHORT_INSTANT_WEBSOCKET",
                    direction
            );

            return;
        }

        boolean filled =
                orders.shortMarketAndWaitForFill(
                        news.getTicker(),
                        quantity
                );

        if (!filled) {
            if (!positions.hasPosition(news.getTicker())) {
                priceStreamRegistry.stopTracking(news.getTicker());
            }

            signalJournal.recordDetailed(
                    news,
                    sentiment,
                    catalyst,
                    ranked.marketQuality,
                    ranked.rankScore,
                    ageMs,
                    "REJECTED",
                    "SHORT_ORDER_NOT_FILLED",
                    direction
            );

            return;
        }

        double entryPrice =
                risk.lastPrice(
                        news.getTicker()
                );

        int trackedQuantity = quantity;
        int signedBrokerQuantity = orders.getSignedBrokerPositionQuantity(news.getTicker());
        if (signedBrokerQuantity < 0) {
            trackedQuantity = Math.abs(signedBrokerQuantity);
        }

        if (trackedQuantity != quantity) {
            System.out.println(
                    "INSTANT SHORT POSITION TRACKING QTY RESYNC: " +
                            news.getTicker() +
                            " requestedQty=" +
                            quantity +
                            " brokerSignedQty=" +
                            signedBrokerQuantity +
                            " trackedQty=" +
                            trackedQuantity
            );
        }

        positions.openShort(
                news.getTicker(),
                entryPrice,
                trackedQuantity,
                strategyNameFor(direction),
                ranked == null ? 0.0 : ranked.rankScore,
                ranked == null ? 0.0 : ranked.rankScore
        );

        signalJournal.recordDetailed(
                news,
                sentiment,
                catalyst,
                ranked.marketQuality,
                ranked.rankScore,
                ageMs,
                "EXECUTED",
                dryRunMode ? "DRY_RUN_SHORT_ORDER_FILLED_INSTANT_WEBSOCKET" : "SHORT_ORDER_FILLED_INSTANT_WEBSOCKET",
                direction
        );
    }

    private double calculateFinalScore(
            SentimentScore sentiment,
            CatalystResult catalyst,
            CatalystQualityDecision qualityDecision
    ) {
        if (!qualityDecision.passed) {
            return -999.0;
        }

        return (sentiment.netSentiment() * 0.65)
                + (sentiment.positive * 0.20)
                + (catalyst.weight * 0.75)
                - (sentiment.negative * 0.50);
    }

    private boolean executionAllowed() {
        return dryRunMode || tradingEnabled();
    }

    private boolean tradingEnabled() {
        return "true".equalsIgnoreCase(
                System.getenv("TRADING_ENABLED")
        );
    }

    private static String strategyNameFor(TradeDirection direction) {
        return direction == TradeDirection.SHORT_STOCK
                ? "INSTANT_NEWS_MOMENTUM_SHORT"
                : "INSTANT_NEWS_MOMENTUM_LONG";
    }
}
