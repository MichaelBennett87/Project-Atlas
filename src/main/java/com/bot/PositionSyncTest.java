package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.MarketDataCache;

public class PositionSyncTest {

    public static void main(String[] args) {

        AlpacaBroker broker = new AlpacaBroker();

        MarketDataCache marketData = new MarketDataCache();
        TradeJournal journal = new TradeJournal();
        OrderExecutor orders = new OrderExecutor(broker, journal);

        PositionManager positionManager =
                new PositionManager(marketData, orders);

        positionManager.syncFromBroker(broker.getOpenPositions());

        System.out.println("Synced positions: " + positionManager.openPositionCount());
    }
}