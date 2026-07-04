package com.bot;

import com.bot.broker.AlpacaBroker;

public class AlpacaPaperOrderTest {

    public static void main(String[] args) throws Exception {

        AlpacaBroker broker = new AlpacaBroker();

        String ticker = "AAPL";
        int qty = 1;

        System.out.println("Submitting PAPER buy order...");
        String buyOrderId = broker.buyMarket(ticker, qty);
        System.out.println("Buy order ID: " + buyOrderId);

        Thread.sleep(5_000);

        System.out.println("Submitting PAPER sell order...");
        String sellOrderId = broker.sellMarket(ticker, qty);
        System.out.println("Sell order ID: " + sellOrderId);

        System.out.println("Paper order test complete.");
    }
}