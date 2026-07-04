package com.bot;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.EntryDecision;
import com.bot.model.Level2OrderBookProfile;
import com.bot.model.MarketQuality;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.model.RelevanceDecision;
import com.bot.model.SectorMomentumProfile;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.EntryDecisionService;

public class Level2AndSectorFilterTest {

    public static void main(String[] args) {
        runStrongLevel2AndStrongSectorPassTest();
        runBadLevel2BlocksAutoBuyTest();
        runBadSectorBlocksAutoBuyTest();
    }

    private static void runStrongLevel2AndStrongSectorPassTest() {
        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        null,
                        null,
                        ticker -> strongLevel2(ticker),
                        ticker -> strongSector(ticker)
                );

        EntryDecision decision =
                service.decide(
                        rankedOpportunity(),
                        RelevanceDecision.PRIMARY_SUBJECT
                );

        System.out.println();
        System.out.println("=== STRONG LEVEL 2 + STRONG SECTOR TEST ===");
        System.out.println("Expected decision: IMMEDIATE_ENTRY");
        System.out.println("Actual decision: " + decision);
        System.out.println(decision == EntryDecision.IMMEDIATE_ENTRY ? "PASS" : "FAIL");
    }

    private static void runBadLevel2BlocksAutoBuyTest() {
        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        null,
                        null,
                        ticker -> badLevel2(ticker),
                        ticker -> strongSector(ticker)
                );

        EntryDecision decision =
                service.decide(
                        rankedOpportunity(),
                        RelevanceDecision.PRIMARY_SUBJECT
                );

        System.out.println();
        System.out.println("=== BAD LEVEL 2 BLOCK TEST ===");
        System.out.println("Expected decision: PENDING_CONFIRMATION");
        System.out.println("Actual decision: " + decision);
        System.out.println(decision == EntryDecision.PENDING_CONFIRMATION ? "PASS" : "FAIL");
    }

    private static void runBadSectorBlocksAutoBuyTest() {
        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        null,
                        null,
                        ticker -> strongLevel2(ticker),
                        ticker -> badSector(ticker)
                );

        EntryDecision decision =
                service.decide(
                        rankedOpportunity(),
                        RelevanceDecision.PRIMARY_SUBJECT
                );

        System.out.println();
        System.out.println("=== BAD SECTOR BLOCK TEST ===");
        System.out.println("Expected decision: PENDING_CONFIRMATION");
        System.out.println("Actual decision: " + decision);
        System.out.println(decision == EntryDecision.PENDING_CONFIRMATION ? "PASS" : "FAIL");
    }

    private static RankedOpportunity rankedOpportunity() {
        NewsEvent news =
                new NewsEvent(
                        "level2-sector-test",
                        "SMCI",
                        "SMCI raises guidance after record AI server demand",
                        "Super Micro Computer raised full-year guidance after record AI server demand.",
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
                        "High quality test opportunity"
                );

        MarketQuality marketQuality =
                new MarketQuality(
                        "SMCI",
                        100.00,
                        99.95,
                        100.05,
                        0.001,
                        true,
                        0.95
                );

        return new RankedOpportunity(
                opportunity,
                marketQuality,
                0.98,
                "High ranking test opportunity"
        );
    }

    private static Level2OrderBookProfile strongLevel2(String ticker) {
        return new Level2OrderBookProfile(
                ticker,
                250_000,
                90_000,
                0.47,
                0.001,
                0.92,
                true,
                "BID_SUPPORT",
                "Strong bid support and tight spread"
        );
    }

    private static Level2OrderBookProfile badLevel2(String ticker) {
        return new Level2OrderBookProfile(
                ticker,
                40_000,
                260_000,
                -0.73,
                0.001,
                0.20,
                true,
                "HEAVY_ASK_PRESSURE",
                "Ask side is much heavier than bid side"
        );
    }

    private static SectorMomentumProfile strongSector(String ticker) {
        return new SectorMomentumProfile(
                ticker,
                "TECH_AI_INFRASTRUCTURE",
                0.025,
                0.88,
                true,
                "STRONG_SECTOR",
                "Sector is moving with the trade"
        );
    }

    private static SectorMomentumProfile badSector(String ticker) {
        return new SectorMomentumProfile(
                ticker,
                "TECH_AI_INFRASTRUCTURE",
                -0.025,
                0.18,
                true,
                "HOSTILE_SECTOR",
                "Sector is moving against the trade"
        );
    }
}
