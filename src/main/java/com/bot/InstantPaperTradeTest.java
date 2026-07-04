package com.bot;

import com.bot.broker.AlpacaAccountService;
import com.bot.broker.AlpacaBroker;
import com.bot.config.TradingConfig;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.AccountService;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.model.Position;
import com.bot.news.FinBertService;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.strategy.InstantNewsMomentumStrategy;
import com.bot.strategy.OpportunityRanker;
import com.bot.stream.PriceStreamRegistry;

import java.time.Instant;
import java.util.List;

public class InstantPaperTradeTest {

    public static void main(String[] args) throws Exception {

        AlpacaBroker broker =
                new AlpacaBroker();

        MarketDataCache marketData =
                new MarketDataCache();

        TradeJournal tradeJournal =
                new TradeJournal();

        OrderExecutor orderExecutor =
                new OrderExecutor(
                        broker,
                        tradeJournal
                );

        PositionManager positionManager =
                new PositionManager(
                        marketData,
                        orderExecutor
                );

        List<Position> openPositions =
                broker.getOpenPositions();

        positionManager.syncFromBroker(openPositions);

        PriceStreamRegistry priceStreamRegistry =
                new PriceStreamRegistry(
                        broker,
                        marketData,
                        positionManager,
                        1
                );

        for (Position position : openPositions) {
            priceStreamRegistry.startTracking(position.ticker);
        }

        AccountService accountService =
                new AlpacaAccountService(broker);

        AdvancedRiskEngine riskEngine =
                new AdvancedRiskEngine(
                        accountService,
                        positionManager
                );

        FinBertService finbert =
                new FinBertService();

        OpportunityRanker ranker =
                new OpportunityRanker(broker);

        InstantNewsMomentumStrategy strategy =
                new InstantNewsMomentumStrategy(
                        broker,
                        finbert,
                        ranker,
                        riskEngine,
                        orderExecutor,
                        positionManager,
                        priceStreamRegistry
                );

        NewsEvent positiveNews =
                new NewsEvent(
                        "manual-test-fda-clearance-AAPL",
                        "AAPL",
                        "Apple Receives FDA Clearance For New Health Monitoring Feature",
                        "Apple Receives FDA Clearance For New Health Monitoring Feature",
                        Instant.now().toEpochMilli()
                );

        strategy.onNews(positiveNews);

        System.out.println();
        System.out.println("========== POSITIVE MANUAL TEST COMPLETE ==========");
        System.out.println();

        NewsEvent negativeNews =
                new NewsEvent(
                        "manual-test-negative-guidance-AAPL",
                        "AAPL",
                        "Apple Misses EPS Estimates And Cuts Guidance",
                        "Apple Misses EPS Estimates And Cuts Guidance",
                        Instant.now().toEpochMilli()
                );

        strategy.onNews(negativeNews);

        System.out.println();
        System.out.println("========== NEGATIVE MANUAL TEST COMPLETE ==========");
        System.out.println();

        System.out.println("Instant paper trade test complete.");
    }
}