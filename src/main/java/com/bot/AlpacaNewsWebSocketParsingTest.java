package com.bot;

import com.bot.model.NewsEvent;
import com.bot.stream.AlpacaNewsWebSocketStream;

import java.util.ArrayList;
import java.util.List;

public class AlpacaNewsWebSocketParsingTest {

    public static void main(String[] args) {
        List<NewsEvent> receivedNews =
                new ArrayList<>();

        AlpacaNewsWebSocketStream stream =
                new AlpacaNewsWebSocketStream(
                        "test-key",
                        "test-secret",
                        receivedNews::add
                );

        String sampleMessage =
                "[" +
                        "{" +
                        "\"T\":\"n\"," +
                        "\"id\":12345," +
                        "\"headline\":\"SMCI raises guidance after strong AI server demand\"," +
                        "\"summary\":\"Super Micro Computer raised guidance after reporting strong demand.\"," +
                        "\"content\":\"Super Micro Computer raised guidance after reporting strong AI server demand and record orders.\"," +
                        "\"created_at\":\"2026-06-14T20:00:00Z\"," +
                        "\"symbols\":[\"SMCI\"]" +
                        "}" +
                        "]";

        stream.handleRawMessageForTest(sampleMessage);

        boolean expectedOneNewsEvent =
                receivedNews.size() == 1;

        NewsEvent news =
                expectedOneNewsEvent
                        ? receivedNews.get(0)
                        : null;

        boolean expectedTicker =
                news != null &&
                        "SMCI".equals(news.getTicker());

        boolean expectedHeadline =
                news != null &&
                        news.getHeadline().contains("raises guidance");

        System.out.println();
        System.out.println("=== ALPACA NEWS WEBSOCKET PARSING TEST ===");
        System.out.println("Received news count: " + receivedNews.size());
        System.out.println("Expected one news event: true");
        System.out.println("Actual one news event: " + expectedOneNewsEvent);

        if (news != null) {
            System.out.println("News ID: " + news.getId());
            System.out.println("Ticker: " + news.getTicker());
            System.out.println("Headline: " + news.getHeadline());
            System.out.println("Content: " + news.getContent());
            System.out.println("Timestamp: " + news.getTimestamp());
        }

        System.out.println("Expected ticker SMCI: true");
        System.out.println("Actual ticker SMCI: " + expectedTicker);
        System.out.println("Expected headline contains guidance: true");
        System.out.println("Actual headline contains guidance: " + expectedHeadline);

        if (expectedOneNewsEvent && expectedTicker && expectedHeadline) {
            System.out.println("PASS");
        } else {
            System.out.println("FAIL");
        }
    }
}