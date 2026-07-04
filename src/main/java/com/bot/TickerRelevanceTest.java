package com.bot;

import com.bot.model.NewsEvent;
import com.bot.strategy.TickerRelevanceFilter;

public class TickerRelevanceTest {

    public static void main(String[] args) {

        TickerRelevanceFilter filter = new TickerRelevanceFilter();

        NewsEvent appleNews = new NewsEvent(
                "test-1",
                "AAPL",
                "Apple reports record iPhone revenue",
                "The company beat analyst expectations.",
                System.currentTimeMillis()
        );

        NewsEvent nvidiaNews = new NewsEvent(
                "test-2",
                "AAPL",
                "NVIDIA unveils new AI chip",
                "The company announced a new GPU for local inference.",
                System.currentTimeMillis()
        );

        System.out.println("Apple news relevant? " + filter.isRelevant(appleNews));
        System.out.println("NVIDIA news relevant to AAPL? " + filter.isRelevant(nvidiaNews));
    }
}