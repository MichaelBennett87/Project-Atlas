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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shared candidate evidence object used by the agent committee and scoring layer.
 *
 * The goal is to prevent every agent/strategy from independently interpreting raw
 * market data. For each candidate ticker the graph captures normalized evidence
 * buckets: news, technicals, world model, ticker personality, replay/lifecycle,
 * risk, and execution quality. The graph is advisory only; hard risk controls and
 * broker execution remain outside this class.
 */
public final class CandidateEvidenceGraph {
    private static final Path JOURNAL = Paths.get(System.getenv().getOrDefault(
            "CANDIDATE_EVIDENCE_GRAPH_JOURNAL", "logs/candidate_evidence_graph.csv"));

    private final String ticker;
    private final String strategy;
    private final List<EvidenceNode> nodes = new ArrayList<>();
    private final long createdAtMs;

    private CandidateEvidenceGraph(String ticker, String strategy) {
        this.ticker = normalize(ticker);
        this.strategy = normalize(strategy);
        this.createdAtMs = System.currentTimeMillis();
    }

    public static CandidateEvidenceGraph build(StrategyContext context, StrategySignal signal) {
        String ticker = signal == null ? (context == null ? "UNKNOWN" : context.getTicker()) : signal.getTicker();
        String strategy = signal == null ? "UNKNOWN" : signal.getStrategyName();
        CandidateEvidenceGraph graph = new CandidateEvidenceGraph(ticker, strategy);
        graph.addNewsEvidence(context);
        graph.addTechnicalEvidence(context);
        graph.addFusionEvidence(ticker);
        graph.addWorldEvidence(strategy);
        graph.addOpportunityMemoryEvidence(ticker, strategy);
        graph.addReplayAndLifecycleEvidence(ticker, strategy);
        graph.addRiskAndExecutionEvidence(context, signal);
        graph.journal();
        return graph;
    }

    public String getTicker() { return ticker; }
    public String getStrategy() { return strategy; }
    public long getCreatedAtMs() { return createdAtMs; }
    public List<EvidenceNode> getNodes() { return Collections.unmodifiableList(nodes); }

    public double weightedScore() {
        double totalWeight = 0.0;
        double total = 0.0;
        for (EvidenceNode node : nodes) {
            if (node == null) continue;
            double w = Math.max(0.0, node.weight);
            total += clamp(node.score) * w;
            totalWeight += w;
        }
        return totalWeight <= 0.0 ? 0.45 : clamp(total / totalWeight);
    }

    public double bucketScore(String bucket) {
        String normalized = normalize(bucket);
        double totalWeight = 0.0;
        double total = 0.0;
        for (EvidenceNode node : nodes) {
            if (node == null || !normalize(node.bucket).equals(normalized)) continue;
            total += clamp(node.score) * Math.max(0.0, node.weight);
            totalWeight += Math.max(0.0, node.weight);
        }
        return totalWeight <= 0.0 ? 0.45 : clamp(total / totalWeight);
    }

    public String compactSummary() {
        return "ticker=" + ticker + " strategy=" + strategy +
                " evidence=" + fmt(weightedScore()) +
                " news=" + fmt(bucketScore("NEWS")) +
                " technical=" + fmt(bucketScore("TECHNICAL")) +
                " fusion=" + fmt(bucketScore("EVIDENCE_FUSION")) +
                " world=" + fmt(bucketScore("WORLD_MODEL")) +
                " memory=" + fmt(bucketScore("OPPORTUNITY_MEMORY")) +
                " risk=" + fmt(bucketScore("RISK"));
    }

    private void addNewsEvidence(StrategyContext context) {
        boolean hasNews = context != null && context.hasNews();
        double sentiment = 0.45;
        try {
            if (context != null && context.getSentiment() != null) {
                sentiment = clamp(0.50 + context.getSentiment().netSentiment() * 0.35);
            }
        } catch (Exception ignored) {
        }
        add("NEWS", "hasNews", hasNews ? 0.70 : 0.40, 0.60, hasNews ? "latest news attached" : "market-only candidate");
        add("NEWS", "sentiment", sentiment, 0.75, "normalized FinBERT/net sentiment evidence");
        if (hasNews && context.getLatestNews() != null) {
            double sourceWeight = DataSourceReliabilityService.getInstance().weightFor(context.getLatestNews().getSource());
            add("NEWS", "sourceReliability", clamp(0.50 + (sourceWeight - 1.0) * 0.35), 0.55,
                    "provider=" + context.getLatestNews().getSource() + " weight=" + fmt(sourceWeight));
        }
    }

    private void addTechnicalEvidence(StrategyContext context) {
        List<Bar> bars = context == null ? null : context.getBars();
        if (bars == null || bars.size() < 3) {
            add("TECHNICAL", "insufficientBars", 0.40, 0.80, "not enough bars for technical evidence");
            return;
        }
        Bar last = bars.get(bars.size() - 1);
        Bar prev = bars.get(bars.size() - 2);
        double closeMomentum = prev.close <= 0.0 ? 0.0 : (last.close - prev.close) / prev.close;
        double momentumScore = clamp(0.50 + Math.max(-0.25, Math.min(0.30, closeMomentum * 10.0)));
        long total = 0L;
        int count = 0;
        for (int i = Math.max(0, bars.size() - 16); i < bars.size() - 1; i++) {
            total += Math.max(0L, bars.get(i).volume);
            count++;
        }
        double avg = count <= 0 ? Math.max(1L, last.volume) : total * 1.0 / count;
        double volumePulse = avg <= 0.0 ? 1.0 : last.volume / avg;
        double volumeScore = clamp(0.45 + Math.max(-0.15, Math.min(0.35, (volumePulse - 1.0) * 0.12)));
        add("TECHNICAL", "closeMomentum", momentumScore, 0.90, "last-bar momentum=" + fmt(closeMomentum));
        add("TECHNICAL", "volumePulse", volumeScore, 0.85, "relative recent volume=" + fmt(volumePulse));
    }


    private void addFusionEvidence(String ticker) {
        try {
            EvidenceFusionEngine.FusedTickerEvidence fused = EvidenceFusionEngine.getInstance().evidenceFor(ticker);
            double score = fused.fusedScore();
            int providers = fused.providers().size();
            int signals = fused.signalCount();
            add("EVIDENCE_FUSION", "providerCorroboration", score, 0.90,
                    "providers=" + providers + " signals=" + signals + " ticker=" + ticker);
            add("EVIDENCE_FUSION", "marketData", fused.typeScore(com.bot.intelligence.bus.MarketIntelligenceSignalType.MARKET_DATA), 0.55,
                    "fused market data evidence");
            add("EVIDENCE_FUSION", "catalyst", Math.max(fused.typeScore(com.bot.intelligence.bus.MarketIntelligenceSignalType.NEWS),
                    Math.max(fused.typeScore(com.bot.intelligence.bus.MarketIntelligenceSignalType.PRESS_RELEASE),
                            fused.typeScore(com.bot.intelligence.bus.MarketIntelligenceSignalType.CATALYST_CALENDAR))), 0.70,
                    "fused catalyst/news evidence");
        } catch (Exception e) {
            add("EVIDENCE_FUSION", "error", 0.45, 0.20, e.getMessage());
        }
    }

    private void addWorldEvidence(String strategyName) {
        try {
            WorldModelSnapshot world = WorldModelAgent.getInstance().currentSnapshot();
            if (world == null) {
                add("WORLD_MODEL", "missing", 0.45, 0.50, "world model not ready");
                return;
            }
            add("WORLD_MODEL", "dataConfidence", world.getDataConfidenceScore(), 0.65, world.getSummary());
            add("WORLD_MODEL", "liquidity", world.getLiquidityScore(), 0.55, "liquidity regime");
            add("WORLD_MODEL", "trend", world.getTrendScore(), 0.60, "trend regime");
            if (strategyName != null && strategyName.toUpperCase(Locale.ROOT).contains("PARABOLIC")) {
                add("WORLD_MODEL", "parabolicHeat", world.getParabolicHeatScore(), 0.85, "parabolic heat for parabolic strategy");
            } else {
                add("WORLD_MODEL", "catalystHeat", world.getCatalystHeatScore(), 0.70, "catalyst heat");
            }
        } catch (Exception e) {
            add("WORLD_MODEL", "error", 0.40, 0.30, e.getMessage());
        }
    }

    private void addOpportunityMemoryEvidence(String ticker, String strategyName) {
        try {
            OpportunityMemoryProfile profile = OpportunityMemoryService.getInstance().profile(ticker);
            double score = profile.opportunityScore();
            String best = profile.getBestStrategy() == null ? "" : profile.getBestStrategy().toUpperCase(Locale.ROOT);
            String strategy = strategyName == null ? "" : strategyName.toUpperCase(Locale.ROOT);
            if (!best.isBlank() && strategy.contains(best)) score = Math.min(1.0, score + 0.12);
            add("OPPORTUNITY_MEMORY", "tickerPersonality", score <= 0.0 ? 0.45 : score, 0.85,
                    "bestStrategy=" + profile.getBestStrategy() + " samples=" + profile.getObservations());
        } catch (Exception e) {
            add("OPPORTUNITY_MEMORY", "missing", 0.45, 0.35, e.getMessage());
        }
    }

    private void addReplayAndLifecycleEvidence(String ticker, String strategyName) {
        try {
            add("REPLAY", "whatIfHint", MarketReplayAgent.getInstance().hintFor(ticker, strategyName), 0.65,
                    "market replay what-if hint");
        } catch (Exception e) {
            add("REPLAY", "error", 0.45, 0.20, e.getMessage());
        }
        try {
            double mult = TradeLifecycleOptimizationAgent.getInstance().strategyMultiplier(strategyName);
            add("LIFECYCLE", "strategyQuality", clamp((mult - 0.75) / 0.50), 0.65,
                    "lifecycle multiplier=" + fmt(mult));
        } catch (Exception e) {
            add("LIFECYCLE", "error", 0.45, 0.20, e.getMessage());
        }
    }

    private void addRiskAndExecutionEvidence(StrategyContext context, StrategySignal signal) {
        double price = context == null ? 0.0 : context.getLastPrice();
        double expectedMove = signal == null ? 0.0 : signal.getExpectedMovePercent();
        double qty = signal == null ? 0.0 : signal.getSuggestedQuantity();
        double notional = Math.max(0.0, price * qty);
        double equity = context == null ? 0.0 : context.getAccountEquity();
        double exposure = equity <= 0.0 ? 0.0 : notional / equity;
        double riskScore = clamp(0.80 - Math.max(0.0, exposure - 0.05) * 2.0);
        double evScore = clamp(0.45 + Math.min(0.35, Math.max(0.0, expectedMove) * 0.08));
        double executionScore = ExecutionAnalyticsService.getInstance().executionScore(
                signal == null ? (context == null ? "" : context.getTicker()) : signal.getTicker(),
                signal == null ? "UNKNOWN" : signal.getStrategyName());
        add("RISK", "notionalExposure", riskScore, 0.80, "exposure=" + fmt(exposure));
        add("EXECUTION", "expectedMove", evScore, 0.55, "expectedMovePercent=" + fmt(expectedMove));
        add("EXECUTION", "historicalExecutionQuality", executionScore, 0.70,
                "fill/slippage/latency score from execution analytics");
    }

    private void add(String bucket, String name, double score, double weight, String detail) {
        nodes.add(new EvidenceNode(bucket, name, clamp(score), Math.max(0.0, weight), detail));
    }

    private synchronized void journal() {
        if (!envBoolean("CANDIDATE_EVIDENCE_GRAPH_ENABLED", true)) return;
        try {
            Path parent = JOURNAL.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(JOURNAL, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (EvidenceNode node : nodes) {
                    writer.write(String.join(",",
                            clean(Instant.now().toString()),
                            clean(ticker),
                            clean(strategy),
                            clean(node.bucket),
                            clean(node.name),
                            fmt(node.score),
                            fmt(node.weight),
                            fmt(weightedScore()),
                            clean(node.detail)
                    ));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            if (envBoolean("CANDIDATE_EVIDENCE_VERBOSE_ERRORS", false)) {
                System.out.println("CANDIDATE EVIDENCE JOURNAL FAILED: " + e.getMessage());
            }
        }
    }

    public static final class EvidenceNode {
        private final String bucket;
        private final String name;
        private final double score;
        private final double weight;
        private final String detail;

        private EvidenceNode(String bucket, String name, double score, double weight, String detail) {
            this.bucket = normalize(bucket);
            this.name = normalize(name);
            this.score = clamp(score);
            this.weight = Math.max(0.0, weight);
            this.detail = detail == null ? "" : detail;
        }

        public String getBucket() { return bucket; }
        public String getName() { return name; }
        public double getScore() { return score; }
        public double getWeight() { return weight; }
        public String getDetail() { return detail; }
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0.0;
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
    }
}
