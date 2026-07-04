package com.bot.stream;

import com.bot.broker.AlpacaBroker;
import com.bot.model.NewsEvent;
import com.bot.strategy.NewsMomentumStrategy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AlpacaNewsStream {

    private final AlpacaBroker broker;
    private final NewsMomentumStrategy strategy;
    private final String ticker;
    private final int pollSeconds;
    private final Set<String> seenNewsIds = new HashSet<>();

    private volatile boolean running = false;

    public AlpacaNewsStream(
            AlpacaBroker broker,
            NewsMomentumStrategy strategy,
            String ticker,
            int pollSeconds
    ) {
        this.broker = broker;
        this.strategy = strategy;
        this.ticker = ticker;
        this.pollSeconds = pollSeconds;
    }

    public void start() {
        running = true;

        Thread thread = new Thread(this::runLoop);
        thread.setName("alpaca-news-stream-" + ticker);
        thread.setDaemon(true);
        thread.start();

        System.out.println("Alpaca news polling started for " + ticker);
    }

    public void stop() {
        running = false;
    }

    public void onNews(NewsEvent news) throws Exception {
        strategy.onNews(news);
    }

    private void runLoop() {
        while (running) {
            try {
                List<NewsEvent> latestNews =
                        broker.getLatestNews(ticker, 10);

                for (NewsEvent news : latestNews) {
                    if (news.getId() == null || news.getId().isBlank()) {
                        continue;
                    }

                    if (!seenNewsIds.add(news.getId())) {
                        continue;
                    }

                    System.out.println("LIVE NEWS RECEIVED: " + news.getHeadline());

                    strategy.onNews(news);
                }

                Thread.sleep(pollSeconds * 1000L);

            } catch (Exception e) {
                System.err.println("Alpaca news polling error: " + e.getMessage());

                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }
}