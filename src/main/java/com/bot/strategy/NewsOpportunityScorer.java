package com.bot.strategy;

import com.bot.model.CatalystQualityDecision;
import com.bot.model.CatalystResult;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.news.CatalystScorer;
import com.bot.news.FinBertService;
import com.bot.sentiment.SentimentScore;

public class NewsOpportunityScorer {

    private final FinBertService finbert;
    private final CatalystClassifier catalystClassifier;
    private final CatalystQualityFilter catalystQualityFilter;
    private final CatalystScorer catalystScorer;

    public NewsOpportunityScorer(FinBertService finbert) {
        this.finbert = finbert;
        this.catalystClassifier = new CatalystClassifier();
        this.catalystQualityFilter = new CatalystQualityFilter();
        this.catalystScorer = new CatalystScorer();
    }

    public NewsOpportunity score(NewsEvent news) throws Exception {
        SentimentScore sentiment = finbert.analyze(news.fullText());
        CatalystResult catalyst = catalystClassifier.classify(news);

        CatalystQualityDecision decision =
                catalystQualityFilter.evaluate(catalyst, sentiment);

        double weightedCatalystScore =
                catalystScorer.score(news.fullText());

        boolean unknownCatalyst =
                catalyst != null &&
                        catalyst.type != null &&
                        "UNKNOWN".equals(catalyst.type.toString());

        boolean strongPositiveUnknown =
                unknownCatalyst &&
                        weightedCatalystScore >= 7.0 &&
                        sentiment.positive >= 0.80 &&
                        sentiment.negative <= 0.20 &&
                        sentiment.netSentiment() >= 0.60;

        boolean passed =
                decision.passed || strongPositiveUnknown;

        String reason =
                decision.reason;

        double catalystWeight =
                catalyst == null ? 0.0 : catalyst.weight;

        if (strongPositiveUnknown) {
            catalystWeight =
                    Math.min(0.75, weightedCatalystScore / 15.0);

            reason =
                    "UNKNOWN catalyst accepted as strong positive weighted catalyst";
        }

        double finalScore;

        if (!passed) {
            finalScore = -999.0;
        } else {
            finalScore =
                    (sentiment.netSentiment() * 0.65)
                            + (sentiment.positive * 0.20)
                            + (catalystWeight * 0.75)
                            - (sentiment.negative * 0.50);
        }

        return new NewsOpportunity(
                news,
                sentiment,
                catalyst,
                finalScore,
                passed,
                reason
        );
    }
}