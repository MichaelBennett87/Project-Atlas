package com.bot;

import com.bot.broker.AlpacaAccountService;
import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.AccountService;
import com.bot.model.MarketDataCache;
import com.bot.news.FinBertService;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.strategy.InstantNewsMomentumStrategy;
import com.bot.strategy.OpportunityRanker;
import com.bot.stream.AlpacaNewsWebSocketStream;
import com.bot.stream.PriceStreamRegistry;

public class InstantNewsMain {

    private static final int PRICE_POLL_SECONDS = 1;

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

        positionManager.syncFromBroker(
                broker.getOpenPositions()
        );

        AccountService accountService =
                new AlpacaAccountService(
                        broker
                );

        AdvancedRiskEngine riskEngine =
                new AdvancedRiskEngine(
                        accountService,
                        positionManager
                );

        FinBertService finbert =
                new FinBertService();

        OpportunityRanker opportunityRanker =
                new OpportunityRanker(
                        broker
                );

        PriceStreamRegistry priceStreamRegistry =
                new PriceStreamRegistry(
                        broker,
                        marketData,
                        positionManager,
                        PRICE_POLL_SECONDS
                );

        InstantNewsMomentumStrategy instantNewsMomentumStrategy =
                new InstantNewsMomentumStrategy(
                        broker,
                        finbert,
                        opportunityRanker,
                        riskEngine,
                        orderExecutor,
                        positionManager,
                        priceStreamRegistry
                );

        AlpacaNewsWebSocketStream newsStream =
                new AlpacaNewsWebSocketStream(
                        news -> {
                            try {
                                instantNewsMomentumStrategy.onNews(news);
                            } catch (Exception e) {
                                System.err.println(
                                        "Instant news strategy failed: " +
                                                e.getMessage()
                                );

                                e.printStackTrace();
                            }
                        }
                );

        System.out.println("InstantNewsMain started.");
        System.out.println("TRADING_ENABLED=" + System.getenv("TRADING_ENABLED"));
        System.out.println("News intake mode: ALPACA_WEBSOCKET");
        System.out.println("PRICE_POLL_SECONDS=" + PRICE_POLL_SECONDS);

        newsStream.start();

        while (true) {
            Thread.sleep(60_000L);
        }
    }
}