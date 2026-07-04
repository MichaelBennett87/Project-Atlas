package com.bot.master;

import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.sentiment.SentimentScore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StrategyContext {

    private final String ticker;
    private final MarketDataCache marketData;
    private final List<Bar> bars;
    private final NewsEvent latestNews;
    private final SentimentScore sentiment;
    private final double lastPrice;
    private final double accountEquity;
    private final long createdAt;

    public StrategyContext(
            String ticker,
            MarketDataCache marketData,
            NewsEvent latestNews,
            SentimentScore sentiment,
            double lastPrice,
            double accountEquity
    ) {
        this.ticker = ticker == null ? "" : ticker.trim().toUpperCase();
        this.marketData = marketData;
        this.bars = marketData == null
                ? Collections.emptyList()
                : new ArrayList<>(marketData.recentBars(this.ticker, 120));
        this.latestNews = latestNews;
        this.sentiment = sentiment;
        this.lastPrice = lastPrice > 0 ? lastPrice : inferLastPrice(this.bars);
        this.accountEquity = Math.max(0.0, accountEquity);
        this.createdAt = System.currentTimeMillis();
    }

    public String getTicker() { return ticker; }
    public MarketDataCache getMarketData() { return marketData; }
    public List<Bar> getBars() { return Collections.unmodifiableList(bars); }
    public NewsEvent getLatestNews() { return latestNews; }
    public SentimentScore getSentiment() { return sentiment; }
    public double getLastPrice() { return lastPrice; }
    public double getAccountEquity() { return accountEquity; }
    public long getCreatedAt() { return createdAt; }

    public boolean hasNews() {
        return latestNews != null;
    }

    public String newsText() {
        return latestNews == null ? "" : latestNews.fullText();
    }

    private static double inferLastPrice(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0.0;
        }
        return bars.get(bars.size() - 1).close;
    }
}
