package com.bot.intelligence;

import com.bot.master.StrategyContext;
import com.bot.model.Bar;
import com.bot.model.NewsEvent;
import com.bot.sentiment.SentimentScore;
import com.bot.technical.TechnicalAnalysis;

import java.util.Collections;
import java.util.List;

public class MarketFeatureEngine {

    public MarketFeatureSnapshot extract(StrategyContext context) {
        if (context == null) {
            return empty("UNKNOWN");
        }

        List<Bar> bars = context.getBars() == null ? Collections.emptyList() : context.getBars();
        String ticker = context.getTicker();
        long timestamp = bars.isEmpty() ? System.currentTimeMillis() : TechnicalAnalysis.latestTimestamp(bars);
        double latest = TechnicalAnalysis.latestClose(bars);
        double vwap = TechnicalAnalysis.vwap(bars, 30);
        double vwapDistance = latest <= 0 || vwap <= 0 ? 0.0 : (latest - vwap) / latest;

        SentimentScore sentiment = context.getSentiment();
        NewsEvent news = context.getLatestNews();
        long providerTs = news == null ? 0L : Math.max(news.getProviderTimestamp(), news.getTimestamp());
        double freshnessSeconds = providerTs <= 0 ? 999999.0 : Math.max(0.0, (System.currentTimeMillis() - providerTs) / 1000.0);

        double sentimentNet = sentiment == null ? (news == null ? 0.0 : news.getSentimentScore()) : sentiment.netSentiment();
        double sentimentPositive = sentiment == null ? Math.max(0.0, sentimentNet) : sentiment.positive;
        double sentimentNegative = sentiment == null ? Math.max(0.0, -sentimentNet) : sentiment.negative;

        return new MarketFeatureSnapshot(
                ticker,
                timestamp,
                bars.size(),
                latest,
                returnBack(bars, 1),
                returnBack(bars, 3),
                returnBack(bars, 5),
                returnBack(bars, 10),
                TechnicalAnalysis.percentDropFromRecentHigh(bars, 20),
                TechnicalAnalysis.bounceFromRecentLow(bars, 20),
                TechnicalAnalysis.relativeVolume(bars, 5),
                TechnicalAnalysis.relativeVolume(bars, 20),
                TechnicalAnalysis.greenVolumeRatio(bars, 10),
                TechnicalAnalysis.atrPercent(bars, 14),
                TechnicalAnalysis.rsi(bars, 14),
                vwap,
                vwapDistance,
                TechnicalAnalysis.bullishBreak(bars),
                TechnicalAnalysis.reclaimedVwap(bars, 30),
                TechnicalAnalysis.failedBreakdown(bars),
                TechnicalAnalysis.higherLows(bars, 3),
                TechnicalAnalysis.noFreshLow(bars, 3),
                sentimentNet,
                sentimentPositive,
                sentimentNegative,
                news == null ? 0.0 : Math.max(news.getCatalystScore(), com.bot.master.CatalystQualityGate.tradeableCatalystScore(news)),
                freshnessSeconds,
                news == null ? "" : news.getSource(),
                news == null ? "" : news.getHeadline()
        );
    }

    private static MarketFeatureSnapshot empty(String ticker) {
        return new MarketFeatureSnapshot(
                ticker,
                System.currentTimeMillis(),
                0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                50.0,
                0.0,
                0.0,
                false,
                false,
                false,
                false,
                false,
                0.0,
                0.0,
                0.0,
                0.0,
                999999.0,
                "",
                ""
        );
    }

    private static double returnBack(List<Bar> bars, int barsBack) {
        if (bars == null || bars.size() <= barsBack) {
            return 0.0;
        }
        Bar latest = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 1 - barsBack);
        if (latest == null || previous == null || previous.close <= 0.0) {
            return 0.0;
        }
        return (latest.close - previous.close) / previous.close;
    }
}
