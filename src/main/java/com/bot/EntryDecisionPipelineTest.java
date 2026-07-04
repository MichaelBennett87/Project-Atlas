package com.bot;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.EntryDecision;
import com.bot.model.MarketQuality;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.model.RelevanceDecision;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.EntryDecisionService;

public class EntryDecisionPipelineTest {

    public static void main(String[] args) {

        NewsEvent news =
                new NewsEvent(
                        "pipeline-test-1",
                        "SMCI",
                        "SMCI raises guidance after record AI server demand",
                        "Super Micro Computer raised full-year guidance after reporting record AI server demand and strong revenue growth.",
                        System.currentTimeMillis()
                );

        SentimentScore sentiment =
                new SentimentScore(
                        0.95,
                        0.03,
                        0.02
                );

        CatalystResult catalyst =
                new CatalystResult(
                        CatalystType.GUIDANCE_RAISE,
                        0.90,
                        "Guidance raise detected"
                );

        NewsOpportunity opportunity =
                new NewsOpportunity(
                        news,
                        sentiment,
                        catalyst,
                        0.95,
                        true,
                        "High quality opportunity"
                );

        MarketQuality marketQuality =
                new MarketQuality(
                        "SMCI",
                        100.00,
                        99.95,
                        100.05,
                        0.001,
                        true,
                        0.90
                );

        RankedOpportunity rankedOpportunity =
                new RankedOpportunity(
                        opportunity,
                        marketQuality,
                        0.98,
                        "Top ranked opportunity"
                );

        EntryDecisionService entryDecisionService =
                new EntryDecisionService();

        EntryDecision decision =
                entryDecisionService.decide(
                        rankedOpportunity,
                        RelevanceDecision.PRIMARY_SUBJECT
                );

        System.out.println();
        System.out.println("=== ENTRY DECISION PIPELINE TEST ===");
        System.out.println("Ticker: " + news.getTicker());
        System.out.println("Headline: " + news.getHeadline());
        System.out.println("Sentiment: " + sentiment);
        System.out.println("Catalyst: " + catalyst);
        System.out.println("Rank Score: " + rankedOpportunity.rankScore);
        System.out.println("Market Quality: " + marketQuality.qualityScore);

        System.out.println();
        System.out.println("Expected decision: IMMEDIATE_ENTRY");
        System.out.println("Actual decision: " + decision);

        if (decision == EntryDecision.IMMEDIATE_ENTRY) {
            System.out.println("PASS");
        } else {
            System.out.println("FAIL");
        }
    }
}