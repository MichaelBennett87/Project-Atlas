package com.bot.intelligence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One-command nightly autonomous improvement pipeline.
 *
 * This class is intentionally offline-only. It performs the complete autonomous
 * cycle after UnifiedStrategyMain has stopped: historical replay, model policy
 * training, regime detection, strategy discovery, portfolio allocation, policy
 * tournament, source-policy tournament, and promotion of the best policy files.
 */
public class AutonomousEvolutionPipeline {

    public PipelineResult run() {
        AutonomousTournamentEngine.OfflineGuard.assertSafeToMutate();

        List<String> steps = new ArrayList<>();
        long started = System.currentTimeMillis();

        HistoricalReplayEngine.ReplayResult replay = new HistoricalReplayEngine().runReplay();
        steps.add("historicalReplay=" + replay.summary());

        RegimeDetectionEngine.RegimeResult regime = new RegimeDetectionEngine().detect();
        steps.add("regimeDetection=" + regime.summary());

        ModelTrainingEngine.ModelTrainingResult model = new ModelTrainingEngine().train();
        steps.add("modelTraining=" + model.summary());

        StrategyDiscoveryEngine.StrategyDiscoveryResult discovery = new StrategyDiscoveryEngine().discover();
        steps.add("strategyDiscovery=" + discovery.summary());

        PortfolioOptimizerEngine.PortfolioResult portfolio = new PortfolioOptimizerEngine().optimize();
        steps.add("portfolioOptimizer=" + portfolio.summary());

        MetaLearningEngine.MetaLearningResult meta = new MetaLearningEngine().learn();
        steps.add("metaLearning=" + meta.summary());

        AutonomousEvolutionSuite.EvolutionResult evolution = new AutonomousEvolutionSuite().runFullEvolution();
        steps.add("fullEvolution=" + evolution.toConsoleSummary());

        long finished = System.currentTimeMillis();
        String report = "AUTONOMOUS EVOLUTION PIPELINE REPORT\n" +
                "startedAt=" + Instant.ofEpochMilli(started) + "\n" +
                "finishedAt=" + Instant.ofEpochMilli(finished) + "\n" +
                "elapsedMs=" + (finished - started) + "\n" +
                "mode=OFFLINE_FULLY_AUTONOMOUS_NO_HUMAN_APPROVAL\n" +
                "liveMutation=false\n" +
                "steps=\n  - " + String.join("\n  - ", steps) + "\n";
        AutonomousEvolutionSuite.FilesUtil.writeString(Path.of(System.getenv().getOrDefault(
                "AI_AUTONOMOUS_PIPELINE_REPORT_PATH", "logs/autonomous_pipeline_report.txt")), report);

        return new PipelineResult(evolution.promoted, evolution.championName, steps, finished - started);
    }

    public static final class PipelineResult {
        public final boolean promoted;
        public final String champion;
        public final List<String> steps;
        public final long elapsedMs;

        PipelineResult(boolean promoted, String champion, List<String> steps, long elapsedMs) {
            this.promoted = promoted;
            this.champion = champion;
            this.steps = steps;
            this.elapsedMs = elapsedMs;
        }

        public String toConsoleSummary() {
            return "AUTONOMOUS PIPELINE COMPLETE: promoted=" + promoted +
                    " champion=" + champion +
                    " elapsedMs=" + elapsedMs +
                    " steps=" + steps.size();
        }
    }
}
