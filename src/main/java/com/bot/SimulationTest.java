package com.bot;

import com.bot.broker.AlpacaAccountService;
import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.AccountService;
import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.news.FinBertService;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.strategy.MarketConfirmationFilter;
import com.bot.strategy.NewsMomentumStrategy;
import com.bot.stream.AlpacaNewsStream;
import com.bot.stream.AlpacaPriceStream;

public class SimulationTest {

    public static void main(String[] args) throws Exception {

        AlpacaBroker broker = new AlpacaBroker(true);

        MarketDataCache marketData = new MarketDataCache();
        TradeJournal journal = new TradeJournal();

        OrderExecutor orders = new OrderExecutor(broker, journal);
        PositionManager positions = new PositionManager(marketData, orders);

        AccountService account = new AlpacaAccountService(broker);
        AdvancedRiskEngine risk = new AdvancedRiskEngine(account, positions);

        FinBertService finbert = new FinBertService();
        MarketConfirmationFilter confirmation = new MarketConfirmationFilter(marketData);

        NewsMomentumStrategy strategy =
                new NewsMomentumStrategy(
                        finbert,
                        confirmation,
                        risk,
                        orders,
                        positions
                );

        AlpacaPriceStream priceStream =
                new AlpacaPriceStream(marketData, positions);

        String ticker = "AAPL";

        AlpacaNewsStream newsStream =
                new AlpacaNewsStream(
                        broker,
                        strategy,
                        ticker,
                        60
                );

        long now = System.currentTimeMillis();

        for (int i = 0; i < 25; i++) {
            Bar bar = new Bar();
            bar.ticker = ticker;
            bar.timestamp = now + (i * 60_000L);
            bar.open = 100 + i * 0.10;
            bar.high = 100 + i * 0.10 + 0.25;
            bar.low = 100 + i * 0.10 - 0.25;
            bar.close = 100 + i * 0.10;
            bar.volume = i == 24 ? 300_000 : 100_000;

            priceStream.onBar(bar);
        }

        NewsEvent news = new NewsEvent(
                "sim-news-001",
                ticker,
                "Apple reports record revenue and raises guidance",
                "The company beat expectations and increased its outlook for next quarter.",
                now + 26 * 60_000L
        );

        newsStream.onNews(news);

        double[] prices = {103, 104, 105, 106, 107, 106, 104, 101};

        for (double price : prices) {
            Bar bar = new Bar();
            bar.ticker = ticker;
            bar.timestamp = System.currentTimeMillis();
            bar.open = price;
            bar.high = price + 0.20;
            bar.low = price - 0.20;
            bar.close = price;
            bar.volume = 200_000;

            priceStream.onBar(bar);
        }
    }
}