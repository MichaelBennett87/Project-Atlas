package com.bot;

import com.bot.broker.AlpacaBroker;

public class OrderBehaviorTest {

    public static void main(String[] args) {

        AlpacaBroker broker =
                new AlpacaBroker(true);

        System.out.println("=== ORDER BEHAVIOR TEST ===");

        String buyOrderId =
                broker.buyMarket(
                        "TEST",
                        1
                );

        System.out.println("Buy order id: " + buyOrderId);

        String sellOrderId =
                broker.sellMarket(
                        "TEST",
                        1
                );

        System.out.println("Sell order id: " + sellOrderId);

        String shortOrderId =
                broker.shortMarket(
                        "TEST",
                        1
                );

        System.out.println("Short order id: " + shortOrderId);

        String coverOrderId =
                broker.coverShortMarket(
                        "TEST",
                        1
                );

        System.out.println("Cover order id: " + coverOrderId);

        System.out.println("TEST PASSED: dry-run order path is callable.");
    }
}