package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.model.Bar;

public class LatestBarTest {

    public static void main(String[] args) {

        AlpacaBroker broker = new AlpacaBroker();

        testBar(broker, "AMD");
        testBar(broker, "NVDA");
        testBar(broker, "MRVL");
    }

    private static void testBar(AlpacaBroker broker, String ticker) {
        Bar bar = broker.getLatestBar(ticker);

        System.out.println("---");
        System.out.println("Ticker: " + ticker);

        if (bar == null) {
            System.out.println("No bar returned.");
            return;
        }

        System.out.println("Timestamp: " + bar.timestamp);
        System.out.println("Open: " + bar.open);
        System.out.println("High: " + bar.high);
        System.out.println("Low: " + bar.low);
        System.out.println("Close: " + bar.close);
        System.out.println("Volume: " + bar.volume);
    }
}