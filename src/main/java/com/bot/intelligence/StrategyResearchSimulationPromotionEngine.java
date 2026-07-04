package com.bot.intelligence;

import com.bot.governance.PromotionGatekeeper;
import com.bot.intelligence.historical.HistoricalResearchOrchestrator;
import com.bot.master.MasterStrategyEngine;
import com.bot.master.TradingStrategy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Offline research -> simulation -> promotion loop.
 *
 * The live bot should consume only the promoted policy output. New strategy
 * source ideas are staged as candidate artifacts so the governance gate can
 * inspect them before they ever become production code.
 */
public final class StrategyResearchSimulationPromotionEngine {

    private final Path replayReportPath = Path.of(env("HISTORICAL_REPLAY_REPORT_PATH", "logs/historical_replay_report.csv"));
    private final Path outcomesPath = Path.of(env("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv"));
    private final Path discoveredPath = Path.of(env("DISCOVERED_STRATEGIES_PATH", "logs/discovered_strategies.properties"));
    private final Path replayPolicyPath = Path.of(env("REPLAY_STRATEGY_POLICY_PATH", "logs/replay_strategy_policy.properties"));
    private final Path policyPath = Path.of(env("SIMULATION_STRATEGY_POLICY_PATH", "logs/simulation_strategy_policy.properties"));
    private final Path candidateCsvPath = Path.of(env("STRATEGY_RESEARCH_CANDIDATES_PATH", "logs/strategy_research_candidates.csv"));
    private final Path reportPath = Path.of(env("STRATEGY_RESEARCH_SIMULATION_REPORT_PATH", "logs/strategy_research_simulation_report.txt"));
    private final Path openAiReviewPath = Path.of(env("OPENAI_STRATEGY_RESEARCH_REVIEW_PATH", "logs/openai_strategy_research_review.json"));
    private final Path candidateRoot = Path.of(env("AI_CANDIDATE_PATCH_ROOT", "logs/autonomous_candidates"))
            .resolve("strategy_research");

    private final int minReplaySamples = Math.max(5, envInt("SIMULATION_PROMOTION_MIN_REPLAY_SAMPLES", 20));
    private final int minLiveSamplesForVeto = Math.max(3, envInt("SIMULATION_PROMOTION_MIN_LIVE_SAMPLES_FOR_VETO", 8));
    private final double minAvgQuality = envDouble("SIMULATION_PROMOTION_MIN_AVG_QUALITY", 2.0);
    private final double minAvgExpectedValue = envDouble("SIMULATION_PROMOTION_MIN_AVG_EXPECTED_VALUE", 0.35);
    private final double minAvgTargetProbability = envDouble("SIMULATION_PROMOTION_MIN_TARGET_PROBABILITY", 0.50);
    private final double minSimulationScore = envDouble("SIMULATION_PROMOTION_MIN_SCORE", 0.58);
    private final double maxPromotionMultiplier = envDouble("SIMULATION_PROMOTION_MAX_MULTIPLIER", 1.18);

    public Result run() {
        long started = System.currentTimeMillis();
        List<String> steps = new ArrayList<>();
        if (envBool("AI_STRATEGY_RESEARCH_RUN_HISTORICAL_PULLS", false)) {
            HistoricalResearchOrchestrator.Result research = new HistoricalResearchOrchestrator().runNightlyResearch();
            steps.add("historicalResearch=" + research.summary());
        }
        if (envBool("AI_STRATEGY_RESEARCH_RUN_REPLAY", false) || !Files.exists(replayReportPath)) {
            HistoricalReplayEngine.ReplayResult replay = new HistoricalReplayEngine().runReplay();
            steps.add("historicalReplay=" + replay.summary());
        }
        if (envBool("AI_STRATEGY_RESEARCH_RUN_DISCOVERY", true)) {
            StrategyDiscoveryEngine.StrategyDiscoveryResult discovery = new StrategyDiscoveryEngine().discover();
            steps.add("strategyDiscovery=" + discovery.summary());
        }

        Map<String, Candidate> candidates = seedCandidates();
        Map<String, ReplayAggregate> replay = readReplayReport();
        Map<String, OutcomeAggregate> outcomes = readOutcomes();
        Properties replayPolicy = loadProperties(replayPolicyPath);

        for (Map.Entry<String, ReplayAggregate> e : replay.entrySet()) {
            candidates.computeIfAbsent(e.getKey(), k -> new Candidate(k, "HISTORICAL_REPLAY", false,
                    "Replay-discovered strategy candidate from historical feature rows."));
        }
        for (Candidate c : candidates.values()) {
            c.replay = replay.getOrDefault(c.strategy, new ReplayAggregate(c.strategy));
            c.outcome = outcomes.getOrDefault(c.strategy, new OutcomeAggregate(c.strategy));
            c.replayPolicyMultiplier = parseDouble(replayPolicy.getProperty("strategyMultiplier." + c.strategy), 1.0);
            c.replayPolicyDisabled = Boolean.parseBoolean(replayPolicy.getProperty("disabledStrategy." + c.strategy, "false"));
            score(c);
        }

        OpenAiAdvisory advisory = runOpenAiResearch(candidates);
        PromotionGatekeeper.GateResult gate = writeCodeCandidates(candidates.values());
        int promoted = writePolicy(candidates.values(), gate);
        writeCandidatesCsv(candidates.values());
        writeReport(candidates.values(), steps, advisory, gate, promoted, System.currentTimeMillis() - started);

        return new Result(candidates.size(), promoted, codeProposalCount(candidates.values()), policyPath, reportPath,
                System.currentTimeMillis() - started);
    }

    private Map<String, Candidate> seedCandidates() {
        Map<String, Candidate> out = new LinkedHashMap<>();
        Set<String> implemented = implementedStrategies();
        for (String strategy : implemented) {
            out.put(strategy, new Candidate(strategy, "IMPLEMENTED_STRATEGY", true,
                    "Existing strategy implementation available in MasterStrategyEngine."));
        }
        addFeatureRecipe(out, "FEATURE_MOMENTUM", "Feature recipe for price and volume continuation.");
        addFeatureRecipe(out, "FEATURE_MEAN_REVERSION", "Feature recipe for panic recovery and reclaim behavior.");
        addFeatureRecipe(out, "FEATURE_VWAP_RECLAIM", "Feature recipe for VWAP reclaim continuation.");
        addFeatureRecipe(out, "FEATURE_SQUEEZE", "Feature recipe for volume expansion and short-squeeze pressure.");
        addFeatureRecipe(out, "FEATURE_FAILED_BREAKDOWN", "Feature recipe for failed breakdown recovery.");
        addFeatureRecipe(out, "FEATURE_NEGATIVE_NEWS_SHORT", "Feature recipe for fresh negative catalyst shorts.");
        readDiscoveredStrategies(out, implemented);
        return out;
    }

    private Set<String> implementedStrategies() {
        Set<String> out = new HashSet<>();
        try {
            for (TradingStrategy strategy : MasterStrategyEngine.defaultStrategies()) {
                if (strategy != null && strategy.name() != null && !strategy.name().isBlank()) {
                    out.add(normalize(strategy.name()));
                }
            }
        } catch (Exception e) {
            System.out.println("STRATEGY RESEARCH IMPLEMENTED LIST FAILED: " + e.getMessage());
        }
        return out;
    }

    private void addFeatureRecipe(Map<String, Candidate> out, String strategy, String hypothesis) {
        out.putIfAbsent(strategy, new Candidate(strategy, "FEATURE_RECIPE", true, hypothesis));
    }

    private void readDiscoveredStrategies(Map<String, Candidate> out, Set<String> implemented) {
        Properties p = loadProperties(discoveredPath);
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("strategy.")) {
                continue;
            }
            String strategy = normalize(p.getProperty(key));
            if (strategy.isBlank()) {
                continue;
            }
            out.putIfAbsent(strategy, new Candidate(strategy, "DISCOVERED_RECIPE", implemented.contains(strategy),
                    "Autonomous discovery recipe from feature/outcome correlations."));
        }
    }

    private void score(Candidate c) {
        ReplayAggregate r = c.replay;
        OutcomeAggregate o = c.outcome;
        double replayQualityScore = clamp(r.avgQuality() / 8.0, 0.0, 1.0);
        double replayEvScore = clamp(r.avgExpectedValue() / 4.0, 0.0, 1.0);
        double probabilityScore = clamp(r.avgTargetProbability(), 0.0, 1.0);
        double liveScore = 0.50;
        if (o.closed > 0) {
            liveScore = clamp(0.45 + o.winRate() * 0.25 + Math.min(0.20, o.expectancy() / 20.0)
                    + Math.min(0.15, Math.max(0.0, o.profitFactor() - 1.0) / 2.0), 0.0, 1.0);
        }
        c.simulationScore = clamp(replayQualityScore * 0.35 + replayEvScore * 0.35 + probabilityScore * 0.20 + liveScore * 0.10,
                0.0, 1.0);

        boolean enoughReplay = r.samples >= minReplaySamples;
        boolean replayPass = enoughReplay
                && r.avgQuality() >= minAvgQuality
                && r.avgExpectedValue() >= minAvgExpectedValue
                && r.avgTargetProbability() >= minAvgTargetProbability
                && c.simulationScore >= minSimulationScore
                && !c.replayPolicyDisabled;
        boolean liveVeto = o.closed >= minLiveSamplesForVeto && (o.expectancy() < 0.0 || o.profitFactor() < 0.95);

        if (replayPass && !liveVeto && c.implemented) {
            c.decision = "PROMOTE";
            c.multiplier = clamp(1.0 + (c.simulationScore - minSimulationScore) * 0.55, 1.02, maxPromotionMultiplier);
            c.reason = "Historical simulation passed with enough replay evidence.";
        } else if (replayPass && !liveVeto) {
            c.decision = "CODE_PROPOSAL";
            c.multiplier = 1.0;
            c.reason = "Simulation passed, but strategy is not implemented yet; staged code proposal only.";
        } else if (liveVeto || (enoughReplay && (r.avgExpectedValue() < 0.0 || r.avgQuality() < 0.5))) {
            c.decision = c.implemented ? "SHRINK" : "REJECT";
            c.multiplier = c.implemented ? 0.72 : 1.0;
            c.reason = liveVeto ? "Live outcomes veto promotion." : "Historical simulation evidence is weak.";
        } else {
            c.decision = "HOLD";
            c.multiplier = Math.min(1.0, Math.max(0.85, c.replayPolicyMultiplier));
            c.reason = enoughReplay ? "Simulation did not clear promotion thresholds." : "Not enough replay samples yet.";
        }
    }

    private OpenAiAdvisory runOpenAiResearch(Map<String, Candidate> candidates) {
        if (!envBool("OPENAI_STRATEGY_RESEARCH_ENABLED", true)) {
            return new OpenAiAdvisory(false, false, "disabled");
        }
        OpenAiClientManager client = OpenAiClientManager.getInstance();
        if (!client.isUsable()) {
            return new OpenAiAdvisory(false, false, "OpenAI unavailable");
        }
        List<Candidate> top = sorted(candidates.values());
        StringBuilder input = new StringBuilder();
        input.append("Review these strategy simulation candidates for a momentum scalping system. ");
        input.append("Suggest only entry/exit ideas that can be backtested before promotion. Do not weaken risk controls.\n");
        int limit = Math.min(20, top.size());
        for (int i = 0; i < limit; i++) {
            Candidate c = top.get(i);
            input.append(c.strategy)
                    .append(" decision=").append(c.decision)
                    .append(" score=").append(fmt(c.simulationScore))
                    .append(" replaySamples=").append(c.replay.samples)
                    .append(" avgEv=").append(fmt(c.replay.avgExpectedValue()))
                    .append(" avgP=").append(fmt(c.replay.avgTargetProbability()))
                    .append(" liveTrades=").append(c.outcome.closed)
                    .append(" liveExpectancy=").append(fmt(c.outcome.expectancy()))
                    .append(" implemented=").append(c.implemented)
                    .append('\n');
        }
        OpenAiClientManager.OpenAiResult result = client.requestJsonWithTimeout(
                "STRATEGY_RESEARCH_SIMULATION_REVIEW",
                env("OPENAI_STRATEGY_RESEARCH_MODEL", client.defaultModel()),
                "Return strict JSON with keys: promising_strategies, rejected_strategies, new_strategy_hypotheses, code_changes_required, tests_required.",
                input.toString(),
                envInt("OPENAI_STRATEGY_RESEARCH_MAX_OUTPUT_TOKENS", 1000),
                envInt("OPENAI_STRATEGY_RESEARCH_TIMEOUT_SECONDS", 180),
                envInt("OPENAI_STRATEGY_RESEARCH_MAX_RETRIES", 1)
        );
        writeString(openAiReviewPath, result.ok ? result.outputText : "{ \"ok\": false, \"error\": " + OpenAiClientManager.json(result.error) + " }");
        return new OpenAiAdvisory(true, result.ok, result.ok ? "review=" + openAiReviewPath : result.error);
    }

    private int writePolicy(Iterable<Candidate> candidates, PromotionGatekeeper.GateResult gate) {
        Properties p = new Properties();
        p.setProperty("updatedAtMs", Long.toString(System.currentTimeMillis()));
        p.setProperty("source", "strategy_research_simulation_promotion");
        p.setProperty("candidateReport", reportPath.toString());
        p.setProperty("candidateCsv", candidateCsvPath.toString());
        p.setProperty("codeCandidateRoot", candidateRoot.toString());
        p.setProperty("codeGatePassed", Boolean.toString(gate == null || gate.passed));
        int promoted = 0;
        for (Candidate c : candidates) {
            String prefix = "strategy." + c.strategy + ".";
            p.setProperty(prefix + "decision", c.decision);
            p.setProperty(prefix + "score", fmt(c.simulationScore));
            p.setProperty(prefix + "replaySamples", Integer.toString(c.replay.samples));
            p.setProperty(prefix + "avgReplayQuality", fmt(c.replay.avgQuality()));
            p.setProperty(prefix + "avgReplayExpectedValue", fmt(c.replay.avgExpectedValue()));
            p.setProperty(prefix + "avgReplayTargetProbability", fmt(c.replay.avgTargetProbability()));
            p.setProperty(prefix + "liveTrades", Integer.toString(c.outcome.closed));
            p.setProperty(prefix + "liveExpectancy", fmt(c.outcome.expectancy()));
            p.setProperty(prefix + "liveProfitFactor", fmt(c.outcome.profitFactor()));
            p.setProperty(prefix + "reason", c.reason);
            if ("PROMOTE".equals(c.decision)) {
                p.setProperty("promotedStrategy." + c.strategy, "true");
                p.setProperty("simulationStatus." + c.strategy, "PASSED");
                p.setProperty("strategyMultiplier." + c.strategy, fmt(c.multiplier));
                promoted++;
            } else if ("SHRINK".equals(c.decision)) {
                p.setProperty("simulationStatus." + c.strategy, "RISK_REDUCTION");
                p.setProperty("strategyMultiplier." + c.strategy, fmt(c.multiplier));
            } else if ("REJECT".equals(c.decision) && envBool("SIMULATION_POLICY_DISABLE_REJECTED_UNIMPLEMENTED", false)) {
                p.setProperty("disabledStrategy." + c.strategy, "true");
            }
        }
        try {
            Path parent = policyPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (java.io.OutputStream out = Files.newOutputStream(policyPath)) {
                p.store(out, "Simulation-promoted strategy policy");
            }
        } catch (IOException e) {
            System.out.println("SIMULATION STRATEGY POLICY WRITE FAILED: " + e.getMessage());
        }
        return promoted;
    }

    private PromotionGatekeeper.GateResult writeCodeCandidates(Iterable<Candidate> candidates) {
        int written = 0;
        StringBuilder manifest = new StringBuilder();
        manifest.append("STRATEGY RESEARCH CODE CANDIDATES\n");
        manifest.append("generatedAt=").append(Instant.now()).append('\n');
        manifest.append("mode=OFFLINE_STAGED_CANDIDATES\n\n");
        for (Candidate c : candidates) {
            if (!"CODE_PROPOSAL".equals(c.decision)) {
                continue;
            }
            written++;
            String className = toClassName(c.strategy) + "Strategy";
            Path javaPath = candidateRoot.resolve(className + ".java");
            writeString(javaPath, codeSkeleton(c, className));
            manifest.append("- ").append(c.strategy)
                    .append(" class=").append(className)
                    .append(" evidenceScore=").append(fmt(c.simulationScore))
                    .append(" replaySamples=").append(c.replay.samples)
                    .append(" avgEv=").append(fmt(c.replay.avgExpectedValue()))
                    .append('\n');
        }
        if (written == 0) {
            manifest.append("- none\n");
        }
        writeString(candidateRoot.resolve("implementation_manifest.txt"), manifest.toString());
        return new PromotionGatekeeper().inspectCandidateTree(candidateRoot);
    }

    private static String codeSkeleton(Candidate c, String className) {
        return "package com.bot.strategy.unified;\n\n"
                + "import com.bot.master.StrategyContext;\n"
                + "import com.bot.master.StrategySignal;\n\n"
                + "/**\n"
                + " * Candidate generated by StrategyResearchSimulationPromotionEngine.\n"
                + " * Evidence score: " + fmt(c.simulationScore) + "\n"
                + " * Replay samples: " + c.replay.samples + "\n"
                + " * Hypothesis: " + safeComment(c.hypothesis) + "\n"
                + " */\n"
                + "public class " + className + " extends AbstractUnifiedStrategy {\n"
                + "    @Override\n"
                + "    public String name() {\n"
                + "        return \"" + c.strategy + "\";\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public StrategySignal evaluate(StrategyContext context) {\n"
                + "        if (context == null) {\n"
                + "            return StrategySignal.hold(name(), \"UNKNOWN\", 0.0, \"No context supplied.\");\n"
                + "        }\n"
                + "        return hold(context, 0.0, \"Candidate requires implementation after simulation review.\");\n"
                + "    }\n"
                + "}\n";
    }

    private void writeCandidatesCsv(Iterable<Candidate> candidates) {
        StringBuilder b = new StringBuilder();
        b.append("generatedAt,strategy,source,implemented,replaySamples,avgQuality,avgExpectedValue,avgTargetProbability,liveTrades,liveExpectancy,liveProfitFactor,simulationScore,decision,multiplier,reason,hypothesis\n");
        String now = Instant.now().toString();
        for (Candidate c : sorted(candidates)) {
            b.append(csv(now)).append(',')
                    .append(csv(c.strategy)).append(',')
                    .append(csv(c.source)).append(',')
                    .append(c.implemented).append(',')
                    .append(c.replay.samples).append(',')
                    .append(fmt(c.replay.avgQuality())).append(',')
                    .append(fmt(c.replay.avgExpectedValue())).append(',')
                    .append(fmt(c.replay.avgTargetProbability())).append(',')
                    .append(c.outcome.closed).append(',')
                    .append(fmt(c.outcome.expectancy())).append(',')
                    .append(fmt(c.outcome.profitFactor())).append(',')
                    .append(fmt(c.simulationScore)).append(',')
                    .append(csv(c.decision)).append(',')
                    .append(fmt(c.multiplier)).append(',')
                    .append(csv(c.reason)).append(',')
                    .append(csv(c.hypothesis)).append('\n');
        }
        writeString(candidateCsvPath, b.toString());
    }

    private void writeReport(Iterable<Candidate> candidates, List<String> steps, OpenAiAdvisory advisory,
                             PromotionGatekeeper.GateResult gate, int promoted, long elapsedMs) {
        StringBuilder b = new StringBuilder();
        b.append("STRATEGY RESEARCH SIMULATION PROMOTION REPORT\n");
        b.append("generatedAt=").append(Instant.now()).append('\n');
        b.append("elapsedMs=").append(elapsedMs).append('\n');
        b.append("promoted=").append(promoted).append('\n');
        b.append("codeProposals=").append(codeProposalCount(candidates)).append('\n');
        b.append("policyPath=").append(policyPath).append('\n');
        b.append("candidateCsv=").append(candidateCsvPath).append('\n');
        b.append("codeCandidateRoot=").append(candidateRoot).append('\n');
        b.append("codeGate=").append(gate == null ? "not_run" : gate.summary()).append('\n');
        b.append("openAiAdvisory=attempted:").append(advisory.attempted)
                .append(" ok:").append(advisory.ok)
                .append(" message=").append(advisory.message).append('\n');
        b.append("thresholds=minReplaySamples=").append(minReplaySamples)
                .append(" minAvgQuality=").append(minAvgQuality)
                .append(" minAvgExpectedValue=").append(minAvgExpectedValue)
                .append(" minAvgTargetProbability=").append(minAvgTargetProbability)
                .append(" minSimulationScore=").append(minSimulationScore)
                .append('\n');
        b.append("\nsteps:\n");
        if (steps.isEmpty()) {
            b.append("- none\n");
        } else {
            for (String step : steps) {
                b.append("- ").append(step).append('\n');
            }
        }
        b.append("\ncandidates:\n");
        for (Candidate c : sorted(candidates)) {
            b.append("- ").append(c.strategy)
                    .append(" decision=").append(c.decision)
                    .append(" score=").append(fmt(c.simulationScore))
                    .append(" multiplier=").append(fmt(c.multiplier))
                    .append(" replaySamples=").append(c.replay.samples)
                    .append(" avgEv=").append(fmt(c.replay.avgExpectedValue()))
                    .append(" avgP=").append(fmt(c.replay.avgTargetProbability()))
                    .append(" liveTrades=").append(c.outcome.closed)
                    .append(" reason=").append(c.reason)
                    .append('\n');
        }
        writeString(reportPath, b.toString());
    }

    private Map<String, ReplayAggregate> readReplayReport() {
        Map<String, ReplayAggregate> out = new HashMap<>();
        if (!Files.exists(replayReportPath)) {
            return out;
        }
        try (BufferedReader reader = Files.newBufferedReader(replayReportPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return out;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("summary") || line.startsWith("strategy,")) {
                    break;
                }
                List<String> cols = parseCsv(line);
                String strategy = normalize(h.get(cols, "strategy"));
                if (strategy.isBlank() || "UNKNOWN".equals(strategy)) {
                    continue;
                }
                ReplayAggregate aggregate = out.computeIfAbsent(strategy, ReplayAggregate::new);
                aggregate.record(
                        parseDouble(h.get(cols, "quality"), 0.0),
                        parseDouble(h.get(cols, "expectedValue"), 0.0),
                        parseDouble(h.get(cols, "pTarget"), 0.0)
                );
            }
        } catch (IOException e) {
            System.out.println("SIMULATION RESEARCH REPLAY READ FAILED: " + e.getMessage());
        }
        return out;
    }

    private Map<String, OutcomeAggregate> readOutcomes() {
        Map<String, OutcomeAggregate> out = new HashMap<>();
        if (!Files.exists(outcomesPath)) {
            return out;
        }
        try (BufferedReader reader = Files.newBufferedReader(outcomesPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return out;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                String eventType = h.get(cols, "eventType");
                String strategy = TradeOutcomeTrainingFilter.normalizeStrategy(h.get(cols, "strategyName"));
                String syncedFromBroker = h.get(cols, "syncedFromBroker");
                if (!TradeOutcomeTrainingFilter.isTrainingEligible(eventType, strategy, syncedFromBroker)) {
                    continue;
                }
                out.computeIfAbsent(strategy, OutcomeAggregate::new)
                        .record(parseDouble(h.get(cols, "realizedPnlDollars"), 0.0));
            }
        } catch (IOException e) {
            System.out.println("SIMULATION RESEARCH OUTCOME READ FAILED: " + e.getMessage());
        }
        return out;
    }

    private static List<Candidate> sorted(Iterable<Candidate> candidates) {
        List<Candidate> list = new ArrayList<>();
        for (Candidate c : candidates) {
            list.add(c);
        }
        list.sort(Comparator.comparingDouble((Candidate c) -> c.simulationScore).reversed()
                .thenComparing(c -> c.strategy));
        return list;
    }

    private static int codeProposalCount(Iterable<Candidate> candidates) {
        int count = 0;
        for (Candidate c : candidates) {
            if ("CODE_PROPOSAL".equals(c.decision)) {
                count++;
            }
        }
        return count;
    }

    private static Properties loadProperties(Path path) {
        Properties p = new Properties();
        if (path == null || !Files.exists(path)) {
            return p;
        }
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            System.out.println("SIMULATION RESEARCH PROPERTIES READ FAILED: " + path + " " + e.getMessage());
        }
        return p;
    }

    private static void writeString(Path path, String text) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("SIMULATION RESEARCH WRITE FAILED: " + path + " " + e.getMessage());
        }
    }

    private static String toClassName(String strategy) {
        StringBuilder b = new StringBuilder();
        String[] parts = normalize(strategy).split("[^A-Z0-9]+");
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            b.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.length() == 0 ? "GeneratedResearch" : b.toString();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? ""
                : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String safeComment(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace("*/", "");
    }

    private static String csv(String value) {
        String v = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            value = 0.0;
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '"') {
                quoted = true;
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static final class CsvHeader {
        private final Map<String, Integer> indexes = new HashMap<>();

        CsvHeader(String header) {
            List<String> cols = parseCsv(header);
            for (int i = 0; i < cols.size(); i++) {
                indexes.put(cols.get(i).trim(), i);
            }
        }

        String get(List<String> cols, String name) {
            Integer idx = indexes.get(name);
            if (idx == null || idx < 0 || idx >= cols.size()) {
                return "";
            }
            return cols.get(idx);
        }
    }

    private static final class Candidate {
        final String strategy;
        final String source;
        final boolean implemented;
        final String hypothesis;
        ReplayAggregate replay = new ReplayAggregate("");
        OutcomeAggregate outcome = new OutcomeAggregate("");
        double replayPolicyMultiplier = 1.0;
        boolean replayPolicyDisabled;
        double simulationScore;
        double multiplier = 1.0;
        String decision = "HOLD";
        String reason = "";

        Candidate(String strategy, String source, boolean implemented, String hypothesis) {
            this.strategy = normalize(strategy);
            this.source = source == null ? "UNKNOWN" : source;
            this.implemented = implemented;
            this.hypothesis = hypothesis == null ? "" : hypothesis;
        }
    }

    private static final class ReplayAggregate {
        final String strategy;
        int samples;
        double totalQuality;
        double totalExpectedValue;
        double totalTargetProbability;

        ReplayAggregate(String strategy) {
            this.strategy = strategy;
        }

        void record(double quality, double expectedValue, double targetProbability) {
            samples++;
            totalQuality += quality;
            totalExpectedValue += expectedValue;
            totalTargetProbability += targetProbability;
        }

        double avgQuality() {
            return samples <= 0 ? 0.0 : totalQuality / samples;
        }

        double avgExpectedValue() {
            return samples <= 0 ? 0.0 : totalExpectedValue / samples;
        }

        double avgTargetProbability() {
            return samples <= 0 ? 0.0 : totalTargetProbability / samples;
        }
    }

    private static final class OutcomeAggregate {
        final String strategy;
        int closed;
        int wins;
        double pnl;
        double grossProfit;
        double grossLoss;

        OutcomeAggregate(String strategy) {
            this.strategy = strategy;
        }

        void record(double value) {
            closed++;
            pnl += value;
            if (value > 0.0) {
                wins++;
                grossProfit += value;
            } else if (value < 0.0) {
                grossLoss += Math.abs(value);
            }
        }

        double winRate() {
            return closed <= 0 ? 0.0 : (double) wins / closed;
        }

        double expectancy() {
            return closed <= 0 ? 0.0 : pnl / closed;
        }

        double profitFactor() {
            if (grossLoss <= 0.0) {
                return grossProfit > 0.0 ? 99.0 : 0.0;
            }
            return grossProfit / grossLoss;
        }
    }

    private static final class OpenAiAdvisory {
        final boolean attempted;
        final boolean ok;
        final String message;

        OpenAiAdvisory(boolean attempted, boolean ok, String message) {
            this.attempted = attempted;
            this.ok = ok;
            this.message = message == null ? "" : message;
        }
    }

    public static final class Result {
        public final int candidates;
        public final int promoted;
        public final int codeProposals;
        public final Path policyPath;
        public final Path reportPath;
        public final long elapsedMs;

        Result(int candidates, int promoted, int codeProposals, Path policyPath, Path reportPath, long elapsedMs) {
            this.candidates = candidates;
            this.promoted = promoted;
            this.codeProposals = codeProposals;
            this.policyPath = policyPath;
            this.reportPath = reportPath;
            this.elapsedMs = elapsedMs;
        }

        public String summary() {
            return "candidates=" + candidates +
                    " promoted=" + promoted +
                    " codeProposals=" + codeProposals +
                    " policy=" + policyPath +
                    " report=" + reportPath +
                    " elapsedMs=" + elapsedMs;
        }
    }
}
