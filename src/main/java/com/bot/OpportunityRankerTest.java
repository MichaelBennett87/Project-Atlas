package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.news.FinBertService;
import com.bot.strategy.MultiTickerNewsScanner;
import com.bot.strategy.NewsOpportunityScorer;
import com.bot.strategy.OpportunityRanker;

import java.util.List;

public class OpportunityRankerTest {

    public static void main(String[] args) throws Exception {

        AlpacaBroker broker =
                new AlpacaBroker();

        FinBertService finbert =
                new FinBertService();

        NewsOpportunityScorer scorer =
                new NewsOpportunityScorer(finbert);

        MultiTickerNewsScanner scanner =
                new MultiTickerNewsScanner(
                        broker,
                        scorer
                );

        OpportunityRanker ranker =
                new OpportunityRanker(broker);

        List<NewsOpportunity> opportunities =
                scanner.scan(25);

        List<RankedOpportunity> ranked =
                ranker.rank(opportunities);

        System.out.println("Ranked opportunities found: " + ranked.size());

        for (RankedOpportunity opportunity : ranked) {
            System.out.println("---");
            System.out.println("Ticker: " + opportunity.opportunity.news.getTicker());
            System.out.println("Rank Score: " + opportunity.rankScore);
            System.out.println("Market Quality: " + opportunity.marketQuality);
            System.out.println("Catalyst: " + opportunity.opportunity.catalyst);
            System.out.println("Sentiment: " + opportunity.opportunity.sentiment);
            System.out.println("Headline: " + opportunity.opportunity.news.getHeadline());
        }
    }
}