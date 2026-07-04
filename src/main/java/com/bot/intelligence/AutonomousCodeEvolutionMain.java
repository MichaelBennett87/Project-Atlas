package com.bot.intelligence;

import com.bot.intelligence.historical.HistoricalResearchOrchestrator;

/**
 * Offline fully autonomous evolution entry point.
 *
 * Run this only after UnifiedStrategyMain has stopped. It performs the complete
 * no-human-approval nightly cycle:
 *
 * 1. Historical replay from accumulated logs.
 * 2. Regime detection.
 * 3. Model weight training.
 * 4. Strategy recipe discovery.
 * 5. Portfolio allocation optimization.
 * 6. Meta-learning of mutation/exploration rates.
 * 7. 500+ candidate policy tournament with Monte Carlo robustness checks.
 * 8. Optional bounded generated-source policy tournament.
 * 9. Automatic champion promotion into policy/config files for tomorrow.
 *
 * It refuses to mutate while live trading is active or a live lock exists.
 */
public class AutonomousCodeEvolutionMain {
    public static void main(String[] args) {
        System.out.println("NIGHTLY VALIDATION: pre-evolution evidence scan starting.");
        new NightlyValidationEngine().run();

        System.out.println("NIGHTLY HISTORICAL RESEARCH: analyst REST requests and local cache build starting.");
        HistoricalResearchOrchestrator.Result historicalResearch = new HistoricalResearchOrchestrator().runNightlyResearch();
        System.out.println("NIGHTLY HISTORICAL RESEARCH COMPLETE: " + historicalResearch.summary());

        System.out.println("NIGHTLY POLYGON REPLAY LAB: premium historical market reconstruction starting.");
        new NightlyPolygonReplayLab().run();

        System.out.println("NIGHTLY OPENAI ENTRY/EXIT POLICY REVIEW: dynamic governor improvement research starting.");
        OpenAiNightlyEntryExitPolicyReview.ReviewResult entryExitReview = new OpenAiNightlyEntryExitPolicyReview().run();
        System.out.println("NIGHTLY OPENAI ENTRY/EXIT POLICY REVIEW COMPLETE: " + entryExitReview.summary());

        System.out.println("NIGHTLY AUTONOMOUS ENTRY/EXIT POLICY PROMOTION: validation-gated policy generation starting.");
        AutonomousEntryExitPolicyPromotionPipeline.Result entryExitPromotion = new AutonomousEntryExitPolicyPromotionPipeline().run();
        System.out.println("NIGHTLY AUTONOMOUS ENTRY/EXIT POLICY PROMOTION COMPLETE: " + entryExitPromotion.summary());

        System.out.println("NIGHTLY MARKET REPRESENTATION: technical/multitimeframe/synthetic/knowledge graph build starting.");
        new NightlyMarketRepresentationUpgradeOrchestrator().run();

        FullyAutonomousSelfImprovementOrchestrator.RunResult result =
                new FullyAutonomousSelfImprovementOrchestrator().runNightlyEvolution();
        System.out.println(result.toConsoleSummary());

        System.out.println("NIGHTLY VALIDATION: post-evolution promotion evidence scan starting.");
        new NightlyValidationEngine().run();
    }
}
