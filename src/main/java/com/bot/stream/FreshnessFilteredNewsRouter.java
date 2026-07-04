package com.bot.stream;

import com.bot.model.NewsEvent;

import java.util.function.Consumer;

public class FreshnessFilteredNewsRouter {

    private final MultiSourceNewsFreshnessEngine freshnessEngine;
    private final Consumer<NewsEvent> downstream;

    public FreshnessFilteredNewsRouter(
            MultiSourceNewsFreshnessEngine freshnessEngine,
            Consumer<NewsEvent> downstream
    ) {
        this.freshnessEngine = freshnessEngine;
        this.downstream = downstream;
    }

    public void onNews(
            NewsEvent news
    ) {
        if (news == null) {
            return;
        }

        String fastRejectReason = NewsPriorityGate.preFreshnessRejectReason(news);
        if (fastRejectReason != null) {
            System.out.println(
                    "NEWS REJECTED BEFORE FRESHNESS: " +
                            news.getTicker() +
                            " source=" + news.getSource() +
                            " reason=" + fastRejectReason +
                            " priority=" + NewsPriorityGate.priorityLabel(news) +
                            " headline=" + news.getHeadline()
            );
            return;
        }

        if (NewsPriorityGate.isHighPriorityCatalyst(news)) {
            System.out.println(
                    "HIGH PRIORITY NEWS INTERRUPT: " +
                            "ticker=" + news.getTicker() +
                            " source=" + news.getSource() +
                            " priority=" + NewsPriorityGate.priorityLabel(news) +
                            " headline=" + news.getHeadline()
            );
        }

        NewsFreshnessDecision decision =
                freshnessEngine.evaluate(news);

        news.setStaleRejected(!decision.allowed);
        news.setFreshnessReason(decision.reason);
        news.setSourceLagMs(decision.sourceLagMs);

        System.out.println(
                "NEWS FRESHNESS CHECK: " +
                        "ticker=" + news.getTicker() +
                        " source=" + news.getSource() +
                        " category=" + decision.category +
                        " allowed=" + decision.allowed +
                        " duplicate=" + decision.duplicate +
                        " providerAgeMs=" + decision.providerAgeMs +
                        " sourceLagMs=" + decision.sourceLagMs +
                        " firstSource=" + decision.firstSource +
                        " priority=" + NewsPriorityGate.priorityLabel(news) +
                        " reason=" + decision.reason
        );

        if (!decision.allowed) {
            System.out.println(
                    "NEWS REJECTED BEFORE STRATEGY: " +
                            news.getTicker() +
                            " source=" + news.getSource() +
                            " reason=" + decision.category +
                            " headline=" + news.getHeadline()
            );
            return;
        }

        downstream.accept(news);
    }
}
