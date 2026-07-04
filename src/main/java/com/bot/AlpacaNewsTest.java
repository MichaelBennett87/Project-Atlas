package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.model.NewsEvent;

import java.util.List;

public class AlpacaNewsTest {

    public static void main(String[] args) {

        AlpacaBroker broker = new AlpacaBroker();

        List<NewsEvent> news = broker.getLatestNews("AAPL", 5);

        System.out.println("News count: " + news.size());

        for (NewsEvent event : news) {
            System.out.println("ID: " + event.getId());
            System.out.println("Ticker: " + event.getTicker());
            System.out.println("Headline: " + event.getHeadline());
            System.out.println("Content: " + event.getContent());
            System.out.println("Timestamp: " + event.getTimestamp());
            System.out.println("---");
        }
    }
}