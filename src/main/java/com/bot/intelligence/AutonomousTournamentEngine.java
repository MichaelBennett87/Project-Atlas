package com.bot.intelligence;

import com.bot.intelligence.generated.GeneratedAiStrategyPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Autonomous offline policy tournament.
 *
 * This is the "no human approval" improvement loop:
 *
 * 1. Read market_features.csv and trade_outcomes.csv.
 * 2. Generate many bounded policy candidates.
 * 3. Score every candidate on historical features, realized outcomes, strategy
 *    expectancy, selectivity, and Monte Carlo risk.
 * 4. Promote the champion automatically if it beats the current baseline by the
 *    configured margin.
 * 5. Rewrite only GeneratedAiStrategyPolicy.java.
 *
 * The live trading bot should never call this class. It is for after-hours
 * research/evolution only.
 */
public class AutonomousTournamentEngine {

    private final Config config;

    public AutonomousTournamentEngine() {
        this(Config.fromEnv());
    }

    public AutonomousTournamentEngine(Config config) {
        this.config = config == null ? Config.fromEnv() : config;
    }

    public TournamentResult run() {
        OfflineGuard.assertSafeToMutate();

        DataSet data = DataSet.load(config.featuresPath, config.outcomesPath);
        FilesUtil.ensureParent(config.reportPath);

        List<CandidatePolicy> candidates = new ArrayList<>();
        CandidatePolicy current = CandidatePolicy.fromGenerated("current_production");
        candidates.add(current);

        Random random = new Random(config.randomSeed);
        for (int i = 1; i <= config.candidateCount; i++) {
            CandidatePolicy mutated = current.mutated("candidate_" + pad(i), random, i, data);
            candidates.add(mutated);
        }

        for (CandidatePolicy c : candidates) {
            c.score(data, config);
        }

        candidates.sort(Comparator.comparingDouble((CandidatePolicy c) -> c.fitness).reversed());
        CandidatePolicy champion = candidates.get(0);
        CandidatePolicy baseline = current;
        boolean enoughData = data.featureRows >= config.minFeatureRows || data.closedTrades >= config.minClosedTrades;
        boolean beatsBaseline = champion != baseline && champion.fitness >= baseline.fitness + config.minFitnessImprovement;
        boolean promoted = enoughData && beatsBaseline;

        Path generationDir = config.candidateDir.resolve("generation_" + System.currentTimeMillis());
        writeCandidateFiles(generationDir, candidates, champion, baseline, data, promoted);

        if (promoted) {
            FilesUtil.writeString(config.generatedPolicyPath, champion.toJavaSource());
        }

        writeReport(candidates, champion, baseline, data, promoted, enoughData, generationDir);
        return new TournamentResult(promoted, champion.name, baseline.fitness, champion.fitness, data.featureRows, data.closedTrades, generationDir);
    }

    private void writeCandidateFiles(Path generationDir, List<CandidatePolicy> candidates, CandidatePolicy champion, CandidatePolicy baseline, DataSet data, boolean promoted) {
        FilesUtil.ensureDir(generationDir);
        int limit = Math.min(config.keepCandidateFiles, candidates.size());
        for (int i = 0; i < limit; i++) {
            CandidatePolicy c = candidates.get(i);
            FilesUtil.writeString(generationDir.resolve(c.name + "_GeneratedAiStrategyPolicy.java"), c.toJavaSource());
        }

        StringBuilder ranking = new StringBuilder();
        ranking.append("rank,name,fitness,expectedPnl,selectedRows,estimatedWinRate,monteCarloP05,maxDrawdownPenalty,reason\n");
        for (int i = 0; i < candidates.size(); i++) {
            CandidatePolicy c = candidates.get(i);
            ranking.append(i + 1).append(',')
                    .append(csv(c.name)).append(',')
                    .append(fmt(c.fitness)).append(',')
                    .append(fmt(c.expectedPnl)).append(',')
                    .append(c.selectedRows).append(',')
                    .append(fmt(c.estimatedWinRate)).append(',')
                    .append(fmt(c.monteCarloP05)).append(',')
                    .append(fmt(c.maxDrawdownPenalty)).append(',')
                    .append(csv(c.reason)).append('\n');
        }
        FilesUtil.writeString(generationDir.resolve("candidate_rankings.csv"), ranking.toString());

        String summary = "AUTONOMOUS TOURNAMENT SUMMARY\n" +
                "generatedAt=" + Instant.now() + "\n" +
                "featureRows=" + data.featureRows + "\n" +
                "closedTrades=" + data.closedTrades + "\n" +
                "baselineFitness=" + fmt(baseline.fitness) + "\n" +
                "champion=" + champion.name + "\n" +
                "championFitness=" + fmt(champion.fitness) + "\n" +
                "promoted=" + promoted + "\n" +
                "generatedPolicyPath=" + config.generatedPolicyPath + "\n";
        FilesUtil.writeString(generationDir.resolve("summary.txt"), summary);
    }

    private void writeReport(List<CandidatePolicy> candidates, CandidatePolicy champion, CandidatePolicy baseline, DataSet data, boolean promoted, boolean enoughData, Path generationDir) {
        StringBuilder b = new StringBuilder();
        b.append("AUTONOMOUS CODE EVOLUTION TOURNAMENT REPORT\n");
        b.append("generatedAt=").append(Instant.now()).append('\n');
        b.append("mode=OFFLINE_AUTONOMOUS_NO_HUMAN_APPROVAL\n");
        b.append("sourceMutationScope=GeneratedAiStrategyPolicy_only\n");
        b.append("featureRows=").append(data.featureRows).append('\n');
        b.append("closedTrades=").append(data.closedTrades).append('\n');
        b.append("realizedPnl=").append(fmt(data.totalPnl)).append('\n');
        b.append("realizedWinRate=").append(fmt(data.winRate())).append('\n');
        b.append("enoughData=").append(enoughData).append('\n');
        b.append("candidateCount=").append(candidates.size()).append('\n');
        b.append("baselineFitness=").append(fmt(baseline.fitness)).append('\n');
        b.append("champion=").append(champion.name).append('\n');
        b.append("championFitness=").append(fmt(champion.fitness)).append('\n');
        b.append("championExpectedPnl=").append(fmt(champion.expectedPnl)).append('\n');
        b.append("championSelectedRows=").append(champion.selectedRows).append('\n');
        b.append("championMonteCarloP05=").append(fmt(champion.monteCarloP05)).append('\n');
        b.append("promoted=").append(promoted).append('\n');
        b.append("candidateDirectory=").append(generationDir).append('\n');
        b.append('\n');
        b.append("TOP CANDIDATES\n");
        int top = Math.min(10, candidates.size());
        for (int i = 0; i < top; i++) {
            CandidatePolicy c = candidates.get(i);
            b.append(i + 1).append(". ")
                    .append(c.name)
                    .append(" fitness=").append(fmt(c.fitness))
                    .append(" expectedPnl=").append(fmt(c.expectedPnl))
                    .append(" selectedRows=").append(c.selectedRows)
                    .append(" winRate=").append(fmt(c.estimatedWinRate))
                    .append(" mcP05=").append(fmt(c.monteCarloP05))
                    .append(" reason=").append(c.reason)
                    .append('\n');
        }
        FilesUtil.writeString(config.reportPath, b.toString());
    }

    static final class Config {
        final Path featuresPath;
        final Path outcomesPath;
        final Path generatedPolicyPath;
        final Path reportPath;
        final Path candidateDir;
        final int candidateCount;
        final int keepCandidateFiles;
        final int minFeatureRows;
        final int minClosedTrades;
        final double minFitnessImprovement;
        final int monteCarloRuns;
        final long randomSeed;

        Config(Path featuresPath, Path outcomesPath, Path generatedPolicyPath, Path reportPath, Path candidateDir,
               int candidateCount, int keepCandidateFiles, int minFeatureRows, int minClosedTrades,
               double minFitnessImprovement, int monteCarloRuns, long randomSeed) {
            this.featuresPath = featuresPath;
            this.outcomesPath = outcomesPath;
            this.generatedPolicyPath = generatedPolicyPath;
            this.reportPath = reportPath;
            this.candidateDir = candidateDir;
            this.candidateCount = Math.max(10, candidateCount);
            this.keepCandidateFiles = Math.max(3, keepCandidateFiles);
            this.minFeatureRows = Math.max(25, minFeatureRows);
            this.minClosedTrades = Math.max(3, minClosedTrades);
            this.minFitnessImprovement = Math.max(0.0, minFitnessImprovement);
            this.monteCarloRuns = Math.max(50, monteCarloRuns);
            this.randomSeed = randomSeed;
        }

        static Config fromEnv() {
            return new Config(
                    Path.of(env("FEATURE_JOURNAL_PATH", "logs/market_features.csv")),
                    Path.of(env("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv")),
                    Path.of(env("GENERATED_AI_POLICY_SOURCE_PATH", "com/bot/intelligence/generated/GeneratedAiStrategyPolicy.java")),
                    Path.of(env("AI_SOURCE_EVOLUTION_REPORT_PATH", "logs/ai_source_evolution_report.txt")),
                    Path.of(env("AI_CANDIDATE_DIR", "logs/autonomous_candidates")),
                    intEnv("AI_EVOLUTION_CANDIDATE_COUNT", 100),
                    intEnv("AI_EVOLUTION_KEEP_CANDIDATE_FILES", 20),
                    intEnv("AI_MIN_FEATURE_ROWS_BEFORE_SOURCE_REWRITE", 250),
                    intEnv("AI_MIN_CLOSED_TRADES_BEFORE_SOURCE_REWRITE", 20),
                    doubleEnv("AI_MIN_FITNESS_IMPROVEMENT", 0.25),
                    intEnv("AI_MONTE_CARLO_RUNS", 500),
                    longEnv("AI_EVOLUTION_RANDOM_SEED", System.currentTimeMillis())
            );
        }

        private static String env(String key, String fallback) {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : v.trim();
        }

        private static int intEnv(String key, int fallback) {
            try { return Integer.parseInt(env(key, Integer.toString(fallback))); }
            catch (Exception e) { return fallback; }
        }

        private static long longEnv(String key, long fallback) {
            try { return Long.parseLong(env(key, Long.toString(fallback))); }
            catch (Exception e) { return fallback; }
        }

        private static double doubleEnv(String key, double fallback) {
            try { return Double.parseDouble(env(key, Double.toString(fallback))); }
            catch (Exception e) { return fallback; }
        }
    }

    static final class OfflineGuard {
        static void assertSafeToMutate() {
            boolean tradingEnabled = boolEnv("TRADING_ENABLED", false);
            boolean dryRun = boolEnv("DRY_RUN", true);
            Path liveLock = Path.of(System.getenv().getOrDefault("LIVE_TRADING_LOCK_PATH", "logs/live_trading.lock"));

            if (tradingEnabled && !dryRun) {
                throw new IllegalStateException("Refusing autonomous source evolution while TRADING_ENABLED=true and DRY_RUN=false.");
            }
            if (Files.exists(liveLock)) {
                throw new IllegalStateException("Refusing autonomous source evolution because live trading lock exists: " + liveLock);
            }
        }

        private static boolean boolEnv(String key, boolean fallback) {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : "true".equalsIgnoreCase(v.trim());
        }
    }

    static final class DataSet {
        final List<FeatureRow> features = new ArrayList<>();
        final List<OutcomeRow> outcomes = new ArrayList<>();
        final Map<String, StrategyStats> strategyStats = new LinkedHashMap<>();
        int featureRows;
        int closedTrades;
        double totalPnl;

        static DataSet load(Path featurePath, Path outcomePath) {
            DataSet d = new DataSet();
            d.loadFeatures(featurePath);
            d.loadOutcomes(outcomePath);
            return d;
        }

        private void loadFeatures(Path path) {
            if (!Files.exists(path)) return;
            try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String headerLine = r.readLine();
                if (headerLine == null) return;
                CsvHeader header = new CsvHeader(headerLine);
                String line;
                int maxRows = intEnv("AI_TOURNAMENT_MAX_FEATURE_ROWS_IN_MEMORY", 50000);
                while ((line = r.readLine()) != null) {
                    List<String> c = Csv.parse(line);
                    FeatureRow row = FeatureRow.from(header, c, line);
                    if (features.size() < maxRows) {
                        features.add(row);
                    }
                    featureRows++;
                }
            } catch (IOException e) {
                System.out.println("FEATURE LOAD FAILED: " + e.getMessage());
            }
        }

        private void loadOutcomes(Path path) {
            if (!Files.exists(path)) return;
            try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String headerLine = r.readLine();
                if (headerLine == null) return;
                CsvHeader header = new CsvHeader(headerLine);
                String line;
                int maxRows = intEnv("AI_TOURNAMENT_MAX_OUTCOME_ROWS_IN_MEMORY", 50000);
                while ((line = r.readLine()) != null) {
                    List<String> c = Csv.parse(line);
                    OutcomeRow row = OutcomeRow.from(header, c);
                    if (!row.isCloseEvent()) continue;
                    if (outcomes.size() < maxRows) {
                        outcomes.add(row);
                    }
                    closedTrades++;
                    totalPnl += row.realizedPnl;
                    StrategyStats stats = strategyStats.computeIfAbsent(row.strategyName, k -> new StrategyStats());
                    stats.add(row.realizedPnl);
                }
            } catch (IOException e) {
                System.out.println("OUTCOME LOAD FAILED: " + e.getMessage());
            }
        }

        double winRate() {
            if (closedTrades <= 0) return 0.0;
            int wins = 0;
            for (OutcomeRow o : outcomes) if (o.realizedPnl > 0) wins++;
            return (double) wins / closedTrades;
        }

        double avgOutcomePnl() {
            return closedTrades <= 0 ? 0.0 : totalPnl / closedTrades;
        }

        double fallbackTradePnl() {
            if (closedTrades > 0) {
                return avgOutcomePnl();
            }
            return 0.0;
        }
    }

    static final class FeatureRow {
        String ticker;
        int barCount;
        double rvol5;
        double rvol20;
        double return1Bar;
        double return3Bars;
        double return5Bars;
        double dropFromHigh20;
        double bounceFromLow20;
        double vwapDistance;
        double sentimentNet;
        double catalystScore;
        double freshnessSeconds;
        double pTarget;
        double pStop;
        double expectedValue;
        boolean bullishBreak;
        boolean reclaimedVwap;
        boolean failedBreakdown;
        boolean higherLows3;
        boolean noFreshLow3;
        String selectedStrategy;
        String selectedAction;
        String modelReason;
        String rawLine;

        static FeatureRow from(CsvHeader h, List<String> c, String rawLine) {
            FeatureRow r = new FeatureRow();
            r.ticker = h.get(c, "ticker");
            r.barCount = (int) num(h.get(c, "barCount"), 0);
            r.rvol5 = num(h.get(c, "rvol5"), 0);
            r.rvol20 = num(h.get(c, "rvol20"), 0);
            r.return1Bar = num(h.get(c, "return1Bar"), 0);
            r.return3Bars = num(h.get(c, "return3Bars"), 0);
            r.return5Bars = num(h.get(c, "return5Bars"), 0);
            r.dropFromHigh20 = num(h.get(c, "dropFromHigh20"), 0);
            r.bounceFromLow20 = num(h.get(c, "bounceFromLow20"), 0);
            r.vwapDistance = num(h.get(c, "vwapDistance"), 0);
            r.sentimentNet = num(h.get(c, "sentimentNet"), 0);
            r.catalystScore = num(h.get(c, "catalystScore"), 0);
            r.freshnessSeconds = num(h.get(c, "freshnessSeconds"), 999999);
            r.pTarget = num(h.get(c, "pProfitTarget"), 0);
            r.pStop = num(h.get(c, "pStopLoss"), 1);
            r.expectedValue = num(h.get(c, "expectedValuePercent"), 0);
            r.bullishBreak = bool(h.get(c, "bullishBreak")) || rawLine.toLowerCase().contains("bullish break");
            r.reclaimedVwap = bool(h.get(c, "reclaimedVwap")) || rawLine.toLowerCase().contains("vwap strength");
            r.failedBreakdown = bool(h.get(c, "failedBreakdown"));
            r.higherLows3 = bool(h.get(c, "higherLows3"));
            r.noFreshLow3 = bool(h.get(c, "noFreshLow3"));
            r.selectedStrategy = h.get(c, "selectedStrategy");
            r.selectedAction = h.get(c, "selectedAction");
            r.modelReason = h.get(c, "modelReason");
            r.rawLine = rawLine == null ? "" : rawLine;
            return r;
        }

        boolean hasRvol() {
            return rvol5 > 0.0 || rvol20 > 0.0;
        }

        boolean hasGoodStructure() {
            return bullishBreak || reclaimedVwap || failedBreakdown || (higherLows3 && noFreshLow3);
        }

        boolean selectedBuy() {
            String a = selectedAction == null ? "" : selectedAction.toUpperCase();
            return a.contains("BUY") || a.contains("READY");
        }
    }

    static final class OutcomeRow {
        String eventType;
        String ticker;
        String strategyName;
        String syncedFromBroker;
        double realizedPnl;
        double maxGainPercent;
        double maxDrawdownPercent;

        static OutcomeRow from(CsvHeader h, List<String> c) {
            OutcomeRow r = new OutcomeRow();
            r.eventType = h.get(c, "eventType");
            r.ticker = h.get(c, "ticker");
            r.strategyName = TradeOutcomeTrainingFilter.normalizeStrategy(h.get(c, "strategyName"));
            r.syncedFromBroker = h.get(c, "syncedFromBroker");
            r.realizedPnl = num(h.get(c, "realizedPnlDollars"), 0);
            r.maxGainPercent = num(h.get(c, "maxGainPercent"), 0);
            r.maxDrawdownPercent = num(h.get(c, "maxDrawdownPercent"), 0);
            return r;
        }

        boolean isCloseEvent() {
            return TradeOutcomeTrainingFilter.isTrainingEligible(eventType, strategyName, syncedFromBroker);
        }
    }

    static final class StrategyStats {
        int trades;
        int wins;
        double pnl;
        double grossWins;
        double grossLosses;

        void add(double value) {
            trades++;
            pnl += value;
            if (value > 0) {
                wins++;
                grossWins += value;
            } else if (value < 0) {
                grossLosses += Math.abs(value);
            }
        }

        double expectancy() {
            return trades <= 0 ? 0.0 : pnl / trades;
        }

        double profitFactor() {
            if (grossLosses <= 0) return grossWins > 0 ? 9.0 : 1.0;
            return grossWins / grossLosses;
        }
    }

    static final class CandidatePolicy {
        String name;
        String reason;
        long generatedAtMs;

        double baseTargetProbability = GeneratedAiStrategyPolicy.BASE_TARGET_PROBABILITY;
        double rvolExpansionBonus = GeneratedAiStrategyPolicy.RVOL_EXPANSION_BONUS;
        double moderateRvolBonus = GeneratedAiStrategyPolicy.MODERATE_RVOL_BONUS;
        double earlyMissingRvolPenalty = GeneratedAiStrategyPolicy.EARLY_MISSING_RVOL_PENALTY;
        double weakRvolPenalty = GeneratedAiStrategyPolicy.WEAK_RVOL_PENALTY;
        double bullishBreakBonus = GeneratedAiStrategyPolicy.BULLISH_BREAK_BONUS;
        double vwapStrengthBonus = GeneratedAiStrategyPolicy.VWAP_STRENGTH_BONUS;
        double failedBreakdownBonus = GeneratedAiStrategyPolicy.FAILED_BREAKDOWN_BONUS;
        double structureStabilityBonus = GeneratedAiStrategyPolicy.STRUCTURE_STABILITY_BONUS;
        double oneBarContinuationBonus = GeneratedAiStrategyPolicy.ONE_BAR_CONTINUATION_BONUS;
        double threeBarContinuationBonus = GeneratedAiStrategyPolicy.THREE_BAR_CONTINUATION_BONUS;
        double fallingKnifePenalty = GeneratedAiStrategyPolicy.FALLING_KNIFE_PENALTY;
        double overextendedRsiPenalty = GeneratedAiStrategyPolicy.OVEREXTENDED_RSI_PENALTY;
        double excessiveVolatilityPenalty = GeneratedAiStrategyPolicy.EXCESSIVE_VOLATILITY_PENALTY;
        double freshCatalystBonus = GeneratedAiStrategyPolicy.FRESH_CATALYST_BONUS;
        double negativeSentimentPenalty = GeneratedAiStrategyPolicy.NEGATIVE_SENTIMENT_PENALTY;
        double avgWinPercent = GeneratedAiStrategyPolicy.AVG_WIN_PERCENT;
        double avgLossPercent = GeneratedAiStrategyPolicy.AVG_LOSS_PERCENT;

        double momentumReturn3Multiplier = GeneratedAiStrategyPolicy.MOMENTUM_RETURN3_MULTIPLIER;
        double momentumRvolBonus = GeneratedAiStrategyPolicy.MOMENTUM_RVOL_BONUS;
        double momentumBreakBonus = GeneratedAiStrategyPolicy.MOMENTUM_BREAK_BONUS;
        double momentumVwapBonus = GeneratedAiStrategyPolicy.MOMENTUM_VWAP_BONUS;
        double meanReversionDropMultiplier = GeneratedAiStrategyPolicy.MEAN_REVERSION_DROP_MULTIPLIER;
        double meanReversionBounceMultiplier = GeneratedAiStrategyPolicy.MEAN_REVERSION_BOUNCE_MULTIPLIER;
        double meanReversionVwapBonus = GeneratedAiStrategyPolicy.MEAN_REVERSION_VWAP_BONUS;
        double meanReversionHigherLowsBonus = GeneratedAiStrategyPolicy.MEAN_REVERSION_HIGHER_LOWS_BONUS;
        double vwapReclaimBaseBonus = GeneratedAiStrategyPolicy.VWAP_RECLAIM_BASE_BONUS;
        double vwapDistanceBonus = GeneratedAiStrategyPolicy.VWAP_DISTANCE_BONUS;
        double vwapRvolBonus = GeneratedAiStrategyPolicy.VWAP_RVOL_BONUS;
        double squeezeRvolBonus = GeneratedAiStrategyPolicy.SQUEEZE_RVOL_BONUS;
        double squeezeGreenVolumeBonus = GeneratedAiStrategyPolicy.SQUEEZE_GREEN_VOLUME_BONUS;
        double squeezeBreakBonus = GeneratedAiStrategyPolicy.SQUEEZE_BREAK_BONUS;
        double squeezeSentimentBonus = GeneratedAiStrategyPolicy.SQUEEZE_SENTIMENT_BONUS;

        double fitness;
        double expectedPnl;
        double estimatedWinRate;
        double monteCarloP05;
        double maxDrawdownPenalty;
        int selectedRows;

        static CandidatePolicy fromGenerated(String name) {
            CandidatePolicy c = new CandidatePolicy();
            c.name = name;
            c.reason = "current generated production policy";
            c.generatedAtMs = System.currentTimeMillis();
            return c;
        }

        CandidatePolicy copy(String nextName) {
            CandidatePolicy n = new CandidatePolicy();
            n.name = nextName;
            n.reason = reason;
            n.generatedAtMs = System.currentTimeMillis();
            n.baseTargetProbability = baseTargetProbability;
            n.rvolExpansionBonus = rvolExpansionBonus;
            n.moderateRvolBonus = moderateRvolBonus;
            n.earlyMissingRvolPenalty = earlyMissingRvolPenalty;
            n.weakRvolPenalty = weakRvolPenalty;
            n.bullishBreakBonus = bullishBreakBonus;
            n.vwapStrengthBonus = vwapStrengthBonus;
            n.failedBreakdownBonus = failedBreakdownBonus;
            n.structureStabilityBonus = structureStabilityBonus;
            n.oneBarContinuationBonus = oneBarContinuationBonus;
            n.threeBarContinuationBonus = threeBarContinuationBonus;
            n.fallingKnifePenalty = fallingKnifePenalty;
            n.overextendedRsiPenalty = overextendedRsiPenalty;
            n.excessiveVolatilityPenalty = excessiveVolatilityPenalty;
            n.freshCatalystBonus = freshCatalystBonus;
            n.negativeSentimentPenalty = negativeSentimentPenalty;
            n.avgWinPercent = avgWinPercent;
            n.avgLossPercent = avgLossPercent;
            n.momentumReturn3Multiplier = momentumReturn3Multiplier;
            n.momentumRvolBonus = momentumRvolBonus;
            n.momentumBreakBonus = momentumBreakBonus;
            n.momentumVwapBonus = momentumVwapBonus;
            n.meanReversionDropMultiplier = meanReversionDropMultiplier;
            n.meanReversionBounceMultiplier = meanReversionBounceMultiplier;
            n.meanReversionVwapBonus = meanReversionVwapBonus;
            n.meanReversionHigherLowsBonus = meanReversionHigherLowsBonus;
            n.vwapReclaimBaseBonus = vwapReclaimBaseBonus;
            n.vwapDistanceBonus = vwapDistanceBonus;
            n.vwapRvolBonus = vwapRvolBonus;
            n.squeezeRvolBonus = squeezeRvolBonus;
            n.squeezeGreenVolumeBonus = squeezeGreenVolumeBonus;
            n.squeezeBreakBonus = squeezeBreakBonus;
            n.squeezeSentimentBonus = squeezeSentimentBonus;
            return n;
        }

        CandidatePolicy mutated(String nextName, Random random, int index, DataSet data) {
            CandidatePolicy n = copy(nextName);

            double realizedExpectancy = data.avgOutcomePnl();
            double winRate = data.winRate();
            double noRvolRatio = data.featureRows <= 0 ? 0.0 : countNoRvol(data) / (double) data.featureRows;

            double scale = 0.03 + (index % 7) * 0.005;
            n.baseTargetProbability += randomDelta(random, scale);
            n.rvolExpansionBonus += randomDelta(random, scale);
            n.moderateRvolBonus += randomDelta(random, scale * 0.8);
            n.earlyMissingRvolPenalty += randomDelta(random, scale * 0.6);
            n.weakRvolPenalty += randomDelta(random, scale * 0.8);
            n.bullishBreakBonus += randomDelta(random, scale);
            n.vwapStrengthBonus += randomDelta(random, scale);
            n.failedBreakdownBonus += randomDelta(random, scale);
            n.structureStabilityBonus += randomDelta(random, scale * 0.7);
            n.fallingKnifePenalty += randomDelta(random, scale);
            n.excessiveVolatilityPenalty += randomDelta(random, scale);
            n.avgWinPercent += randomDelta(random, 0.30);
            n.avgLossPercent += randomDelta(random, 0.20);
            n.momentumReturn3Multiplier += randomDelta(random, 2.0);
            n.momentumRvolBonus += randomDelta(random, scale);
            n.momentumBreakBonus += randomDelta(random, scale);
            n.momentumVwapBonus += randomDelta(random, scale);
            n.meanReversionDropMultiplier += randomDelta(random, 0.80);
            n.meanReversionBounceMultiplier += randomDelta(random, 1.20);
            n.vwapReclaimBaseBonus += randomDelta(random, scale);
            n.vwapDistanceBonus += randomDelta(random, scale);
            n.vwapRvolBonus += randomDelta(random, scale);
            n.squeezeRvolBonus += randomDelta(random, scale);
            n.squeezeGreenVolumeBonus += randomDelta(random, scale);
            n.squeezeBreakBonus += randomDelta(random, scale);

            if (data.closedTrades >= 10 && realizedExpectancy < 0.0) {
                n.baseTargetProbability += 0.02;
                n.avgLossPercent = Math.max(1.5, n.avgLossPercent - 0.10);
                n.fallingKnifePenalty -= 0.02;
            }
            if (data.closedTrades >= 10 && realizedExpectancy > 0.0 && winRate > 0.50) {
                n.baseTargetProbability -= 0.005;
                n.avgWinPercent += 0.05;
            }
            if (noRvolRatio > 0.35) {
                n.earlyMissingRvolPenalty = Math.min(-0.001, n.earlyMissingRvolPenalty + 0.01);
            }

            n.reason = "candidate mutation index=" + index + " sourceExpectancy=" + fmt(realizedExpectancy) + " winRate=" + fmt(winRate);
            n.clamp();
            return n;
        }

        void score(DataSet data, Config config) {
            selectedRows = 0;
            double syntheticPnl = 0.0;
            int syntheticWins = 0;
            int usableRows = 0;
            List<Double> selectedReturns = new ArrayList<>();

            for (FeatureRow row : data.features) {
                double p = predictTargetProbability(row);
                double stop = predictStopProbability(row);
                double ev = p * avgWinPercent - stop * avgLossPercent;
                boolean allowed = p >= dynamicThreshold(row) && ev >= 0.25 && row.barCount >= 1;

                usableRows++;
                if (allowed) {
                    selectedRows++;
                    double synthetic = estimateOutcome(row, p, ev, data);
                    syntheticPnl += synthetic;
                    selectedReturns.add(synthetic);
                    if (synthetic > 0) syntheticWins++;
                }
            }

            if (selectedRows == 0 && !data.features.isEmpty()) {
                fitness = -1000.0;
                expectedPnl = -1000.0;
                estimatedWinRate = 0.0;
                monteCarloP05 = -1000.0;
                maxDrawdownPenalty = 100.0;
                return;
            }

            double selectivity = data.featureRows <= 0 ? 0.0 : selectedRows / (double) data.featureRows;
            double selectivityPenalty = selectivity > 0.20 ? (selectivity - 0.20) * 200.0 : 0.0;
            double starvationPenalty = selectivity < 0.005 && data.featureRows > 100 ? 20.0 : 0.0;

            expectedPnl = syntheticPnl;
            estimatedWinRate = selectedRows <= 0 ? 0.0 : syntheticWins / (double) selectedRows;
            monteCarloP05 = monteCarloP05(selectedReturns, config.monteCarloRuns, selectedRows + 17L);
            maxDrawdownPenalty = maxDrawdownPenalty(selectedReturns);

            double realizedStrategyBoost = strategyOutcomeBoost(data);
            fitness = syntheticPnl
                    + monteCarloP05 * 0.35
                    + estimatedWinRate * 25.0
                    + realizedStrategyBoost
                    - maxDrawdownPenalty
                    - selectivityPenalty
                    - starvationPenalty;

            if (usableRows < config.minFeatureRows && data.closedTrades < config.minClosedTrades) {
                fitness -= 10.0;
            }
        }

        private double dynamicThreshold(FeatureRow row) {
            double threshold = baseTargetProbability;
            if (!row.hasRvol()) threshold += Math.max(0.0, -earlyMissingRvolPenalty) * 0.5;
            if (row.hasGoodStructure()) threshold -= 0.015;
            if (row.freshnessSeconds < 120) threshold -= 0.01;
            return AutonomousTournamentEngine.clamp(threshold, 0.35, 0.62);
        }

        private double predictTargetProbability(FeatureRow f) {
            double score = baseTargetProbability;
            if (f.rvol5 >= 2.0 || f.rvol20 >= 2.0) score += rvolExpansionBonus;
            else if (f.rvol5 >= 1.25 || f.rvol20 >= 1.25) score += moderateRvolBonus;
            else if (!f.hasRvol() && f.barCount < 6) score += earlyMissingRvolPenalty;
            else score += weakRvolPenalty;

            if (f.bullishBreak) score += bullishBreakBonus;
            if (f.reclaimedVwap || f.vwapDistance > 0.002) score += vwapStrengthBonus;
            if (f.failedBreakdown) score += failedBreakdownBonus;
            if (f.higherLows3 && f.noFreshLow3) score += structureStabilityBonus;
            if (f.return1Bar > 0.001) score += oneBarContinuationBonus;
            if (f.return3Bars > 0.002) score += threeBarContinuationBonus;
            if (f.dropFromHigh20 > 0.08 && !f.noFreshLow3) score += fallingKnifePenalty;
            if (f.freshnessSeconds < 180 && f.sentimentNet > 0.25) score += freshCatalystBonus;
            if (f.sentimentNet < -0.25) score += negativeSentimentPenalty;
            if (f.pTarget > 0.0) score = (score * 0.65) + (f.pTarget * 0.35);
            return AutonomousTournamentEngine.clamp(score, 0.0, 1.0);
        }

        private double predictStopProbability(FeatureRow f) {
            double stop = 0.30;
            if (f.dropFromHigh20 > 0.08 && !f.reclaimedVwap) stop += 0.15;
            if (!f.hasGoodStructure()) stop += 0.10;
            if (f.sentimentNet < -0.20) stop += 0.10;
            if (f.rvol5 >= 2.0 || f.rvol20 >= 2.0) stop -= 0.05;
            if (f.pStop > 0.0 && f.pStop < 1.0) stop = (stop * 0.65) + (f.pStop * 0.35);
            return AutonomousTournamentEngine.clamp(stop, 0.05, 0.95);
        }

        private double estimateOutcome(FeatureRow row, double p, double ev, DataSet data) {
            double base = p * avgWinPercent - (1.0 - p) * avgLossPercent;
            if (row.selectedBuy()) base += 0.15;
            if (row.hasGoodStructure()) base += 0.10;
            if (!row.hasRvol()) base -= 0.05;
            if (data.closedTrades > 0) base += data.avgOutcomePnl() * 0.25;
            return base + ev * 0.05;
        }

        private double strategyOutcomeBoost(DataSet data) {
            double boost = 0.0;
            for (Map.Entry<String, StrategyStats> e : data.strategyStats.entrySet()) {
                StrategyStats s = e.getValue();
                if (s.trades < 3) continue;
                double pf = s.profitFactor();
                if (pf > 1.25) boost += Math.min(5.0, s.expectancy());
                if (pf < 0.85) boost -= Math.min(5.0, Math.abs(s.expectancy()));
            }
            return boost;
        }

        private double monteCarloP05(List<Double> returns, int runs, long seed) {
            if (returns == null || returns.isEmpty()) return -10.0;
            Random r = new Random(seed);
            List<Double> samples = new ArrayList<>();
            int drawCount = Math.min(50, Math.max(5, returns.size()));
            for (int i = 0; i < runs; i++) {
                double sum = 0.0;
                for (int j = 0; j < drawCount; j++) {
                    sum += returns.get(r.nextInt(returns.size()));
                }
                samples.add(sum);
            }
            samples.sort(Double::compare);
            int idx = Math.max(0, Math.min(samples.size() - 1, (int) Math.floor(samples.size() * 0.05)));
            return samples.get(idx);
        }

        private double maxDrawdownPenalty(List<Double> returns) {
            if (returns == null || returns.isEmpty()) return 10.0;
            double equity = 0.0;
            double peak = 0.0;
            double maxDd = 0.0;
            for (double v : returns) {
                equity += v;
                peak = Math.max(peak, equity);
                maxDd = Math.max(maxDd, peak - equity);
            }
            return maxDd * 0.30;
        }

        private String toJavaSource() {
            String safeReason = reason == null ? "generated" : reason.replace("\\", "\\\\").replace("\"", "\\\"");
            return "package com.bot.intelligence.generated;\n\n" +
                    "/**\n" +
                    " * Auto-generated by AutonomousTournamentEngine.\n" +
                    " * Candidate: " + name + "\n" +
                    " * Fitness: " + fmt(fitness) + "\n" +
                    " */\n" +
                    "public final class GeneratedAiStrategyPolicy {\n" +
                    "    private GeneratedAiStrategyPolicy() {}\n\n" +
                    "    public static final long GENERATED_AT_MS = " + System.currentTimeMillis() + "L;\n" +
                    "    public static final String GENERATION_REASON = \"" + safeReason + "\";\n\n" +
                    field("BASE_TARGET_PROBABILITY", baseTargetProbability) +
                    field("RVOL_EXPANSION_BONUS", rvolExpansionBonus) +
                    field("MODERATE_RVOL_BONUS", moderateRvolBonus) +
                    field("EARLY_MISSING_RVOL_PENALTY", earlyMissingRvolPenalty) +
                    field("WEAK_RVOL_PENALTY", weakRvolPenalty) +
                    field("BULLISH_BREAK_BONUS", bullishBreakBonus) +
                    field("VWAP_STRENGTH_BONUS", vwapStrengthBonus) +
                    field("FAILED_BREAKDOWN_BONUS", failedBreakdownBonus) +
                    field("STRUCTURE_STABILITY_BONUS", structureStabilityBonus) +
                    field("ONE_BAR_CONTINUATION_BONUS", oneBarContinuationBonus) +
                    field("THREE_BAR_CONTINUATION_BONUS", threeBarContinuationBonus) +
                    field("FALLING_KNIFE_PENALTY", fallingKnifePenalty) +
                    field("OVEREXTENDED_RSI_PENALTY", overextendedRsiPenalty) +
                    field("EXCESSIVE_VOLATILITY_PENALTY", excessiveVolatilityPenalty) +
                    field("FRESH_CATALYST_BONUS", freshCatalystBonus) +
                    field("NEGATIVE_SENTIMENT_PENALTY", negativeSentimentPenalty) +
                    "\n" +
                    field("AVG_WIN_PERCENT", avgWinPercent) +
                    field("AVG_LOSS_PERCENT", avgLossPercent) +
                    "\n" +
                    field("MOMENTUM_RETURN3_MULTIPLIER", momentumReturn3Multiplier) +
                    field("MOMENTUM_RVOL_BONUS", momentumRvolBonus) +
                    field("MOMENTUM_BREAK_BONUS", momentumBreakBonus) +
                    field("MOMENTUM_VWAP_BONUS", momentumVwapBonus) +
                    "\n" +
                    field("MEAN_REVERSION_DROP_MULTIPLIER", meanReversionDropMultiplier) +
                    field("MEAN_REVERSION_BOUNCE_MULTIPLIER", meanReversionBounceMultiplier) +
                    field("MEAN_REVERSION_VWAP_BONUS", meanReversionVwapBonus) +
                    field("MEAN_REVERSION_HIGHER_LOWS_BONUS", meanReversionHigherLowsBonus) +
                    "\n" +
                    field("VWAP_RECLAIM_BASE_BONUS", vwapReclaimBaseBonus) +
                    field("VWAP_DISTANCE_BONUS", vwapDistanceBonus) +
                    field("VWAP_RVOL_BONUS", vwapRvolBonus) +
                    "\n" +
                    field("SQUEEZE_RVOL_BONUS", squeezeRvolBonus) +
                    field("SQUEEZE_GREEN_VOLUME_BONUS", squeezeGreenVolumeBonus) +
                    field("SQUEEZE_BREAK_BONUS", squeezeBreakBonus) +
                    field("SQUEEZE_SENTIMENT_BONUS", squeezeSentimentBonus) +
                    "}\n";
        }

        private String field(String name, double value) {
            return "    public static final double " + name + " = " + Double.toString(value) + ";\n";
        }

        private void clamp() {
            baseTargetProbability = AutonomousTournamentEngine.clamp(baseTargetProbability, 0.36, 0.62);
            rvolExpansionBonus = AutonomousTournamentEngine.clamp(rvolExpansionBonus, 0.05, 0.45);
            moderateRvolBonus = AutonomousTournamentEngine.clamp(moderateRvolBonus, 0.00, 0.25);
            earlyMissingRvolPenalty = AutonomousTournamentEngine.clamp(earlyMissingRvolPenalty, -0.15, -0.001);
            weakRvolPenalty = AutonomousTournamentEngine.clamp(weakRvolPenalty, -0.30, -0.01);
            bullishBreakBonus = AutonomousTournamentEngine.clamp(bullishBreakBonus, 0.02, 0.45);
            vwapStrengthBonus = AutonomousTournamentEngine.clamp(vwapStrengthBonus, 0.02, 0.40);
            failedBreakdownBonus = AutonomousTournamentEngine.clamp(failedBreakdownBonus, 0.02, 0.40);
            structureStabilityBonus = AutonomousTournamentEngine.clamp(structureStabilityBonus, 0.00, 0.30);
            fallingKnifePenalty = AutonomousTournamentEngine.clamp(fallingKnifePenalty, -0.45, -0.02);
            excessiveVolatilityPenalty = AutonomousTournamentEngine.clamp(excessiveVolatilityPenalty, -0.40, -0.01);
            avgWinPercent = AutonomousTournamentEngine.clamp(avgWinPercent, 2.0, 12.0);
            avgLossPercent = AutonomousTournamentEngine.clamp(avgLossPercent, 1.0, 6.0);
            momentumReturn3Multiplier = AutonomousTournamentEngine.clamp(momentumReturn3Multiplier, 4.0, 25.0);
            momentumRvolBonus = AutonomousTournamentEngine.clamp(momentumRvolBonus, 0.02, 0.50);
            momentumBreakBonus = AutonomousTournamentEngine.clamp(momentumBreakBonus, 0.02, 0.50);
            momentumVwapBonus = AutonomousTournamentEngine.clamp(momentumVwapBonus, 0.00, 0.35);
            meanReversionDropMultiplier = AutonomousTournamentEngine.clamp(meanReversionDropMultiplier, 1.0, 10.0);
            meanReversionBounceMultiplier = AutonomousTournamentEngine.clamp(meanReversionBounceMultiplier, 2.0, 16.0);
            meanReversionVwapBonus = AutonomousTournamentEngine.clamp(meanReversionVwapBonus, 0.02, 0.50);
            meanReversionHigherLowsBonus = AutonomousTournamentEngine.clamp(meanReversionHigherLowsBonus, 0.00, 0.35);
            vwapReclaimBaseBonus = AutonomousTournamentEngine.clamp(vwapReclaimBaseBonus, 0.10, 0.75);
            vwapDistanceBonus = AutonomousTournamentEngine.clamp(vwapDistanceBonus, 0.02, 0.50);
            vwapRvolBonus = AutonomousTournamentEngine.clamp(vwapRvolBonus, 0.00, 0.35);
            squeezeRvolBonus = AutonomousTournamentEngine.clamp(squeezeRvolBonus, 0.02, 0.50);
            squeezeGreenVolumeBonus = AutonomousTournamentEngine.clamp(squeezeGreenVolumeBonus, 0.02, 0.50);
            squeezeBreakBonus = AutonomousTournamentEngine.clamp(squeezeBreakBonus, 0.02, 0.50);
            squeezeSentimentBonus = AutonomousTournamentEngine.clamp(squeezeSentimentBonus, 0.00, 0.35);
        }

        private static int countNoRvol(DataSet data) {
            int n = 0;
            for (FeatureRow r : data.features) {
                if (!r.hasRvol()) n++;
            }
            return n;
        }

        private static double randomDelta(Random random, double scale) {
            return (random.nextDouble() * 2.0 - 1.0) * scale;
        }
    }

    static final class TournamentResult {
        final boolean promoted;
        final String championName;
        final double baselineFitness;
        final double championFitness;
        final int featureRows;
        final int closedTrades;
        final Path generationDir;

        TournamentResult(boolean promoted, String championName, double baselineFitness, double championFitness, int featureRows, int closedTrades, Path generationDir) {
            this.promoted = promoted;
            this.championName = championName;
            this.baselineFitness = baselineFitness;
            this.championFitness = championFitness;
            this.featureRows = featureRows;
            this.closedTrades = closedTrades;
            this.generationDir = generationDir;
        }

        public String toConsoleSummary() {
            return "AUTONOMOUS TOURNAMENT COMPLETE: promoted=" + promoted +
                    " champion=" + championName +
                    " baselineFitness=" + fmt(baselineFitness) +
                    " championFitness=" + fmt(championFitness) +
                    " featureRows=" + featureRows +
                    " closedTrades=" + closedTrades +
                    " generationDir=" + generationDir;
        }
    }

    static final class CsvHeader {
        private final Map<String, Integer> index = new HashMap<>();
        CsvHeader(String headerLine) {
            List<String> cols = Csv.parse(headerLine);
            for (int i = 0; i < cols.size(); i++) {
                index.put(cols.get(i).trim(), i);
            }
        }
        String get(List<String> cols, String name) {
            Integer i = index.get(name);
            return i == null || i < 0 || i >= cols.size() ? "" : cols.get(i);
        }
    }

    static final class Csv {
        static List<String> parse(String line) {
            List<String> out = new ArrayList<>();
            if (line == null) return out;
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            cur.append('"');
                            i++;
                        } else quoted = false;
                    } else cur.append(ch);
                } else {
                    if (ch == ',') {
                        out.add(cur.toString());
                        cur.setLength(0);
                    } else if (ch == '"') quoted = true;
                    else cur.append(ch);
                }
            }
            out.add(cur.toString());
            return out;
        }
    }

    static final class FilesUtil {
        static void ensureParent(Path path) {
            Path p = path == null ? null : path.getParent();
            if (p != null) ensureDir(p);
        }

        static void ensureDir(Path path) {
            try { Files.createDirectories(path); }
            catch (IOException e) { throw new RuntimeException("Failed to create directory " + path + ": " + e.getMessage(), e); }
        }

        static void writeString(Path path, String value) {
            try {
                ensureParent(path);
                Files.writeString(path, value == null ? "" : value, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write " + path + ": " + e.getMessage(), e);
            }
        }
    }

    private static double num(String v, double fallback) {
        try { return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static boolean bool(String v) {
        return "true".equalsIgnoreCase(v == null ? "" : v.trim());
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static String pad(int n) {
        if (n < 10) return "00" + n;
        if (n < 100) return "0" + n;
        return Integer.toString(n);
    }


    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static int intEnv(String key, int fallback) {
        try { return Integer.parseInt(env(key, Integer.toString(fallback))); }
        catch (Exception e) { return fallback; }
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v.replace('\n', ' ').replace('\r', ' ');
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0.0;
        return new DecimalFormat("0.0000").format(v);
    }
}
