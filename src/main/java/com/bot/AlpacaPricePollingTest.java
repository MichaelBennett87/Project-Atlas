package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.MarketDataCache;
import com.bot.stream.AlpacaPriceStream;

public class AlpacaPricePollingTest {

    public static void main(String[] args) throws Exception {

        AlpacaBroker broker = new AlpacaBroker();

        MarketDataCache marketData = new MarketDataCache();
        TradeJournal journal = new TradeJournal();
        OrderExecutor orders = new OrderExecutor(broker, journal);
        PositionManager positions = new PositionManager(marketData, orders);

        AlpacaPriceStream priceStream =
                new AlpacaPriceStream(
                        broker,
                        marketData,
                        positions,
                        "AAPL",
                        5
                );

        priceStream.start();

        Thread.sleep(20_000);

        priceStream.stop();

        System.out.println("Price polling test complete.");
    }
}