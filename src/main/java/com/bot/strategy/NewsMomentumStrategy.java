package com.bot.strategy;

import com.bot.config.TradingConfig;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.SignalJournal;
import com.bot.model.NewsEvent;
import com.bot.news.CatalystScorer;
import com.bot.news.FinBertService;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.risk.MarketHoursService;
import com.bot.sentiment.SentimentScore;

public class NewsMomentumStrategy {

    private final FinBertService finbert;
    private final MarketConfirmationFilter confirmation;
    private final AdvancedRiskEngine risk;
    private final OrderExecutor orders;
    private final PositionManager positions;
    private final NewsDeduplicator deduplicator;
    private final MarketHoursService marketHours;
    private final TickerRelevanceFilter relevanceFilter;
    private final TradingConfig config;
    private final SignalJournal signalJournal;

    public NewsMomentumStrategy(
            FinBertService finbert,
            MarketConfirmationFilter confirmation,
            AdvancedRiskEngine risk,
            OrderExecutor orders,
            PositionManager positions
    ) {
        this.finbert = finbert;
        this.confirmation = confirmation;
        this.risk = risk;
        this.orders = orders;
        this.positions = positions;
        this.deduplicator = new NewsDeduplicator();
        this.marketHours = new MarketHoursService();
        this.relevanceFilter = new TickerRelevanceFilter();
        this.config = new TradingConfig();
        this.signalJournal = new SignalJournal();
    }

    public void onNews(NewsEvent news) throws Exception {
        executeNews(
                news,
                false,
                "CONFIRMED_MOMENTUM_ENTRY"
        );
    }

    public void onImmediateNews(NewsEvent news) throws Exception {
        executeNews(
                news,
                true,
                "IMMEDIATE_NEWS_ENTRY"
        );
    }

    private void executeNews(
            NewsEvent news,
            boolean skipConfirmation,
            String entryMode
    ) throws Exception {

        if (!marketHours.isMarketOpenNow()) {
            System.out.println("NEWS REJECTED: market is closed");
            signalJournal.record(news, null, "REJECTED", "MARKET_CLOSED");
            return;
        }

        if (!deduplicator.isNew(news.getId())) {
            System.out.println("DUPLICATE NEWS IGNORED: " + news.getId());
            signalJournal.record(news, null, "REJECTED", "DUPLICATE_NEWS");
            return;
        }

        if (!relevanceFilter.isRelevant(news)) {
            System.out.println("NEWS REJECTED: not relevant to " + news.getTicker());
            System.out.println("Headline: " + news.getHeadline());
            signalJournal.record(news, null, "REJECTED", "NOT_RELEVANT_TO_TICKER");
            return;
        }

        SentimentScore score =
                finbert.analyze(news.fullText());

        System.out.println("NEWS SENTIMENT: " + score);

        boolean strongPositive =
                score.positive > 0.70 &&
                        score.negative < 0.20 &&
                        score.netSentiment() > 0.55;

        if (!strongPositive) {
            System.out.println("NEWS REJECTED: sentiment not strong enough");
            signalJournal.record(news, score, "REJECTED", "WEAK_SENTIMENT");
            return;
        }

        if (!skipConfirmation && !confirmation.confirm(news.getTicker())) {
            System.out.println("NEWS REJECTED: market confirmation failed");
            signalJournal.record(news, score, "REJECTED", "MARKET_CONFIRMATION_FAILED");
            return;
        }

        if (skipConfirmation) {
            System.out.println(
                    "IMMEDIATE ENTRY APPROVED: " +
                            news.getTicker() +
                            " reason=" +
                            entryMode
            );
        }

        if (!risk.allowNewTrade(news.getTicker())) {
            System.out.println("NEWS REJECTED: risk engine blocked trade");
            signalJournal.record(news, score, "REJECTED", "RISK_ENGINE_BLOCKED");
            return;
        }

        int qty =
                risk.calculateQuantity(
                        news.getTicker(),
                        score,
                        "NEWS_MOMENTUM"
                );

        if (qty <= 0) {
            System.out.println("NEWS REJECTED: quantity was zero");
            signalJournal.record(news, score, "REJECTED", "ZERO_QUANTITY");
            return;
        }

        CatalystScorer catalystScorer =
                new CatalystScorer();

        double catalystScore =
                catalystScorer.score(news.getHeadline())
                        + catalystScorer.score(news.getContent());

        news.setCatalystScore(catalystScore);

        if (!config.tradingEnabled()) {
            System.out.println(
                    "TRADE SIGNAL DETECTED BUT NOT EXECUTED: " +
                            news.getTicker() +
                            " qty=" +
                            qty +
                            " reason=TRADING_ENABLED is not true" +
                            " entryMode=" +
                            entryMode
            );

            signalJournal.record(news, score, "SIGNAL_ONLY", "TRADING_DISABLED_" + entryMode);
            return;
        }

        boolean filled =
                orders.buyMarketAndWaitForFill(
                        news.getTicker(),
                        qty
                );

        if (!filled) {
            signalJournal.record(
                    news,
                    score,
                    "REJECTED",
                    "ORDER_NOT_FILLED"
            );

            return;
        }

        double entryPrice =
                risk.lastPrice(news.getTicker());

        int trackedQuantity = qty;
        int signedBrokerQuantity = orders.getSignedBrokerPositionQuantity(news.getTicker());
        if (signedBrokerQuantity > 0) {
            trackedQuantity = signedBrokerQuantity;
        }

        if (trackedQuantity != qty) {
            System.out.println(
                    "NEWS MOMENTUM POSITION TRACKING QTY RESYNC: " +
                            news.getTicker() +
                            " requestedQty=" +
                            qty +
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
                "NEWS_MOMENTUM",
                Math.max(0.0, Math.min(1.0, score.positive)),
                Math.max(0.0, score.netSentiment())
        );

        signalJournal.record(
                news,
                score,
                "EXECUTED",
                "ORDER_FILLED_" + entryMode
        );

        System.out.println(
                "POSITION OPENED: " +
                        news.getTicker() +
                        " qty=" +
                        qty +
                        " entry=" +
                        entryPrice +
                        " entryMode=" +
                        entryMode
        );
    }
}
