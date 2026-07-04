package com.bot.intelligence;

import com.bot.governance.PromotionGatekeeper;
import com.bot.intelligence.historical.HistoricalResearchOrchestrator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The top-level no-human-approval autonomous nightly controller.
 *
 * This class is intentionally offline-only. It refuses to run while the live
 * trading lock exists, then performs the complete improvement cycle:
 *
 * - Long-memory historical replay from all accumulated journals.
 * - Current regime detection.
 * - Model-weight training.
 * - Strategy recipe discovery.
 * - Portfolio/species allocation.
 * - Meta-learning of exploration and mutation pressure.
 * - Large candidate tournament with Monte Carlo checks.
 * - Bounded generated policy-source tournament.
 * - Automatic champion promotion into policy files consumed by UnifiedStrategyMain.
 * - Persistent generation/champion memory so the system compounds knowledge over time.
 *
 * It does not place orders. It does not mutate the live-running process. It is
 * designed to be run after UnifiedStrategyMain has fully stopped.
 */
public class FullyAutonomousSelfImprovementOrchestrator {

    private final Path statePath = Path.of(System.getenv().getOrDefault(
            "AI_AUTONOMOUS_STATE_PATH", "logs/autonomous_evolution/state.properties"));
    private final Path masterReportPath = Path.of(System.getenv().getOrDefault(
            "AI_SELF_IMPROVEMENT_MASTER_REPORT_PATH", "logs/autonomous_self_improvement_report.txt"));
    private final Path liveLockPath = Path.of(System.getenv().getOrDefault(
            "LIVE_TRADING_LOCK_PATH", "logs/live_trading.lock"));

    public RunResult runNightlyEvolution() {
        AutonomousTournamentEngine.OfflineGuard.assertSafeToMutate();

        long startedMs = System.currentTimeMillis();
        EvolutionState state = EvolutionState.load(statePath);
        int nextGeneration = state.generation + 1;
        List<String> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        steps.add("offlineGuard=PASS liveLock=" + liveLockPath);

        CodebaseReviewAgent.ReviewResult codeReview = new CodebaseReviewAgent().runReview();
        steps.add(codeReview.summary());

        InternetResearchAgent.ResearchResult internetResearch = new InternetResearchAgent().runNightlyResearch();
        steps.add(internetResearch.summary());
        if (internetResearch.enabled && !internetResearch.success) {
            warnings.add("INTERNET_RESEARCH_FAILED: " + internetResearch.message);
        }

        HistoricalResearchOrchestrator.Result historicalResearch = new HistoricalResearchOrchestrator().runNightlyResearch();
        steps.add("historicalResearch=" + historicalResearch.summary());
        if (historicalResearch.tickers == 0 || historicalResearch.rows == 0) {
            warnings.add("HISTORICAL_RESEARCH_LOW_DATA: no historical REST rows were downloaded; check POLYGON_API_KEY, ALPHA_VANTAGE_API_KEY, rate limits, or NIGHTLY_HISTORICAL_TICKERS.");
        }

        PromotionGatekeeper.GateResult gateResult = new PromotionGatekeeper()
                .inspectCandidateTree(Path.of(System.getenv().getOrDefault("AI_CANDIDATE_PATCH_ROOT", "logs/autonomous_candidates")));
        steps.add(gateResult.summary());
        if (!gateResult.passed) {
            warnings.add("PROMOTION_GATEKEEPER_FAILED: candidate tree contains forbidden changes; review logs/autonomous_governance_report.txt");
        }

        DailySelfImprovementScorecard.Result scorecard = new DailySelfImprovementScorecard().run();
        steps.add("dailyScorecard=" + scorecard.summary());

        new NightlyValidationEngine().run();
        steps.add("lifecycleValidation=COMPLETE report=" + System.getenv().getOrDefault("NIGHTLY_VALIDATION_REPORT_PATH", "logs/nightly_validation_report.csv"));

        AutonomousLearningEngine.LearningResult nightlyLearning = new AutonomousLearningEngine().run();
        steps.add("nightlyLearning=" + nightlyLearning.summary());

        if (!scorecard.promotionDataReady()) {
            warnings.add("PROMOTION_DATA_NOT_READY: scorecard says feature/outcome sample is still small; evolution will still generate artifacts but guarded promotion may not occur.");
        }
        if (!scorecard.shortArchitectureHasTrainingData()) {
            warnings.add("SHORT_ARCHITECTURE_LOW_DATA: no fresh negative-news rows or closed short outcomes yet; short policy learning will improve after more negative catalyst trades are observed.");
        }

        HistoricalReplayEngine.ReplayResult replay = new HistoricalReplayEngine().runReplay();
        steps.add("historicalReplay=" + replay.summary());
        if (replay.rows < intEnv("AI_MIN_FEATURE_ROWS_WARNING", 250)) {
            warnings.add("LOW_FEATURE_DATA: featureRows=" + replay.rows + "; let the live bot collect more data before trusting promotion quality.");
        }

        RegimeDetectionEngine.RegimeResult regime = new RegimeDetectionEngine().detect();
        steps.add("regimeDetection=" + regime.summary());

        ModelTrainingEngine.ModelTrainingResult model = new ModelTrainingEngine().train();
        steps.add("modelTraining=" + model.summary());

        StrategyDiscoveryEngine.StrategyDiscoveryResult discovery = new StrategyDiscoveryEngine().discover();
        steps.add("strategyDiscovery=" + discovery.summary());

        StrategyResearchSimulationPromotionEngine.Result researchSimulation =
                new StrategyResearchSimulationPromotionEngine().run();
        steps.add("strategyResearchSimulationPromotion=" + researchSimulation.summary());

        PolygonBarByBarSimulationEngine.Result barSimulation =
                new PolygonBarByBarSimulationEngine().run();
        steps.add("barByBarSimulation=" + barSimulation.summary());

        ExecutionCostLearningEngine.Result executionCost =
                new ExecutionCostLearningEngine().run();
        steps.add("executionCostLearning=" + executionCost.summary());

        PreTradeCalibrationLearningEngine.Result preTradeCalibration =
                new PreTradeCalibrationLearningEngine().run();
        steps.add("preTradeCalibration=" + preTradeCalibration.summary());

        PreTradeCalibrationAuditEngine.Result preTradeCalibrationAudit =
                new PreTradeCalibrationAuditEngine().run();
        steps.add("preTradeCalibrationAudit=" + preTradeCalibrationAudit.summary());

        ExitShadowTournamentEngine.Result exitShadowTournament =
                new ExitShadowTournamentEngine().run();
        steps.add("exitShadowTournament=" + exitShadowTournament.summary());

        PaperTradingPerformanceGate.Result paperGate =
                new PaperTradingPerformanceGate().run();
        steps.add("paperTradingPerformanceGate=" + paperGate.summary());

        CandidateWatchlistRetestQueue.Result candidateRetestQueue =
                new CandidateWatchlistRetestQueue().run();
        steps.add("candidateWatchlistRetestQueue=" + candidateRetestQueue.summary());
        if (candidateRetestQueue.promotionReady > 0) {
            warnings.add("CANDIDATE_PROMOTION_READY: " + candidateRetestQueue.promotionReady +
                    " watchlist strategies cleared retest, paper, and shadow confirmation; review " +
                    candidateRetestQueue.reportPath);
        }

        RegimeTaggedStrategyPerformanceEngine.Result regimeStrategy =
                new RegimeTaggedStrategyPerformanceEngine().run();
        steps.add("regimeTaggedStrategyPerformance=" + regimeStrategy.summary());

        LiveTradeReadinessGate.Result liveReadiness =
                LiveTradeReadinessGate.getInstance().writeHealthSnapshot();
        steps.add("liveTradeReadinessGate=" + liveReadiness.summary());

        PortfolioOptimizerEngine.PortfolioResult portfolio = new PortfolioOptimizerEngine().optimize();
        steps.add("portfolioOptimizer=" + portfolio.summary());

        MetaLearningEngine.MetaLearningResult meta = new MetaLearningEngine().learn();
        steps.add("metaLearning=" + meta.summary());

        AutonomousEvolutionSuite.EvolutionResult suite = new AutonomousEvolutionSuite().runFullEvolution();
        steps.add("evolutionSuite=" + suite.toConsoleSummary());

        long finishedMs = System.currentTimeMillis();

        boolean promoted = suite.promoted;
        String champion = promoted ? suite.championName : state.champion;
        String championSpecies = suite.species == null || suite.species.isBlank() ? state.championSpecies : suite.species;
        String regimeName = suite.regime == null || suite.regime.isBlank() ? regime.regime : suite.regime;
        double fitness = promoted ? suite.fitness : state.championFitness;

        state.generation = nextGeneration;
        state.lastRunAt = Instant.ofEpochMilli(finishedMs).toString();
        state.champion = champion == null ? "UNKNOWN" : champion;
        state.championSpecies = championSpecies == null ? "UNKNOWN" : championSpecies;
        state.regime = regimeName == null ? "UNKNOWN" : regimeName;
        state.championFitness = fitness;
        state.lastPromoted = promoted;
        state.totalPromotions += promoted ? 1 : 0;
        state.save(statePath);

        writeMasterReport(nextGeneration, startedMs, finishedMs, promoted, champion, championSpecies, regimeName, fitness, steps, warnings);

        return new RunResult(nextGeneration, promoted, champion, championSpecies, regimeName, fitness, finishedMs - startedMs, warnings.size());
    }

    private void writeMasterReport(int generation, long startedMs, long finishedMs, boolean promoted,
                                   String champion, String species, String regime, double fitness,
                                   List<String> steps, List<String> warnings) {
        StringBuilder b = new StringBuilder();
        b.append("FULLY AUTONOMOUS SELF-IMPROVEMENT REPORT\n");
        b.append("mode=OFFLINE_NO_HUMAN_APPROVAL\n");
        b.append("generation=").append(generation).append('\n');
        b.append("startedAt=").append(Instant.ofEpochMilli(startedMs)).append('\n');
        b.append("finishedAt=").append(Instant.ofEpochMilli(finishedMs)).append('\n');
        b.append("elapsedMs=").append(finishedMs - startedMs).append('\n');
        b.append("promoted=").append(promoted).append('\n');
        b.append("champion=").append(champion).append('\n');
        b.append("championSpecies=").append(species).append('\n');
        b.append("regime=").append(regime).append('\n');
        b.append("fitness=").append(fitness).append('\n');
        b.append("liveMutation=false\n");
        b.append("liveLockPath=").append(liveLockPath).append('\n');
        b.append("statePath=").append(statePath).append('\n');
        b.append('\n').append("STEPS\n");
        for (String step : steps) {
            b.append("- ").append(step).append('\n');
        }
        b.append('\n').append("WARNINGS\n");
        if (warnings.isEmpty()) {
            b.append("- none\n");
        } else {
            for (String warning : warnings) {
                b.append("- ").append(warning).append('\n');
            }
        }
        b.append('\n').append("NEXT DAY LIVE BOT INPUTS\n");
        b.append("- logs/ai_policy.properties\n");
        b.append("- logs/portfolio_policy.properties\n");
        b.append("- logs/model_weights.properties\n");
        b.append("- logs/regime_policy.properties\n");
        b.append("- logs/discovered_strategies.properties\n");
        b.append("- logs/simulation_strategy_policy.properties\n");
        b.append("- logs/bar_by_bar_simulation_policy.properties\n");
        b.append("- logs/bar_by_bar_simulation_report.txt\n");
        b.append("- logs/bar_by_bar_simulation_trades.csv\n");
        b.append("- logs/bar_by_bar_candidate_watchlist.csv\n");
        b.append("- logs/candidate_retest_queue.csv\n");
        b.append("- logs/candidate_retest_queue_report.txt\n");
        b.append("- logs/candidate_retest_queue_policy.properties\n");
        b.append("- logs/candidate_retest_queue_health.properties\n");
        b.append("- logs/bar_by_bar_candidate_retest_policy.properties\n");
        b.append("- logs/bar_by_bar_candidate_retest_report.txt\n");
        b.append("- logs/bar_by_bar_candidate_retest_trades.csv\n");
        b.append("- logs/bar_by_bar_candidate_retest_watchlist.csv\n");
        b.append("- logs/watchlist_shadow_sampler_health.properties\n");
        b.append("- logs/watchlist_shadow_sampler_events.csv\n");
        b.append("- logs/execution_cost_policy.properties\n");
        b.append("- logs/execution_cost_report.txt\n");
        b.append("- logs/execution_cost_matrix.csv\n");
        b.append("- logs/execution_cost_health.properties\n");
        b.append("- logs/pre_trade_calibration_policy.properties\n");
        b.append("- logs/pre_trade_calibration_report.txt\n");
        b.append("- logs/pre_trade_calibration_matrix.csv\n");
        b.append("- logs/pre_trade_calibration_health.properties\n");
        b.append("- logs/pre_trade_calibration_audit.csv\n");
        b.append("- logs/pre_trade_calibration_audit_report.txt\n");
        b.append("- logs/pre_trade_calibration_audit_summary.csv\n");
        b.append("- logs/pre_trade_calibration_audit_health.properties\n");
        b.append("- logs/exit_shadow_tournament_policy.properties\n");
        b.append("- logs/exit_shadow_tournament_report.txt\n");
        b.append("- logs/exit_shadow_tournament_matrix.csv\n");
        b.append("- logs/exit_shadow_tournament_health.properties\n");
        b.append("- logs/paper_trading_strategy_policy.properties\n");
        b.append("- logs/paper_trading_performance_gate_report.txt\n");
        b.append("- logs/paper_trading_performance_gate_health.properties\n");
        b.append("- logs/regime_strategy_policy.properties\n");
        b.append("- logs/regime_strategy_report.txt\n");
        b.append("- logs/regime_strategy_matrix.csv\n");
        b.append("- logs/regime_strategy_health.properties\n");
        b.append("- logs/live_trade_readiness_health.properties\n");
        b.append("- logs/live_trade_readiness_report.txt\n");
        b.append("- logs/live_trade_readiness_journal.csv\n");
        b.append("- logs/shadow_trade_decisions.csv\n");
        b.append("- logs/shadow_trade_outcomes.csv\n");
        b.append("- logs/strategy_research_candidates.csv\n");
        b.append("- logs/strategy_research_simulation_report.txt\n");
        b.append("- logs/ai_daily_scorecard.properties\n");
        b.append("- logs/ai_daily_scorecard.txt\n");
        b.append("- logs/nightly_learning.properties\n");
        b.append("- logs/nightly_learning_report.txt\n");
        b.append("- logs/trade_lifecycle_optimization.csv\n");
        b.append("- logs/trade_lifecycle_recommendations.properties\n");
        b.append("- logs/nightly_validation_report.csv\n");
        b.append("- logs/nightly_historical_research_report.csv\n");
        b.append("- logs/historical_data_requests.csv\n");
        b.append("- logs/nightly_training_dataset.csv\n");
        b.append("- logs/nightly_ticker_personality.csv\n");
        b.append("- logs/internet_research_report.json\n");
        b.append("- logs/codebase_review_report.txt\n");
        b.append("- logs/autonomous_governance_report.txt\n");
        AutonomousEvolutionSuite.FilesUtil.writeString(masterReportPath, b.toString());
    }

    private static int intEnv(String key, int fallback) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public static final class RunResult {
        public final int generation;
        public final boolean promoted;
        public final String champion;
        public final String championSpecies;
        public final String regime;
        public final double fitness;
        public final long elapsedMs;
        public final int warnings;

        RunResult(int generation, boolean promoted, String champion, String championSpecies,
                  String regime, double fitness, long elapsedMs, int warnings) {
            this.generation = generation;
            this.promoted = promoted;
            this.champion = champion;
            this.championSpecies = championSpecies;
            this.regime = regime;
            this.fitness = fitness;
            this.elapsedMs = elapsedMs;
            this.warnings = warnings;
        }

        public String toConsoleSummary() {
            return "FULLY AUTONOMOUS SELF-IMPROVEMENT COMPLETE: generation=" + generation +
                    " promoted=" + promoted +
                    " champion=" + champion +
                    " species=" + championSpecies +
                    " regime=" + regime +
                    " fitness=" + fitness +
                    " elapsedMs=" + elapsedMs +
                    " warnings=" + warnings;
        }
    }

    static final class EvolutionState {
        int generation = 0;
        String lastRunAt = "";
        String champion = "UNKNOWN";
        String championSpecies = "UNKNOWN";
        String regime = "UNKNOWN";
        double championFitness = 0.0;
        boolean lastPromoted = false;
        int totalPromotions = 0;

        static EvolutionState load(Path path) {
            EvolutionState s = new EvolutionState();
            if (!Files.exists(path)) {
                return s;
            }
            try (InputStream in = Files.newInputStream(path)) {
                Properties p = new Properties();
                p.load(in);
                s.generation = parseInt(p.getProperty("generation"), 0);
                s.lastRunAt = p.getProperty("lastRunAt", "");
                s.champion = p.getProperty("champion", "UNKNOWN");
                s.championSpecies = p.getProperty("championSpecies", "UNKNOWN");
                s.regime = p.getProperty("regime", "UNKNOWN");
                s.championFitness = parseDouble(p.getProperty("championFitness"), 0.0);
                s.lastPromoted = Boolean.parseBoolean(p.getProperty("lastPromoted", "false"));
                s.totalPromotions = parseInt(p.getProperty("totalPromotions"), 0);
            } catch (Exception e) {
                System.out.println("EVOLUTION STATE LOAD FAILED: " + e.getMessage());
            }
            return s;
        }

        void save(Path path) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Properties p = new Properties();
                p.setProperty("generation", Integer.toString(generation));
                p.setProperty("lastRunAt", lastRunAt == null ? "" : lastRunAt);
                p.setProperty("champion", champion == null ? "UNKNOWN" : champion);
                p.setProperty("championSpecies", championSpecies == null ? "UNKNOWN" : championSpecies);
                p.setProperty("regime", regime == null ? "UNKNOWN" : regime);
                p.setProperty("championFitness", Double.toString(championFitness));
                p.setProperty("lastPromoted", Boolean.toString(lastPromoted));
                p.setProperty("totalPromotions", Integer.toString(totalPromotions));
                try (OutputStream out = Files.newOutputStream(path)) {
                    p.store(out, "Persistent autonomous evolution memory.");
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to save evolution state " + path + ": " + e.getMessage(), e);
            }
        }

        private static int parseInt(String v, int fallback) {
            try { return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); }
            catch (Exception e) { return fallback; }
        }
        private static double parseDouble(String v, double fallback) {
            try { return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); }
            catch (Exception e) { return fallback; }
        }
    }
}
