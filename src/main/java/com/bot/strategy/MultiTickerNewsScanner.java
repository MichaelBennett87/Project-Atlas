package com.bot.strategy;

import com.bot.broker.AlpacaBroker;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MultiTickerNewsScanner {

    private final AlpacaBroker broker;
    private final NewsOpportunityScorer scorer;
    private final TickerRelevanceFilter relevanceFilter;
    private final Set<String> seenNewsIds = new HashSet<>();

    public MultiTickerNewsScanner(
            AlpacaBroker broker,
            NewsOpportunityScorer scorer
    ) {
        this.broker = broker;
        this.scorer = scorer;
        this.relevanceFilter = new TickerRelevanceFilter();
    }

    public List<NewsOpportunity> scan(int limit) {
        System.out.println("Fetching broad market news...");

        List<NewsEvent> news = broker.getLatestMarketNews(limit);

        System.out.println("News/ticker events found: " + news.size());

        List<NewsOpportunity> opportunities = new ArrayList<>();

        int checked = 0;

        for (NewsEvent event : news) {
            if (event.getId() == null || event.getId().isBlank()) {
                continue;
            }

            if (!seenNewsIds.add(event.getId())) {
                continue;
            }

            if (!relevanceFilter.isRelevant(event)) {
                System.out.println(
                        "NEWS/TICKER REJECTED: not primary enough for " +
                                event.getTicker() +
                                " | relevanceScore=" +
                                relevanceFilter.relevanceScore(event) +
                                " | " +
                                event.getHeadline()
                );
                continue;
            }

            checked++;

            System.out.println(
                    "Scoring " +
                            checked +
                            "/" +
                            news.size() +
                            " " +
                            event.getTicker() +
                            " | " +
                            event.getHeadline()
            );

            try {
                NewsOpportunity opportunity = scorer.score(event);

                System.out.println(
                        "Score: " +
                                opportunity.finalScore +
                                " | passed=" +
                                opportunity.qualityPassed +
                                " | reason=" +
                                opportunity.rejectionReason +
                                " | " +
                                opportunity.sentiment
                );

                if (opportunity.qualityPassed && opportunity.finalScore > 0.55) {
                    opportunities.add(opportunity);
                }

            } catch (Exception e) {
                System.err.println(
                        "Failed scoring news " +
                                event.getId() +
                                " " +
                                event.getTicker() +
                                ": " +
                                e.getMessage()
                );
            }
        }

        opportunities.sort(
                Comparator.comparingDouble((NewsOpportunity o) -> o.finalScore)
                        .reversed()
        );

        return opportunities;
    }
}