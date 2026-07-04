package com.bot;

import com.bot.broker.AlpacaBroker;

public class AlpacaBrokerTest {

    public static void main(String[] args) {

        AlpacaBroker broker = new AlpacaBroker();

        System.out.println("Equity: " + broker.getAccount().getEquity());
        System.out.println("Buying Power: " + broker.getAccount().getBuyingPower());
        System.out.println("AAPL Price: " + broker.getPrice("AAPL"));
    }
}