package com.bot;

import com.bot.model.NewsEvent;
import com.bot.strategy.TickerRelevanceFilter;

public class TickerRelevanceFilterTest {

    public static void main(String[] args) {

        TickerRelevanceFilter filter =
                new TickerRelevanceFilter();

        String headline =
                "Micron Stock Has Become Volatile, But Key Metric Hints at Further Gains";

        String content =
                "Micron shares have become volatile as investors evaluate memory-chip demand and NAND pricing trends.";

        runTest(
                filter,
                "AMD SHOULD BE REJECTED",
                "AMD",
                headline,
                content,
                false
        );

        runTest(
                filter,
                "MRVL SHOULD BE REJECTED",
                "MRVL",
                headline,
                content,
                false
        );

        runTest(
                filter,
                "NVDA SHOULD BE REJECTED",
                "NVDA",
                headline,
                content,
                false
        );

        runTest(
                filter,
                "MU SHOULD PASS",
                "MU",
                headline,
                content,
                true
        );

        runTest(
                filter,
                "SNDK SHOULD BE REJECTED",
                "SNDK",
                headline,
                content,
                false
        );
    }

    private static void runTest(
            TickerRelevanceFilter filter,
            String testName,
            String ticker,
            String headline,
            String content,
            boolean expected
    ) {
        NewsEvent news =
                new NewsEvent(
                        "test-" + ticker,
                        ticker,
                        headline,
                        content,
                        System.currentTimeMillis()
                );

        boolean actual =
                filter.isRelevant(news);

        int score =
                filter.relevanceScore(news);

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Ticker: " + ticker);
        System.out.println("Relevance score: " + score);
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        System.out.println(expected == actual ? "PASS" : "FAIL");
    }
}