package com.bot;

import com.bot.news.FinBertService;
import com.bot.sentiment.SentimentScore;

public class FinBertTest {

    public static void main(String[] args) throws Exception {

        FinBertService finbert = new FinBertService();

        SentimentScore score = finbert.analyze(
                "Apple reports record revenue and raises guidance for next quarter."
        );

        System.out.println(score);
    }
}