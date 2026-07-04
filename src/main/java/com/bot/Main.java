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
import com.bot.strategy.MarketConfirmationFilter;
import com.bot.strategy.NewsMomentumStrategy;
import com.bot.stream.AlpacaNewsStream;
import com.bot.stream.AlpacaPriceStream;

public class Main {

    public static void main(String[] args) throws Exception {

        String ticker =
                getEnvOrDefault("BOT_TICKER", "AAPL");

        boolean tradingEnabled =
                Boolean.parseBoolean(
                        getEnvOrDefault("TRADING_ENABLED", "false")
                );

        boolean dryRun =
                Boolean.parseBoolean(
                        getEnvOrDefault("DRY_RUN", "true")
                );

        System.out.println("Main started.");
        System.out.println("Ticker: " + ticker);
        System.out.println("TRADING_ENABLED=" + tradingEnabled);
        System.out.println("DRY_RUN=" + dryRun);

        if (tradingEnabled && dryRun) {
            System.out.println("WARNING: TRADING_ENABLED=true but DRY_RUN=true.");
            System.out.println("Orders will still be blocked by OrderExecutor.");
        }

        if (tradingEnabled && !dryRun) {
            System.out.println("PAPER ORDER MODE ENABLED.");
            System.out.println("Confirm ALPACA_BASE_URL is https://paper-api.alpaca.markets");
        } else {
            System.out.println("ORDER EXECUTION DISABLED. Signal processing only.");
        }

        AlpacaBroker broker =
                new AlpacaBroker();

        MarketDataCache marketData =
                new MarketDataCache();

        TradeJournal journal =
                new TradeJournal();

        boolean allowOrders =
                tradingEnabled && !dryRun;

        OrderExecutor orderExecutor =
                new OrderExecutor(
                        broker,
                        journal,
                        allowOrders
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

        MarketConfirmationFilter confirmation =
                new MarketConfirmationFilter(
                        marketData
                );

        NewsMomentumStrategy strategy =
                new NewsMomentumStrategy(
                        finbert,
                        confirmation,
                        riskEngine,
                        orderExecutor,
                        positionManager
                );

        AlpacaPriceStream priceStream =
                new AlpacaPriceStream(
                        broker,
                        marketData,
                        positionManager,
                        ticker,
                        10
                );

        AlpacaNewsStream newsStream =
                new AlpacaNewsStream(
                        broker,
                        strategy,
                        ticker,
                        60
                );

        priceStream.start();
        newsStream.start();
    }

    private static String getEnvOrDefault(
            String key,
            String defaultValue
    ) {
        String value =
                System.getenv(key);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }
}