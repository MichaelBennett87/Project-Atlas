package com.bot.intelligence;

import com.bot.master.MasterStrategyDecision;
import com.bot.master.StrategyAction;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;

/**
 * Controlled-experiment layer for autonomous policy learning.
 *
 * This does not place trades and does not override the risk kernel. It records
 * which qualifying decisions would have been routed to a candidate policy and
 * maintains promotion-safe counters. The overnight optimizer/promotion gate can
 * later use these records to promote only when the candidate policy has enough
 * evidence instead of drifting from small noisy samples.
 */
public final class ContinuousExperimentManager {
    private static final ContinuousExperimentManager INSTANCE = new ContinuousExperimentManager();

    private final boolean enabled = envBoolean("CONTINUOUS_EXPERIMENT_MANAGER_ENABLED", true);
    private final double routePercent = Math.max(0.0, Math.min(100.0, envDouble("CONTINUOUS_EXPERIMENT_ROUTE_PERCENT", 5.0)));
    private final double minEvidenceScore = envDouble("CONTINUOUS_EXPERIMENT_MIN_EVIDENCE_SCORE", 0.58);
    private final Path journalPath = Paths.get(System.getenv().getOrDefault("CONTINUOUS_EXPERIMENT_JOURNAL", "logs/continuous_experiments.csv"));
    private final Path statePath = Paths.get(System.getenv().getOrDefault("CONTINUOUS_EXPERIMENT_STATE", "logs/continuous_experiment_state.properties"));
    private int observed;
    private int routed;
    private int production;

    private ContinuousExperimentManager() {
        if (enabled) {
            System.out.println("CONTINUOUS EXPERIMENT MANAGER READY: routePercent=" + routePercent +
                    " minEvidence=" + minEvidenceScore + " journal=" + journalPath + " state=" + statePath);
        }
    }

    public static ContinuousExperimentManager getInstance() { return INSTANCE; }

    public synchronized ExperimentAssignment assign(
            StrategyContext context,
            MasterStrategyDecision decision,
            CandidateEvidenceGraph evidenceGraph,
            String actionLabel
    ) {
        if (!enabled || context == null || decision == null) {
            return ExperimentAssignment.production("disabled_or_missing_context");
        }
        observed++;
        double evidence = evidenceGraph == null ? 0.45 : evidenceGraph.weightedScore();
        boolean eligible = decision.getAction() == StrategyAction.BUY && evidence >= minEvidenceScore;
        boolean route = eligible && deterministicBucket(context, decision) < routePercent;
        if (route) routed++; else production++;
        ExperimentAssignment assignment = route
                ? ExperimentAssignment.candidate("candidate_policy_shadow", evidence, "eligible shadow route")
                : ExperimentAssignment.production(eligible ? "production bucket" : "not eligible for candidate experiment");
        journal(context, decision, evidenceGraph, actionLabel, assignment);
        writeState();
        return assignment;
    }

    public boolean shouldPromoteCandidate(double candidateProfitFactor, double currentProfitFactor, double maxDrawdown, int samples) {
        return PolicyVersionManager.getInstance().shouldPromoteCandidate(candidateProfitFactor, currentProfitFactor, maxDrawdown, samples);
    }

    private double deterministicBucket(StrategyContext context, MasterStrategyDecision decision) {
        String key = (context.getTicker() == null ? "" : context.getTicker()) + "|" +
                (decision.getWinningSignal() == null ? "" : decision.getWinningSignal().getStrategyName()) + "|" +
                (System.currentTimeMillis() / 60_000L);
        int hash = Math.abs(key.hashCode());
        return hash % 100;
    }

    private void journal(
            StrategyContext context,
            MasterStrategyDecision decision,
            CandidateEvidenceGraph graph,
            String actionLabel,
            ExperimentAssignment assignment
    ) {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            StrategySignal winner = decision.getWinningSignal();
            try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(String.join(",",
                        clean(Instant.now().toString()),
                        clean(PolicyVersionManager.getInstance().currentVersion()),
                        clean(assignment.group),
                        clean(actionLabel),
                        clean(context.getTicker()),
                        clean(decision.getAction().name()),
                        clean(winner == null ? "" : winner.getStrategyName()),
                        winner == null ? "" : fmt(winner.getConfidence()),
                        winner == null ? "" : fmt(winner.priorityScore()),
                        graph == null ? "" : fmt(graph.weightedScore()),
                        graph == null ? "" : fmt(graph.bucketScore("NEWS")),
                        graph == null ? "" : fmt(graph.bucketScore("TECHNICAL")),
                        graph == null ? "" : fmt(graph.bucketScore("WORLD_MODEL")),
                        graph == null ? "" : fmt(graph.bucketScore("OPPORTUNITY_MEMORY")),
                        clean(assignment.reason),
                        clean(decision.getReason())
                ));
                writer.newLine();
            }
        } catch (IOException e) {
            if (envBoolean("CONTINUOUS_EXPERIMENT_VERBOSE_ERRORS", false)) {
                System.out.println("CONTINUOUS EXPERIMENT JOURNAL FAILED: " + e.getMessage());
            }
        }
    }

    private void writeState() {
        try {
            Path parent = statePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Properties p = new Properties();
            p.setProperty("updatedAt", Instant.now().toString());
            p.setProperty("observed", Integer.toString(observed));
            p.setProperty("candidateShadowRouted", Integer.toString(routed));
            p.setProperty("productionRouted", Integer.toString(production));
            p.setProperty("routePercent", fmt(routePercent));
            p.setProperty("minEvidenceScore", fmt(minEvidenceScore));
            p.setProperty("promotionRule", "delegate_to_PolicyVersionManager_shouldPromoteCandidate");
            try (java.io.OutputStream out = Files.newOutputStream(statePath)) {
                p.store(out, "Continuous experiment state");
            }
        } catch (Exception e) {
            if (envBoolean("CONTINUOUS_EXPERIMENT_VERBOSE_ERRORS", false)) {
                System.out.println("CONTINUOUS EXPERIMENT STATE WRITE FAILED: " + e.getMessage());
            }
        }
    }

    public static final class ExperimentAssignment {
        private final String group;
        private final double evidenceScore;
        private final String reason;

        private ExperimentAssignment(String group, double evidenceScore, String reason) {
            this.group = group;
            this.evidenceScore = evidenceScore;
            this.reason = reason == null ? "" : reason;
        }

        public static ExperimentAssignment candidate(String group, double evidenceScore, String reason) {
            return new ExperimentAssignment(group, evidenceScore, reason);
        }

        public static ExperimentAssignment production(String reason) {
            return new ExperimentAssignment("production_policy", 0.0, reason);
        }

        public String getGroup() { return group; }
        public double getEvidenceScore() { return evidenceScore; }
        public String getReason() { return reason; }
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0.0;
        return String.format(Locale.ROOT, "%.5f", value);
    }
}
