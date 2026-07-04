package com.bot.strategy.unified;

import com.bot.master.CatalystQualityGate;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.sentiment.SentimentScore;
import com.bot.model.RelevanceDecision;
import com.bot.strategy.TickerRelevanceFilter;
import com.bot.technical.TechnicalAnalysis;

public class MomentumNewsRunnerStrategy extends AbstractUnifiedStrategy {

    private final TickerRelevanceFilter tickerRelevanceFilter = new TickerRelevanceFilter();

    @Override
    public String name() {
        return "MOMENTUM_NEWS_RUNNER";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        if (!context.hasNews()) {
            return hold(context, 0.0, "No fresh news for momentum-news strategy.");
        }

        String qualityReject = CatalystQualityGate.rejectReason(context.getLatestNews());
        if (qualityReject != null) {
            return hold(context, 0.0, "News momentum rejected by profitability catalyst gate: " + qualityReject);
        }

        SentimentScore sentiment = context.getSentiment();
        double net = sentiment == null ? 0.0 : sentiment.netSentiment();
        double positive = sentiment == null ? 0.0 : sentiment.positive;
        int barCount = context.getBars() == null ? 0 : context.getBars().size();

        double measuredRvol = TechnicalAnalysis.relativeVolume(context.getBars(), 20);
        double rvolForScoring = measuredRvol > 0 ? measuredRvol : 0.0;
        boolean bullishBreak = TechnicalAnalysis.bullishBreak(context.getBars());
        boolean reclaimedVwap = TechnicalAnalysis.reclaimedVwap(context.getBars(), 30);
        boolean aboveVwap = TechnicalAnalysis.latestClose(context.getBars()) > TechnicalAnalysis.vwap(context.getBars(), 30);
        boolean vwap = reclaimedVwap || aboveVwap;
        boolean risingCloses = risingCloses(context, 3);
        boolean greenBar = latestGreen(context);
        double priceContinuation = priceContinuation(context, 5);

        if (badNewsKeyword(context.newsText())) {
            return hold(context, 0.0, "News contains catastrophic keyword.");
        }

        RelevanceDecision relevance = tickerRelevanceFilter.evaluate(context.getLatestNews());
        boolean primarySubject = relevance == RelevanceDecision.PRIMARY_SUBJECT;

        if (!primarySubject) {
            return hold(context, 0.0, "News momentum rejected: ticker is not the primary subject of the article. relevance=" + relevance);
        }

        double catalystScore = CatalystQualityGate.tradeableCatalystScore(context.getLatestNews());

        boolean confirmedByPrice =
                measuredRvol >= 1.50 ||
                        bullishBreak ||
                        (vwap && risingCloses) ||
                        (greenBar && priceContinuation >= 0.004);

        double confidence = 0.0;
        confidence += Math.max(0.0, net) * 0.24;
        confidence += positive * 0.18;
        confidence += catalystScore * 0.30;
        confidence += Math.min(1.0, rvolForScoring / 4.0) * 0.12;
        confidence += bullishBreak ? 0.08 : 0.0;
        confidence += vwap ? 0.04 : 0.0;
        confidence += risingCloses ? 0.03 : 0.0;
        confidence += priceContinuation >= 0.006 ? 0.04 : 0.0;
        confidence = TechnicalAnalysis.clamp(confidence);

        if (catalystScore < 0.30) {
            return hold(context, confidence, String.format("News catalyst quality too weak: catalyst=%.2f confidence=%.2f", catalystScore, confidence));
        }

        if (!confirmedByPrice) {
            return hold(context, confidence, String.format(
                    "Tradeable catalyst is on watch, but no price confirmation yet: confidence=%.2f catalyst=%.2f net=%.2f rvol=%.2f bullishBreak=%s vwap=%s risingCloses=%s continuation=%.2f%% bars=%d",
                    confidence, catalystScore, net, measuredRvol, bullishBreak, vwap, risingCloses, priceContinuation * 100.0, barCount));
        }

        if (positive >= 0.70 &&
                net >= 0.55 &&
                confidence >= 0.72) {
            return buy(
                    context,
                    confidence,
                    0.05 + catalystScore * 0.07,
                    String.format("High-quality confirmed news momentum: net=%.2f positive=%.2f catalyst=%.2f rvol=%.2f bullishBreak=%s vwap=%s risingCloses=%s continuation=%.2f%% relevance=%s",
                            net, positive, catalystScore, measuredRvol, bullishBreak, vwap, risingCloses, priceContinuation * 100.0, relevance)
            );
        }

        return hold(context, confidence, String.format("News momentum not strong enough: confidence=%.2f net=%.2f catalyst=%.2f rvol=%.2f bars=%d relevance=%s",
                confidence, net, catalystScore, measuredRvol, barCount, relevance));
    }

    private boolean risingCloses(StrategyContext context, int count) {
        if (context == null || context.getBars() == null || context.getBars().size() < count) {
            return false;
        }
        int start = context.getBars().size() - count;
        double previous = context.getBars().get(start).close;
        for (int i = start + 1; i < context.getBars().size(); i++) {
            double close = context.getBars().get(i).close;
            if (close <= previous) {
                return false;
            }
            previous = close;
        }
        return true;
    }

    private boolean latestGreen(StrategyContext context) {
        if (context == null || context.getBars() == null || context.getBars().isEmpty()) {
            return false;
        }
        int last = context.getBars().size() - 1;
        return context.getBars().get(last).close > context.getBars().get(last).open;
    }

    private double priceContinuation(StrategyContext context, int lookback) {
        if (context == null || context.getBars() == null || context.getBars().size() < 2) {
            return 0.0;
        }
        int start = Math.max(0, context.getBars().size() - Math.max(2, lookback));
        double first = context.getBars().get(start).close;
        double latest = TechnicalAnalysis.latestClose(context.getBars());
        if (first <= 0 || latest <= 0) {
            return 0.0;
        }
        return (latest - first) / first;
    }
}
