package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;

public class OrderFillTest {

    public static void main(String[] args) {

        AlpacaBroker broker =
                new AlpacaBroker();

        TradeJournal journal =
                new TradeJournal();

        OrderExecutor orders =
                new OrderExecutor(
                        broker,
                        journal
                );

        boolean filled =
                orders.buyMarketAndWaitForFill(
                        "AAPL",
                        1
                );

        System.out.println(
                "Filled = " + filled
        );
    }
}