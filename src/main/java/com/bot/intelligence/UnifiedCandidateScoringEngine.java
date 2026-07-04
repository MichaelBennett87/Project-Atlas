package com.bot.intelligence;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Unifies strategy output with world model, ticker personality, replay hints,
 * and lifecycle evidence so multiple agents evaluate the same candidate trade.
 */
public final class UnifiedCandidateScoringEngine {
    private static final UnifiedCandidateScoringEngine INSTANCE = new UnifiedCandidateScoringEngine();

    private final boolean enabled = envBoolean("UNIFIED_CANDIDATE_SCORING_ENABLED", true);
    private final boolean allowOverride = envBoolean("UNIFIED_CANDIDATE_SCORING_ALLOW_BEST_OVERRIDE", true);
    private final double overrideEdge = envDouble("UNIFIED_CANDIDATE_SCORING_OVERRIDE_EDGE", 0.015);
    private final Path journalPath = Paths.get(System.getenv().getOrDefault(
            "UNIFIED_CANDIDATE_SCORE_JOURNAL", "logs/unified_candidate_scores.csv"));

    private UnifiedCandidateScoringEngine() {
        if (enabled) {
            System.out.println("UNIFIED CANDIDATE SCORING ENGINE READY: journal=" + journalPath +
                    " allowOverride=" + allowOverride + " overrideEdge=" + overrideEdge);
        }
    }

    public static UnifiedCandidateScoringEngine getInstance() {
        return INSTANCE;
    }

    public StrategySignal chooseBestCandidate(
            StrategyContext context,
            List<StrategySignal> signals,
            StrategySignal fallbackBest,
            Predicate<StrategySignal> eligibility,
            ToDoubleFunction<StrategySignal> basePriorityFunction
    ) {
        if (!enabled || signals == null || signals.isEmpty()) {
            return fallbackBest;
        }

        List<CandidateConsensusScore> scores = scoreCandidates(context, signals, eligibility, basePriorityFunction);
        journal(context, scores, fallbackBest);
        if (scores.isEmpty()) {
            return fallbackBest;
        }

        CandidateConsensusScore best = scores.stream()
                .max(Comparator.comparingDouble(CandidateConsensusScore::getUnifiedScore))
                .orElse(null);
        if (best == null || best.getSignal() == null) {
            return fallbackBest;
        }

        if (fallbackBest == null) {
            System.out.println("UNIFIED CANDIDATE SELECTED: " + best.compactSummary());
            return best.getSignal();
        }

        CandidateConsensusScore fallbackScore = scores.stream()
                .filter(score -> score.getSignal() == fallbackBest)
                .findFirst()
                .orElse(null);
        double fallbackUnified = fallbackScore == null ? 0.0 : fallbackScore.getUnifiedScore();
        if (allowOverride && best.getSignal() != fallbackBest && best.getUnifiedScore() >= fallbackUnified + overrideEdge) {
            System.out.println("UNIFIED CANDIDATE OVERRIDE: old=" + fallbackBest.getTicker() + ":" + fallbackBest.getStrategyName() +
                    " new=" + best.compactSummary());
            return best.getSignal();
        }
        return fallbackBest;
    }

    public List<CandidateConsensusScore> scoreCandidates(
            StrategyContext context,
            List<StrategySignal> signals,
            Predicate<StrategySignal> eligibility,
            ToDoubleFunction<StrategySignal> basePriorityFunction
    ) {
        List<CandidateConsensusScore> scores = new ArrayList<>();
        if (signals == null) return scores;
        for (StrategySignal signal : signals) {
            if (signal == null || !signal.isActionableBuy()) continue;
            if (eligibility != null && !eligibility.test(signal)) continue;
            scores.add(score(context, signal, basePriorityFunction));
        }
        return scores;
    }

    public CandidateConsensusScore score(
            StrategyContext context,
            StrategySignal signal,
            ToDoubleFunction<StrategySignal> basePriorityFunction
    ) {
        if (signal == null) {
            return new CandidateConsensusScore(null, 0, 0, 0, 0, 0, 0, "null_signal");
        }

        double basePriority = basePriorityFunction == null ? signal.priorityScore() : basePriorityFunction.applyAsDouble(signal);
        CandidateEvidenceGraph evidenceGraph = CandidateEvidenceGraph.build(context, signal);
        double world = evidenceGraph.bucketScore("WORLD_MODEL");
        double memory = evidenceGraph.bucketScore("OPPORTUNITY_MEMORY");
        double replay = evidenceGraph.bucketScore("REPLAY");
        double lifecycle = evidenceGraph.bucketScore("LIFECYCLE");
        double technical = evidenceGraph.bucketScore("TECHNICAL");
        double risk = evidenceGraph.bucketScore("RISK");
        double execution = evidenceGraph.bucketScore("EXECUTION");
        double evidence = evidenceGraph.weightedScore();

        double multiplier = 0.70
                + (evidence * 0.14)
                + (world * 0.08)
                + (memory * 0.08)
                + (replay * 0.06)
                + (lifecycle * 0.06)
                + (technical * 0.07)
                + (risk * 0.05)
                + (execution * 0.05);
        double unified = Math.max(0.0, basePriority) * multiplier;
        String reason = "evidenceGraph=" + fmt(evidence) + " mult=" + fmt(multiplier) +
                " technical=" + fmt(technical) + " risk=" + fmt(risk) + " execution=" + fmt(execution);
        return new CandidateConsensusScore(signal, basePriority, world, memory, replay, lifecycle, unified, reason);
    }

    private double worldScore(StrategySignal signal) {
        try {
            WorldModelSnapshot world = WorldModelAgent.getInstance().currentSnapshot();
            if (world == null) return 0.45;
            double score = 0.35 + world.getDataConfidenceScore() * 0.25 + world.getLiquidityScore() * 0.15 + world.getCatalystHeatScore() * 0.10;
            String strategy = signal == null ? "" : signal.getStrategyName();
            if (strategy != null && strategy.toUpperCase(Locale.ROOT).contains("PARABOLIC")) {
                score += world.getParabolicHeatScore() * 0.20;
            } else {
                score += world.getTrendScore() * 0.15;
            }
            return clamp(score);
        } catch (Exception e) {
            return 0.45;
        }
    }

    private double memoryScore(StrategySignal signal) {
        try {
            OpportunityMemoryProfile profile = OpportunityMemoryService.getInstance().profile(signal.getTicker());
            double score = profile.opportunityScore();
            String best = profile.getBestStrategy() == null ? "" : profile.getBestStrategy().toUpperCase(Locale.ROOT);
            String strategy = signal.getStrategyName() == null ? "" : signal.getStrategyName().toUpperCase(Locale.ROOT);
            if (!best.isBlank() && strategy.contains(best)) {
                score = Math.min(1.0, score + 0.15);
            }
            return clamp(score <= 0.0 ? 0.45 : score);
        } catch (Exception e) {
            return 0.45;
        }
    }

    private double technicalContextScore(StrategyContext context) {
        try {
            List<Bar> bars = context == null ? null : context.getBars();
            if (bars == null || bars.size() < 4) return 0.45;
            Bar last = bars.get(bars.size() - 1);
            Bar prev = bars.get(bars.size() - 2);
            double closeMomentum = prev.close <= 0 ? 0.0 : (last.close - prev.close) / prev.close;
            long avgVolume = 0L;
            int count = 0;
            for (int i = Math.max(0, bars.size() - 12); i < bars.size(); i++) {
                avgVolume += Math.max(0L, bars.get(i).volume);
                count++;
            }
            double rv = count <= 0 || avgVolume <= 0 ? 1.0 : (last.volume * 1.0) / (avgVolume * 1.0 / count);
            double momentumScore = closeMomentum > 0 ? Math.min(0.25, closeMomentum * 8.0) : Math.max(-0.20, closeMomentum * 6.0);
            double volumeScore = Math.max(-0.10, Math.min(0.25, (rv - 1.0) * 0.12));
            return clamp(0.50 + momentumScore + volumeScore);
        } catch (Exception e) {
            return 0.45;
        }
    }

    private synchronized void journal(StrategyContext context, List<CandidateConsensusScore> scores, StrategySignal fallbackBest) {
        if (scores == null || scores.isEmpty()) return;
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (CandidateConsensusScore score : scores) {
                    StrategySignal signal = score.getSignal();
                    writer.write(String.join(",",
                            clean(Instant.now().toString()),
                            clean(context == null ? "" : context.getTicker()),
                            clean(signal == null ? "" : signal.getTicker()),
                            clean(signal == null ? "" : signal.getStrategyName()),
                            clean(fallbackBest == signal ? "BASELINE_BEST" : "CANDIDATE"),
                            fmt(score.getBasePriority()),
                            fmt(score.getWorldScore()),
                            fmt(score.getOpportunityScore()),
                            fmt(score.getReplayScore()),
                            fmt(score.getLifecycleScore()),
                            fmt(score.getUnifiedScore()),
                            clean(score.getReason())
                    ));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            if (envBoolean("UNIFIED_CANDIDATE_VERBOSE_ERRORS", false)) {
                System.out.println("UNIFIED CANDIDATE JOURNAL FAILED: " + e.getMessage());
            }
        }
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

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0.0;
        return String.format(Locale.ROOT, "%.5f", value);
    }
}
