package com.bot.backtest;

import com.bot.engine.PositionManager;
import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.strategy.NewsMomentumStrategy;

import java.util.Comparator;
import java.util.List;

public class Backtester {

    private final NewsMomentumStrategy strategy;
    private final PositionManager positions;
    private final MarketDataCache market;

    public Backtester(
            NewsMomentumStrategy strategy,
            PositionManager positions,
            MarketDataCache market
    ) {
        this.strategy = strategy;
        this.positions = positions;
        this.market = market;
    }

    public void run(List<Bar> historicalBars, List<NewsEvent> historicalNews) throws Exception {

        historicalBars.sort(Comparator.comparingLong(b -> b.timestamp));
        historicalNews.sort(Comparator.comparingLong(NewsEvent::getTimestamp));

        int newsIndex = 0;

        for (Bar bar : historicalBars) {
            market.addBar(bar.ticker, bar);
            positions.onPrice(bar.ticker, bar.close);

            while (newsIndex < historicalNews.size()
                    && historicalNews.get(newsIndex).getTimestamp() <= bar.timestamp) {

                strategy.onNews(historicalNews.get(newsIndex));
                newsIndex++;
            }
        }
    }
}