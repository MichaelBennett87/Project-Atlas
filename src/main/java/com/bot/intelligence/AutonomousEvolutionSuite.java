package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * Full autonomous evolution suite.
 *
 * This is the after-hours optimizer that sits above the bounded source
 * tournament. It does not place orders. It reads the live journals, generates a
 * large population of candidate policy/ensemble configurations, scores them
 * against long-memory and recent data, stress-tests them with Monte Carlo
 * sampling, keeps multiple strategy "species" alive, and automatically promotes
 * the champion into logs/ai_policy.properties.
 *
 * The live bot reads that policy file through AdaptiveTradingPolicyStore.
 */
public class AutonomousEvolutionSuite {

    private final Config config;

    public AutonomousEvolutionSuite() {
        this(Config.fromEnv());
    }

    public AutonomousEvolutionSuite(Config config) {
        this.config = config == null ? Config.fromEnv() : config;
    }

    public EvolutionResult runFullEvolution() {
        AutonomousTournamentEngine.OfflineGuard.assertSafeToMutate();

        EvolutionData data = EvolutionData.load(config.featurePath, config.outcomePath);
        FilesUtil.ensureDir(config.generationRoot);

        List<EvolvedPolicyCandidate> population = buildPopulation(data);
        for (EvolvedPolicyCandidate candidate : population) {
            candidate.score(data, config);
        }

        population.sort(Comparator.comparingDouble((EvolvedPolicyCandidate c) -> c.fitness).reversed());
        EvolvedPolicyCandidate champion = population.isEmpty()
                ? EvolvedPolicyCandidate.baseline("empty_baseline")
                : population.get(0);

        Map<String, EvolvedPolicyCandidate> championPortfolio = selectChampionPortfolio(population, data);
        boolean enoughData = data.featureRows >= config.minFeatureRows || data.closedTrades >= config.minClosedTrades;
        boolean championPassedGuardrails =
                champion.selectedRows >= config.minSelectedRowsToPromote &&
                champion.profitFactor >= config.minProfitFactorToPromote &&
                champion.monteCarloP05 >= config.minMonteCarloP05ToPromote;
        boolean promoted = enoughData && champion.fitness > config.minFitnessToPromote && championPassedGuardrails;

        Path generationDir = config.generationRoot.resolve("generation_" + System.currentTimeMillis());
        writeGenerationArtifacts(generationDir, population, champion, data, promoted);

        if (promoted || config.promoteEvenWhenDataSparse) {
            backupExistingPolicy(config.aiPolicyPath);
            backupExistingPolicy(config.policyPortfolioPath);
            writeAdaptivePolicy(config.aiPolicyPath, champion);
            writePolicyPortfolio(config.policyPortfolioPath, championPortfolio);
            writePolicyPortfolio(generationDir.resolve("ai_policy_portfolio.properties"), championPortfolio);
        }

        AutonomousTournamentEngine.TournamentResult sourceTournament = null;
        boolean sourceTournamentRan = false;
        String sourceTournamentError = "";
        if (config.runSourceTournament) {
            try {
                sourceTournament = new AutonomousTournamentEngine().run();
                sourceTournamentRan = true;
            } catch (Exception e) {
                sourceTournamentError = e.getMessage();
            }
        }

        writeMasterReport(generationDir, population, champion, data, promoted, sourceTournamentRan, sourceTournament, sourceTournamentError);

        return new EvolutionResult(
                promoted || config.promoteEvenWhenDataSparse,
                champion.name,
                champion.species,
                champion.regime,
                champion.fitness,
                data.featureRows,
                data.closedTrades,
                generationDir,
                sourceTournamentRan,
                sourceTournament == null ? false : sourceTournament.promoted
        );
    }

    private List<EvolvedPolicyCandidate> buildPopulation(EvolutionData data) {
        List<EvolvedPolicyCandidate> out = new ArrayList<>();
        EvolvedPolicyCandidate baseline = EvolvedPolicyCandidate.baseline("baseline_policy");
        out.add(baseline);

        String[] species = new String[]{
                "MOMENTUM",
                "MEAN_REVERSION",
                "VWAP_RECLAIM",
                "SQUEEZE",
                "SHORT_ALPHA",
                "FAILED_BREAKDOWN",
                "DEFENSIVE_ENSEMBLE",
                "AGGRESSIVE_ENSEMBLE",
                "BALANCED_ENSEMBLE"
        };

        Regime regime = RegimeClassifier.classify(data);
        Random random = new Random(config.randomSeed);

        int perSpecies = Math.max(1, config.populationSize / species.length);
        int id = 1;
        for (String s : species) {
            EvolvedPolicyCandidate seed = baseline.copy("seed_" + s);
            seed.species = s;
            seed.regime = regime.name();
            seed.applySpeciesBias();
            seed.reason = "species seed " + s + " regime=" + regime.name();
            seed.clamp();
            out.add(seed);

            for (int i = 0; i < perSpecies; i++) {
                EvolvedPolicyCandidate c = seed.copy("candidate_" + pad(id++) + "_" + s);
                c.species = s;
                c.regime = regime.name();
                c.mutate(random, data, config);
                c.clamp();
                out.add(c);
            }
        }

        while (out.size() < config.populationSize + 1) {
            EvolvedPolicyCandidate c = baseline.copy("candidate_" + pad(id++) + "_BALANCED_ENSEMBLE");
            c.species = "BALANCED_ENSEMBLE";
            c.regime = regime.name();
            c.mutate(random, data, config);
            c.clamp();
            out.add(c);
        }

        return out;
    }


    private Map<String, EvolvedPolicyCandidate> selectChampionPortfolio(List<EvolvedPolicyCandidate> population, EvolutionData data) {
        Map<String, EvolvedPolicyCandidate> winners = new LinkedHashMap<>();
        if (population == null || population.isEmpty()) {
            return winners;
        }
        String[] regimes = new String[]{"LOW_DATA", "HIGH_RVOL_MOMENTUM", "CHOPPY_MEAN_REVERSION", "LOW_VOLUME", "NEWS_CATALYST", "NEGATIVE_NEWS", "BALANCED"};
        String[] species = new String[]{"MOMENTUM", "MEAN_REVERSION", "VWAP_RECLAIM", "SQUEEZE", "SHORT_ALPHA", "FAILED_BREAKDOWN", "DEFENSIVE_ENSEMBLE", "AGGRESSIVE_ENSEMBLE", "BALANCED_ENSEMBLE"};
        for (String regime : regimes) {
            for (String sp : species) {
                EvolvedPolicyCandidate best = null;
                for (EvolvedPolicyCandidate c : population) {
                    if (c == null) continue;
                    if (!sp.equals(c.species)) continue;
                    double score = c.fitness + (regime.equals(c.regime) ? 10.0 : 0.0) + regimeSpeciesCompatibility(regime, sp);
                    if (best == null || score > best.fitness + (regime.equals(best.regime) ? 10.0 : 0.0) + regimeSpeciesCompatibility(regime, best.species)) {
                        best = c;
                    }
                }
                if (best != null) {
                    winners.put(regime + "|" + sp, best);
                }
            }
        }
        // Add durable global fallbacks.
        winners.put("ANY|BALANCED_ENSEMBLE", population.get(0));
        for (String sp : species) {
            for (EvolvedPolicyCandidate c : population) {
                if (sp.equals(c.species)) {
                    winners.putIfAbsent("ANY|" + sp, c);
                    break;
                }
            }
        }
        return winners;
    }

    private static double regimeSpeciesCompatibility(String regime, String species) {
        if ("HIGH_RVOL_MOMENTUM".equals(regime) && ("MOMENTUM".equals(species) || "SQUEEZE".equals(species))) return 6.0;
        if ("CHOPPY_MEAN_REVERSION".equals(regime) && ("MEAN_REVERSION".equals(species) || "VWAP_RECLAIM".equals(species))) return 6.0;
        if ("LOW_VOLUME".equals(regime) && ("DEFENSIVE_ENSEMBLE".equals(species) || "SQUEEZE".equals(species))) return 5.0;
        if ("NEWS_CATALYST".equals(regime) && ("MOMENTUM".equals(species) || "BALANCED_ENSEMBLE".equals(species))) return 5.0;
        if ("NEGATIVE_NEWS".equals(regime) && "SHORT_ALPHA".equals(species)) return 7.0;
        return 0.0;
    }

    private void writePolicyPortfolio(Path path, Map<String, EvolvedPolicyCandidate> portfolio) {
        try {
            FilesUtil.ensureParent(path);
            Properties p = new Properties();
            p.setProperty("updatedAtMs", Long.toString(System.currentTimeMillis()));
            p.setProperty("portfolioMode", "REGIME_AWARE_MULTI_CHAMPION");
            List<String> slots = new ArrayList<>();
            int i = 0;
            for (Map.Entry<String, EvolvedPolicyCandidate> e : portfolio.entrySet()) {
                EvolvedPolicyCandidate c = e.getValue();
                if (c == null) continue;
                String slot = "s" + (++i);
                slots.add(slot);
                String[] parts = e.getKey().split("\\|", 2);
                String regime = parts.length > 0 ? parts[0] : "ANY";
                String species = parts.length > 1 ? parts[1] : c.species;
                String prefix = "slot." + slot + ".";
                p.setProperty(prefix + "evolution.regime", regime);
                p.setProperty(prefix + "evolution.species", species);
                p.setProperty(prefix + "evolution.championName", c.name);
                p.setProperty(prefix + "evolution.fitness", fmtRaw(c.fitness));
                p.setProperty(prefix + "evolution.profitFactor", fmtRaw(c.profitFactor));
                p.setProperty(prefix + "evolution.capitalEfficiency", fmtRaw(c.capitalEfficiency));
                p.setProperty(prefix + "evolution.sharpeLike", fmtRaw(c.sharpeLike));
                p.setProperty(prefix + "liveTradingAllowed", Boolean.toString(true));
                p.setProperty(prefix + "minProbabilityTarget", fmtRaw(c.minProbabilityTarget));
                p.setProperty(prefix + "minExpectedValuePercent", fmtRaw(c.minExpectedValuePercent));
                p.setProperty(prefix + "minProposalScore", fmtRaw(c.minProposalScore));
                p.setProperty(prefix + "maxStopProbability", fmtRaw(c.maxStopProbability));
                p.setProperty(prefix + "riskFractionPerTrade", fmtRaw(c.riskFractionPerTrade));
                for (Map.Entry<String, Double> m : c.strategyMultipliers.entrySet()) {
                    p.setProperty(prefix + "strategyMultiplier." + m.getKey(), fmtRaw(m.getValue()));
                }
            }
            p.setProperty("slots", String.join(",", slots));
            try (var out = Files.newOutputStream(path)) {
                p.store(out, "Auto-promoted regime-aware multi-champion policy portfolio.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write policy portfolio " + path + ": " + e.getMessage(), e);
        }
    }

    private void writeAdaptivePolicy(Path path, EvolvedPolicyCandidate c) {
        try {
            FilesUtil.ensureParent(path);
            Properties p = new Properties();
            p.setProperty("updatedAtMs", Long.toString(System.currentTimeMillis()));
            p.setProperty("liveTradingAllowed", Boolean.toString(true));
            p.setProperty("minProbabilityTarget", fmtRaw(c.minProbabilityTarget));
            p.setProperty("minExpectedValuePercent", fmtRaw(c.minExpectedValuePercent));
            p.setProperty("minProposalScore", fmtRaw(c.minProposalScore));
            p.setProperty("maxStopProbability", fmtRaw(c.maxStopProbability));
            p.setProperty("riskFractionPerTrade", fmtRaw(c.riskFractionPerTrade));

            for (Map.Entry<String, Double> e : c.strategyMultipliers.entrySet()) {
                p.setProperty("strategyMultiplier." + e.getKey(), fmtRaw(e.getValue()));
            }

            p.setProperty("evolution.championName", c.name);
            p.setProperty("evolution.species", c.species);
            p.setProperty("evolution.regime", c.regime);
            p.setProperty("evolution.fitness", fmtRaw(c.fitness));
            p.setProperty("evolution.reason", c.reason == null ? "" : c.reason);

            try (var out = Files.newOutputStream(path)) {
                p.store(out, "Auto-promoted by AutonomousEvolutionSuite after offline tournament and Monte Carlo validation.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write adaptive policy " + path + ": " + e.getMessage(), e);
        }
    }

    private void writeGenerationArtifacts(Path dir, List<EvolvedPolicyCandidate> population, EvolvedPolicyCandidate champion, EvolutionData data, boolean promoted) {
        FilesUtil.ensureDir(dir);

        StringBuilder csv = new StringBuilder();
        csv.append("rank,name,species,regime,fitness,longScore,recentScore,monteCarloP05,maxDrawdownPenalty,selectedRows,estimatedWinRate,profitFactor,capitalEfficiency,sharpeLike,avgReturn,minP,minEV,minProposal,maxStop,risk,reason\n");
        for (int i = 0; i < population.size(); i++) {
            EvolvedPolicyCandidate c = population.get(i);
            csv.append(i + 1).append(',')
                    .append(csv(c.name)).append(',')
                    .append(csv(c.species)).append(',')
                    .append(csv(c.regime)).append(',')
                    .append(fmt(c.fitness)).append(',')
                    .append(fmt(c.longMemoryScore)).append(',')
                    .append(fmt(c.recentScore)).append(',')
                    .append(fmt(c.monteCarloP05)).append(',')
                    .append(fmt(c.maxDrawdownPenalty)).append(',')
                    .append(c.selectedRows).append(',')
                    .append(fmt(c.estimatedWinRate)).append(',')
                    .append(fmt(c.profitFactor)).append(',')
                    .append(fmt(c.capitalEfficiency)).append(',')
                    .append(fmt(c.sharpeLike)).append(',')
                    .append(fmt(c.averageReturn)).append(',')
                    .append(fmt(c.minProbabilityTarget)).append(',')
                    .append(fmt(c.minExpectedValuePercent)).append(',')
                    .append(fmt(c.minProposalScore)).append(',')
                    .append(fmt(c.maxStopProbability)).append(',')
                    .append(fmt(c.riskFractionPerTrade)).append(',')
                    .append(csv(c.reason)).append('\n');
        }
        FilesUtil.writeString(dir.resolve("evolved_policy_rankings.csv"), csv.toString());

        int keep = Math.min(config.keepPolicyFiles, population.size());
        for (int i = 0; i < keep; i++) {
            EvolvedPolicyCandidate c = population.get(i);
            FilesUtil.writeString(dir.resolve(c.name + "_ai_policy.properties"), c.toPropertiesText());
        }

        String summary = "AUTONOMOUS EVOLUTION SUITE SUMMARY\n" +
                "generatedAt=" + Instant.now() + "\n" +
                "mode=OFFLINE_AUTONOMOUS_NO_HUMAN_APPROVAL\n" +
                "populationSize=" + population.size() + "\n" +
                "featureRows=" + data.featureRows + "\n" +
                "closedTrades=" + data.closedTrades + "\n" +
                "detectedRegime=" + RegimeClassifier.classify(data).name() + "\n" +
                "champion=" + champion.name + "\n" +
                "championSpecies=" + champion.species + "\n" +
                "championFitness=" + fmt(champion.fitness) + "\n" +
                "promotedToAiPolicy=" + promoted + "\n" +
                "promotionGuard.minSelectedRows=" + config.minSelectedRowsToPromote + "\n" +
                "promotionGuard.minProfitFactor=" + fmt(config.minProfitFactorToPromote) + "\n" +
                "promotionGuard.minMonteCarloP05=" + fmt(config.minMonteCarloP05ToPromote) + "\n" +
                "championPassedGuardrails=" + (champion.selectedRows >= config.minSelectedRowsToPromote && champion.profitFactor >= config.minProfitFactorToPromote && champion.monteCarloP05 >= config.minMonteCarloP05ToPromote) + "\n" +
                "aiPolicyPath=" + config.aiPolicyPath + "\n" +
                "policyPortfolioPath=" + config.policyPortfolioPath + "\n";
        FilesUtil.writeString(dir.resolve("summary.txt"), summary);
    }

    private void writeMasterReport(Path dir, List<EvolvedPolicyCandidate> population, EvolvedPolicyCandidate champion, EvolutionData data,
                                   boolean promoted, boolean sourceTournamentRan,
                                   AutonomousTournamentEngine.TournamentResult sourceTournament,
                                   String sourceTournamentError) {
        StringBuilder b = new StringBuilder();
        b.append("AUTONOMOUS FULL EVOLUTION REPORT\n");
        b.append("generatedAt=").append(Instant.now()).append('\n');
        b.append("marketRegime=").append(RegimeClassifier.classify(data).name()).append('\n');
        b.append("featureRows=").append(data.featureRows).append('\n');
        b.append("closedTrades=").append(data.closedTrades).append('\n');
        b.append("realizedPnl=").append(fmt(data.realizedPnl)).append('\n');
        b.append("realizedWinRate=").append(fmt(data.winRate())).append('\n');
        b.append("populationSize=").append(population.size()).append('\n');
        b.append("champion=").append(champion.name).append('\n');
        b.append("championSpecies=").append(champion.species).append('\n');
        b.append("championRegime=").append(champion.regime).append('\n');
        b.append("championFitness=").append(fmt(champion.fitness)).append('\n');
        b.append("championLongMemoryScore=").append(fmt(champion.longMemoryScore)).append('\n');
        b.append("championRecentScore=").append(fmt(champion.recentScore)).append('\n');
        b.append("championMonteCarloP05=").append(fmt(champion.monteCarloP05)).append('\n');
        b.append("championSelectedRows=").append(champion.selectedRows).append('\n');
        b.append("championProfitFactor=").append(fmt(champion.profitFactor)).append('\n');
        b.append("promotionGuard.minSelectedRows=").append(config.minSelectedRowsToPromote).append('\n');
        b.append("promotionGuard.minProfitFactor=").append(fmt(config.minProfitFactorToPromote)).append('\n');
        b.append("promotionGuard.minMonteCarloP05=").append(fmt(config.minMonteCarloP05ToPromote)).append('\n');
        b.append("championCapitalEfficiency=").append(fmt(champion.capitalEfficiency)).append('\n');
        b.append("championSharpeLike=").append(fmt(champion.sharpeLike)).append('\n');
        b.append("promotedPolicy=").append(promoted).append('\n');
        b.append("policyPortfolioPath=").append(config.policyPortfolioPath).append('\n');
        b.append("sourceTournamentRan=").append(sourceTournamentRan).append('\n');
        if (sourceTournament != null) {
            b.append("sourceTournamentPromoted=").append(sourceTournament.promoted).append('\n');
            b.append("sourceTournamentChampion=").append(sourceTournament.championName).append('\n');
            b.append("sourceTournamentChampionFitness=").append(fmt(sourceTournament.championFitness)).append('\n');
        }
        if (!sourceTournamentError.isBlank()) {
            b.append("sourceTournamentError=").append(sourceTournamentError).append('\n');
        }
        b.append("generationDir=").append(dir).append('\n');
        b.append('\n').append("TOP 20 CANDIDATES\n");
        int top = Math.min(20, population.size());
        for (int i = 0; i < top; i++) {
            EvolvedPolicyCandidate c = population.get(i);
            b.append(i + 1).append(". ")
                    .append(c.name)
                    .append(" species=").append(c.species)
                    .append(" fitness=").append(fmt(c.fitness))
                    .append(" long=").append(fmt(c.longMemoryScore))
                    .append(" recent=").append(fmt(c.recentScore))
                    .append(" mcP05=").append(fmt(c.monteCarloP05))
                    .append(" selected=").append(c.selectedRows)
                    .append(" winRate=").append(fmt(c.estimatedWinRate))
                    .append(" reason=").append(c.reason)
                    .append('\n');
        }
        FilesUtil.writeString(config.reportPath, b.toString());
    }

    private static void backupExistingPolicy(Path path) {
        try {
            if (path == null || !Files.exists(path)) {
                return;
            }
            Path backup = path.resolveSibling(path.getFileName().toString() + ".bak." + System.currentTimeMillis());
            Files.copy(path, backup);
        } catch (Exception e) {
            System.out.println("POLICY BACKUP FAILED: " + path + " " + e.getMessage());
        }
    }

    public static final class Config {
        final Path featurePath;
        final Path outcomePath;
        final Path aiPolicyPath;
        final Path policyPortfolioPath;
        final Path reportPath;
        final Path generationRoot;
        final int populationSize;
        final int keepPolicyFiles;
        final int minFeatureRows;
        final int minClosedTrades;
        final int monteCarloRuns;
        final long randomSeed;
        final boolean runSourceTournament;
        final boolean promoteEvenWhenDataSparse;
        final double minFitnessToPromote;
        final int minSelectedRowsToPromote;
        final double minProfitFactorToPromote;
        final double minMonteCarloP05ToPromote;

        Config(Path featurePath, Path outcomePath, Path aiPolicyPath, Path policyPortfolioPath, Path reportPath, Path generationRoot,
               int populationSize, int keepPolicyFiles, int minFeatureRows, int minClosedTrades,
               int monteCarloRuns, long randomSeed, boolean runSourceTournament,
               boolean promoteEvenWhenDataSparse, double minFitnessToPromote,
               int minSelectedRowsToPromote, double minProfitFactorToPromote, double minMonteCarloP05ToPromote) {
            this.featurePath = featurePath;
            this.outcomePath = outcomePath;
            this.aiPolicyPath = aiPolicyPath;
            this.policyPortfolioPath = policyPortfolioPath;
            this.reportPath = reportPath;
            this.generationRoot = generationRoot;
            this.populationSize = Math.max(50, populationSize);
            this.keepPolicyFiles = Math.max(5, keepPolicyFiles);
            this.minFeatureRows = Math.max(25, minFeatureRows);
            this.minClosedTrades = Math.max(3, minClosedTrades);
            this.monteCarloRuns = Math.max(100, monteCarloRuns);
            this.randomSeed = randomSeed;
            this.runSourceTournament = runSourceTournament;
            this.promoteEvenWhenDataSparse = promoteEvenWhenDataSparse;
            this.minFitnessToPromote = minFitnessToPromote;
            this.minSelectedRowsToPromote = Math.max(1, minSelectedRowsToPromote);
            this.minProfitFactorToPromote = Math.max(0.0, minProfitFactorToPromote);
            this.minMonteCarloP05ToPromote = minMonteCarloP05ToPromote;
        }

        static Config fromEnv() {
            return new Config(
                    Path.of(env("FEATURE_JOURNAL_PATH", "logs/market_features.csv")),
                    Path.of(env("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv")),
                    Path.of(env("AI_POLICY_PATH", "logs/ai_policy.properties")),
                    Path.of(env("AI_POLICY_PORTFOLIO_PATH", "logs/ai_policy_portfolio.properties")),
                    Path.of(env("AI_FULL_EVOLUTION_REPORT_PATH", "logs/ai_full_evolution_report.txt")),
                    Path.of(env("AI_FULL_EVOLUTION_DIR", "logs/autonomous_evolution")),
                    intEnv("AI_EVOLUTION_POPULATION_SIZE", 500),
                    intEnv("AI_EVOLUTION_KEEP_POLICY_FILES", 50),
                    intEnv("AI_MIN_FEATURE_ROWS_BEFORE_POLICY_PROMOTION", 250),
                    intEnv("AI_MIN_CLOSED_TRADES_BEFORE_POLICY_PROMOTION", 20),
                    intEnv("AI_FULL_EVOLUTION_MONTE_CARLO_RUNS", 1000),
                    longEnv("AI_EVOLUTION_RANDOM_SEED", System.currentTimeMillis()),
                    boolEnv("AI_RUN_SOURCE_TOURNAMENT", true),
                    boolEnv("AI_PROMOTE_EVEN_WHEN_DATA_SPARSE", false),
                    doubleEnv("AI_MIN_FULL_EVOLUTION_FITNESS_TO_PROMOTE", -999999.0),
                    intEnv("AI_MIN_SELECTED_ROWS_TO_PROMOTE", 8),
                    doubleEnv("AI_MIN_PROFIT_FACTOR_TO_PROMOTE", 0.95),
                    doubleEnv("AI_MIN_MONTE_CARLO_P05_TO_PROMOTE", -25.0)
            );
        }
    }

    static final class EvolutionData {
        final List<FeatureRow> features = new ArrayList<>();
        final List<OutcomeRow> outcomes = new ArrayList<>();
        final Map<String, StrategyStats> strategyStats = new LinkedHashMap<>();
        int featureRows;
        int closedTrades;
        double realizedPnl;

        static EvolutionData load(Path featurePath, Path outcomePath) {
            EvolutionData d = new EvolutionData();
            d.loadFeatures(featurePath);
            d.loadOutcomes(outcomePath);
            return d;
        }

        void loadFeatures(Path path) {
            if (!Files.exists(path)) {
                return;
            }
            try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String header = r.readLine();
                if (header == null) {
                    return;
                }
                CsvHeader h = new CsvHeader(header);
                String line;
                int maxRows = intEnv("AI_FULL_EVOLUTION_MAX_FEATURE_ROWS_IN_MEMORY", 50000);
                while ((line = r.readLine()) != null) {
                    FeatureRow row = FeatureRow.from(h, Csv.parse(line), line);
                    if (features.size() < maxRows) {
                        features.add(row);
                    }
                    featureRows++;
                }
            } catch (IOException e) {
                System.out.println("FULL EVOLUTION FEATURE LOAD FAILED: " + e.getMessage());
            }
        }

        void loadOutcomes(Path path) {
            if (!Files.exists(path)) {
                return;
            }
            try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String header = r.readLine();
                if (header == null) {
                    return;
                }
                CsvHeader h = new CsvHeader(header);
                String line;
                int maxRows = intEnv("AI_FULL_EVOLUTION_MAX_OUTCOME_ROWS_IN_MEMORY", 50000);
                while ((line = r.readLine()) != null) {
                    OutcomeRow o = OutcomeRow.from(h, Csv.parse(line));
                    if (!o.isClosed()) {
                        continue;
                    }
                    if (outcomes.size() < maxRows) {
                        outcomes.add(o);
                    }
                    closedTrades++;
                    realizedPnl += o.realizedPnl;
                    strategyStats.computeIfAbsent(o.strategyName, k -> new StrategyStats()).add(o.realizedPnl);
                }
            } catch (IOException e) {
                System.out.println("FULL EVOLUTION OUTCOME LOAD FAILED: " + e.getMessage());
            }
        }

        double winRate() {
            if (closedTrades <= 0) {
                return 0.0;
            }
            int wins = 0;
            for (OutcomeRow o : outcomes) {
                if (o.realizedPnl > 0) {
                    wins++;
                }
            }
            return wins / (double) closedTrades;
        }

        List<FeatureRow> recentFeatures(double fraction) {
            int size = features.size();
            if (size <= 0) {
                return List.of();
            }
            int start = Math.max(0, (int) Math.floor(size * (1.0 - fraction)));
            return features.subList(start, size);
        }

        double averageTradePnl() {
            return closedTrades <= 0 ? 0.0 : realizedPnl / closedTrades;
        }
    }

    enum Regime {
        LOW_DATA,
        HIGH_RVOL_MOMENTUM,
        CHOPPY_MEAN_REVERSION,
        LOW_VOLUME,
        NEWS_CATALYST,
        NEGATIVE_NEWS,
        BALANCED
    }

    static final class RegimeClassifier {
        static Regime classify(EvolutionData data) {
            if (data == null || data.featureRows < 25) {
                return Regime.LOW_DATA;
            }
            double avgRvol = 0.0;
            double avgAbsReturn = 0.0;
            double avgCatalyst = 0.0;
            int rvolCount = 0;
            int catalystCount = 0;
            for (FeatureRow f : data.features) {
                avgRvol += Math.max(f.rvol5, f.rvol20);
                avgAbsReturn += Math.abs(f.return3Bars);
                if (f.catalystScore > 0.0 || f.sentimentNet != 0.0 || f.freshnessSeconds < 600) {
                    catalystCount++;
                }
                if (f.hasRvol()) {
                    rvolCount++;
                }
                avgCatalyst += f.catalystScore;
            }
            avgRvol /= Math.max(1, data.featureRows);
            avgAbsReturn /= Math.max(1, data.featureRows);
            avgCatalyst /= Math.max(1, data.featureRows);
            double rvolCoverage = rvolCount / (double) Math.max(1, data.featureRows);
            double catalystCoverage = catalystCount / (double) Math.max(1, data.featureRows);

            if (rvolCoverage < 0.20) {
                return Regime.LOW_VOLUME;
            }
            if (avgRvol >= 1.8 && avgAbsReturn > 0.003) {
                return Regime.HIGH_RVOL_MOMENTUM;
            }
            int negativeFresh = 0;
            for (FeatureRow f : data.features) {
                if (f.sentimentNet <= -0.25 && f.freshnessSeconds <= doubleEnv("AI_NEGATIVE_NEWS_SHORT_MAX_FRESHNESS_SECONDS", 900.0)) {
                    negativeFresh++;
                }
            }
            if (negativeFresh / (double) Math.max(1, data.featureRows) > 0.12) {
                return Regime.NEGATIVE_NEWS;
            }
            if (catalystCoverage > 0.35 || avgCatalyst > 0.25) {
                return Regime.NEWS_CATALYST;
            }
            if (avgAbsReturn < 0.0015) {
                return Regime.CHOPPY_MEAN_REVERSION;
            }
            return Regime.BALANCED;
        }
    }

    static final class EvolvedPolicyCandidate {
        String name;
        String species = "BALANCED_ENSEMBLE";
        String regime = "UNKNOWN";
        String reason = "baseline";

        double minProbabilityTarget = 0.62;
        double minExpectedValuePercent = 1.10;
        double minProposalScore = 0.35;
        double maxStopProbability = 0.48;
        double riskFractionPerTrade = 0.0025;
        final Map<String, Double> strategyMultipliers = new LinkedHashMap<>();

        double fitness;
        double longMemoryScore;
        double recentScore;
        double monteCarloP05;
        double maxDrawdownPenalty;
        int selectedRows;
        double estimatedWinRate;
        double profitFactor;
        double capitalEfficiency;
        double sharpeLike;
        double averageReturn;

        static EvolvedPolicyCandidate baseline(String name) {
            EvolvedPolicyCandidate c = new EvolvedPolicyCandidate();
            c.name = name;
            c.strategyMultipliers.put("MARKET_INTELLIGENCE_AI", 1.0);
            c.strategyMultipliers.put("FEATURE_MOMENTUM", 1.0);
            c.strategyMultipliers.put("FEATURE_MEAN_REVERSION", 1.0);
            c.strategyMultipliers.put("FEATURE_VWAP_RECLAIM", 1.0);
            c.strategyMultipliers.put("FEATURE_SQUEEZE", 1.0);
            c.strategyMultipliers.put("FEATURE_FAILED_BREAKDOWN", 1.0);
            c.strategyMultipliers.put("PANIC_REVERSAL", 1.0);
            c.strategyMultipliers.put("MOMENTUM_NEWS_RUNNER", 1.0);
            c.strategyMultipliers.put("SHORT_SQUEEZE", 1.0);
            c.strategyMultipliers.put("NEGATIVE_NEWS_SHORT", 1.0);
            c.strategyMultipliers.put("FEATURE_NEGATIVE_NEWS_SHORT", 1.0);
            c.strategyMultipliers.put("SHORT_ALPHA_BREAKDOWN", 1.0);
            c.strategyMultipliers.put("OFFERING_FADE_SHORT", 1.0);
            c.strategyMultipliers.put("FAILED_VWAP_SHORT", 1.0);
            c.strategyMultipliers.put("PARABOLIC_EXHAUSTION_SHORT", 1.0);
            c.strategyMultipliers.put("GAP_FILL", 1.0);
            c.strategyMultipliers.put("VWAP_RECLAIM", 1.0);
            c.strategyMultipliers.put("FAILED_BREAKDOWN", 1.0);
            return c;
        }

        EvolvedPolicyCandidate copy(String nextName) {
            EvolvedPolicyCandidate n = new EvolvedPolicyCandidate();
            n.name = nextName;
            n.species = species;
            n.regime = regime;
            n.reason = reason;
            n.minProbabilityTarget = minProbabilityTarget;
            n.minExpectedValuePercent = minExpectedValuePercent;
            n.minProposalScore = minProposalScore;
            n.maxStopProbability = maxStopProbability;
            n.riskFractionPerTrade = riskFractionPerTrade;
            n.strategyMultipliers.putAll(strategyMultipliers);
            return n;
        }

        void applySpeciesBias() {
            if ("MOMENTUM".equals(species)) {
                boost("FEATURE_MOMENTUM", 1.45);
                boost("MOMENTUM_NEWS_RUNNER", 1.20);
                minProposalScore -= 0.03;
            } else if ("MEAN_REVERSION".equals(species)) {
                boost("FEATURE_MEAN_REVERSION", 1.45);
                boost("PANIC_REVERSAL", 1.25);
                boost("GAP_FILL", 1.15);
            } else if ("VWAP_RECLAIM".equals(species)) {
                boost("FEATURE_VWAP_RECLAIM", 1.50);
                boost("VWAP_RECLAIM", 1.30);
                minExpectedValuePercent -= 0.05;
            } else if ("SQUEEZE".equals(species)) {
                boost("FEATURE_SQUEEZE", 1.55);
                boost("SHORT_SQUEEZE", 1.35);
                maxStopProbability -= 0.03;
            } else if ("SHORT_ALPHA".equals(species)) {
                boost("FEATURE_NEGATIVE_NEWS_SHORT", 1.60);
                boost("NEGATIVE_NEWS_SHORT", 1.50);
                boost("SHORT_ALPHA_BREAKDOWN", 1.35);
                boost("OFFERING_FADE_SHORT", 1.25);
                minProposalScore -= 0.04;
                maxStopProbability -= 0.025;
            } else if ("FAILED_BREAKDOWN".equals(species)) {
                boost("FEATURE_FAILED_BREAKDOWN", 1.40);
                boost("FAILED_BREAKDOWN", 1.35);
            } else if ("DEFENSIVE_ENSEMBLE".equals(species)) {
                minProbabilityTarget += 0.04;
                minExpectedValuePercent += 0.25;
                riskFractionPerTrade *= 0.65;
                maxStopProbability -= 0.08;
            } else if ("AGGRESSIVE_ENSEMBLE".equals(species)) {
                minProbabilityTarget -= 0.03;
                minProposalScore -= 0.05;
                riskFractionPerTrade *= 1.25;
            }
        }

        void mutate(Random r, EvolutionData data, Config config) {
            reason = "autonomous mutation species=" + species + " regime=" + regime;
            minProbabilityTarget += randomDelta(r, 0.055);
            minExpectedValuePercent += randomDelta(r, 0.45);
            minProposalScore += randomDelta(r, 0.08);
            maxStopProbability += randomDelta(r, 0.06);
            riskFractionPerTrade += randomDelta(r, 0.0015);

            for (String key : new ArrayList<>(strategyMultipliers.keySet())) {
                double v = strategyMultipliers.getOrDefault(key, 1.0);
                v += randomDelta(r, 0.35);
                StrategyStats stats = data.strategyStats.get(key);
                if (stats != null && stats.trades >= 3) {
                    if (stats.profitFactor() > 1.25) {
                        v += 0.15;
                    } else if (stats.profitFactor() < 0.85) {
                        v -= 0.20;
                    }
                }
                strategyMultipliers.put(key, v);
            }

            Regime current = RegimeClassifier.classify(data);
            if (current == Regime.HIGH_RVOL_MOMENTUM) {
                boost("FEATURE_MOMENTUM", 1.15);
                boost("FEATURE_SQUEEZE", 1.10);
            } else if (current == Regime.CHOPPY_MEAN_REVERSION) {
                boost("FEATURE_MEAN_REVERSION", 1.15);
                boost("FEATURE_VWAP_RECLAIM", 1.10);
            } else if (current == Regime.LOW_VOLUME) {
                minProbabilityTarget += 0.025;
                maxStopProbability -= 0.025;
                riskFractionPerTrade *= 0.80;
            } else if (current == Regime.NEWS_CATALYST) {
                boost("MARKET_INTELLIGENCE_AI", 1.10);
                boost("MOMENTUM_NEWS_RUNNER", 1.08);
            } else if (current == Regime.NEGATIVE_NEWS) {
                boost("FEATURE_NEGATIVE_NEWS_SHORT", 1.18);
                boost("NEGATIVE_NEWS_SHORT", 1.15);
                maxStopProbability -= 0.015;
            }

            if (data.closedTrades >= 10 && data.averageTradePnl() < 0.0) {
                minProbabilityTarget += 0.02;
                minExpectedValuePercent += 0.15;
                riskFractionPerTrade *= 0.85;
            }
        }

        void score(EvolutionData data, Config config) {
            List<Double> longReturns = simulate(data.features, data, false);
            List<Double> recentReturns = simulate(data.recentFeatures(0.25), data, true);
            selectedRows = longReturns.size();
            longMemoryScore = sum(longReturns);
            recentScore = sum(recentReturns);
            estimatedWinRate = winRate(longReturns);
            monteCarloP05 = monteCarloP05(longReturns, config.monteCarloRuns, name.hashCode());
            maxDrawdownPenalty = maxDrawdownPenalty(longReturns);

            double realizedBoost = realizedStrategyBoost(data);
            double selectivity = data.featureRows <= 0 ? 0.0 : selectedRows / (double) data.featureRows;
            double overtradePenalty = selectivity > 0.18 ? (selectivity - 0.18) * 150.0 : 0.0;
            double starvationPenalty = selectivity < 0.003 && data.featureRows > 300 ? 25.0 : 0.0;
            double regimeBonus = regimeAlignmentBonus(RegimeClassifier.classify(data));
            averageReturn = selectedRows <= 0 ? 0.0 : longMemoryScore / selectedRows;
            profitFactor = syntheticProfitFactor(longReturns);
            capitalEfficiency = averageReturn / Math.max(0.0003, riskFractionPerTrade);
            sharpeLike = sharpeLike(longReturns);

            fitness =
                    longMemoryScore * 0.30 +
                    recentScore * 0.25 +
                    monteCarloP05 * 0.40 +
                    estimatedWinRate * 15.0 +
                    Math.min(25.0, profitFactor * 4.0) +
                    Math.min(25.0, capitalEfficiency * 0.02) +
                    Math.min(20.0, sharpeLike * 6.0) +
                    realizedBoost +
                    regimeBonus -
                    maxDrawdownPenalty * 1.20 -
                    overtradePenalty -
                    starvationPenalty;
        }

        List<Double> simulate(List<FeatureRow> rows, EvolutionData data, boolean recent) {
            List<Double> out = new ArrayList<>();
            if (rows == null) {
                return out;
            }
            for (FeatureRow f : rows) {
                double proposal = proposalScore(f);
                double p = probability(f, proposal);
                double stop = stopProbability(f);
                double ev = p * 5.0 - stop * 2.5;
                boolean allowed = p >= minProbabilityTarget &&
                        ev >= minExpectedValuePercent &&
                        proposal >= minProposalScore &&
                        stop <= maxStopProbability &&
                        f.barCount >= 1;
                if (allowed) {
                    double synthetic = p * 5.0 - (1.0 - p) * 2.5;
                    if (f.hasGoodStructure()) synthetic += 0.12;
                    if (!f.hasRvol()) synthetic -= recent ? 0.12 : 0.06;
                    if (f.sentimentNet < -0.20) synthetic -= 0.15;
                    synthetic += data.averageTradePnl() * 0.20;
                    out.add(synthetic);
                }
            }
            return out;
        }

        double proposalScore(FeatureRow f) {
            double momentum = AutonomousEvolutionSuite.clamp(Math.max(0.0, f.return3Bars * 12.0) +
                    (f.hasRvol() ? 0.12 : 0.0) +
                    (f.bullishBreak ? 0.25 : 0.0) +
                    (f.reclaimedVwap ? 0.10 : 0.0));
            double mean = AutonomousEvolutionSuite.clamp(f.dropFromHigh20 * 4.0 + f.bounceFromLow20 * 8.0 +
                    (f.reclaimedVwap ? 0.20 : 0.0) +
                    (f.higherLows3 ? 0.12 : 0.0));
            double vwap = AutonomousEvolutionSuite.clamp((f.reclaimedVwap ? 0.45 : 0.0) +
                    (f.vwapDistance > 0.003 ? 0.20 : 0.0) +
                    (f.hasRvol() ? 0.10 : 0.0));
            double squeeze = AutonomousEvolutionSuite.clamp((Math.max(f.rvol5, f.rvol20) > 1.8 ? 0.25 : 0.0) +
                    (f.greenVolumeRatio10 > 1.5 ? 0.20 : 0.0) +
                    (f.bullishBreak ? 0.22 : 0.0) +
                    (f.sentimentNet > 0.30 ? 0.08 : 0.0));
            double failed = AutonomousEvolutionSuite.clamp((f.failedBreakdown ? 0.45 : 0.0) +
                    (f.noFreshLow3 ? 0.15 : 0.0) +
                    (f.higherLows3 ? 0.12 : 0.0));
            double negativeShort = negativeNewsShortScore(f);

            momentum *= mult("FEATURE_MOMENTUM");
            mean *= mult("FEATURE_MEAN_REVERSION");
            vwap *= mult("FEATURE_VWAP_RECLAIM");
            squeeze *= mult("FEATURE_SQUEEZE");
            failed *= mult("FEATURE_FAILED_BREAKDOWN");
            negativeShort *= Math.max(mult("FEATURE_NEGATIVE_NEWS_SHORT"), mult("NEGATIVE_NEWS_SHORT"));
            return Math.max(momentum, Math.max(mean, Math.max(vwap, Math.max(squeeze, Math.max(failed, negativeShort)))));
        }

        double negativeNewsShortScore(FeatureRow f) {
            if (f == null || f.freshnessSeconds > doubleEnv("AI_NEGATIVE_NEWS_SHORT_MAX_FRESHNESS_SECONDS", 900.0)) {
                return 0.0;
            }
            double negativeSentiment = Math.max(0.0, -f.sentimentNet);
            double downsideContinuation = Math.max(0.0, -f.return3Bars * 16.0) + Math.max(0.0, -f.return1Bar * 10.0);
            double rvolPressure = Math.max(f.rvol5, f.rvol20) >= 1.4 ? 0.18 : 0.0;
            double structureBreak = (!f.reclaimedVwap && !f.higherLows3 ? 0.12 : 0.0) + (f.dropFromHigh20 > 0.025 ? 0.10 : 0.0);
            double catalyst = Math.max(0.0, f.catalystScore) * 0.20;
            return AutonomousEvolutionSuite.clamp(negativeSentiment * 0.62 + downsideContinuation + rvolPressure + structureBreak + catalyst);
        }

        boolean isNegativeNewsShortCandidate(FeatureRow f) {
            return negativeNewsShortScore(f) >= 0.20;
        }

        double probability(FeatureRow f, double proposal) {
            double p = 0.38 + proposal * 0.35;
            if (f.hasRvol()) p += 0.06;
            else if (f.barCount < 6) p -= 0.02;
            if (f.bullishBreak) p += 0.06;
            if (f.reclaimedVwap) p += 0.05;
            if (f.failedBreakdown) p += 0.05;
            if (f.higherLows3 && f.noFreshLow3) p += 0.04;
            if (f.return3Bars > 0.004) p += 0.04;
            boolean negativeShort = isNegativeNewsShortCandidate(f);
            if (f.sentimentNet > 0.35 && f.freshnessSeconds < 300) p += 0.04;
            if (negativeShort) {
                p += Math.min(0.10, Math.max(0.0, -f.sentimentNet) * 0.10);
            } else if (f.sentimentNet < -0.20) p -= 0.08;
            if (f.dropFromHigh20 > 0.08 && !f.noFreshLow3) p -= 0.10;
            return AutonomousEvolutionSuite.clamp(p);
        }

        double stopProbability(FeatureRow f) {
            double s = 0.34;
            boolean negativeShort = isNegativeNewsShortCandidate(f);
            if (!f.hasGoodStructure()) s += negativeShort ? 0.02 : 0.08;
            if (negativeShort) s -= 0.05;
            else if (f.sentimentNet < -0.20) s += 0.07;
            if (f.dropFromHigh20 > 0.08 && !f.reclaimedVwap) s += 0.10;
            if (f.hasRvol()) s -= 0.04;
            if (f.higherLows3 && f.noFreshLow3) s -= 0.04;
            return AutonomousEvolutionSuite.clamp(s);
        }

        double realizedStrategyBoost(EvolutionData data) {
            double boost = 0.0;
            for (Map.Entry<String, StrategyStats> e : data.strategyStats.entrySet()) {
                StrategyStats s = e.getValue();
                if (s.trades < 3) {
                    continue;
                }
                double m = mult(e.getKey());
                if (s.profitFactor() > 1.20) {
                    boost += Math.min(12.0, s.expectancy() * m);
                } else if (s.profitFactor() < 0.90) {
                    boost -= Math.min(12.0, Math.abs(s.expectancy()) * m);
                }
            }
            return boost;
        }

        double regimeAlignmentBonus(Regime regime) {
            if (regime == Regime.HIGH_RVOL_MOMENTUM && ("MOMENTUM".equals(species) || "SQUEEZE".equals(species))) return 4.0;
            if (regime == Regime.CHOPPY_MEAN_REVERSION && ("MEAN_REVERSION".equals(species) || "VWAP_RECLAIM".equals(species))) return 4.0;
            if (regime == Regime.LOW_VOLUME && "DEFENSIVE_ENSEMBLE".equals(species)) return 4.0;
            if (regime == Regime.NEWS_CATALYST && ("MOMENTUM".equals(species) || "BALANCED_ENSEMBLE".equals(species))) return 3.0;
            if (regime == Regime.NEGATIVE_NEWS && "SHORT_ALPHA".equals(species)) return 5.0;
            return 0.0;
        }

        void boost(String key, double factor) {
            strategyMultipliers.put(key, strategyMultipliers.getOrDefault(key, 1.0) * factor);
        }

        double mult(String key) {
            return strategyMultipliers.getOrDefault(key, 1.0);
        }

        void clamp() {
            minProbabilityTarget = AutonomousEvolutionSuite.clamp(minProbabilityTarget, 0.45, 0.82);
            minExpectedValuePercent = AutonomousEvolutionSuite.clamp(minExpectedValuePercent, 0.25, 4.00);
            minProposalScore = AutonomousEvolutionSuite.clamp(minProposalScore, 0.10, 0.85);
            maxStopProbability = AutonomousEvolutionSuite.clamp(maxStopProbability, 0.18, 0.65);
            riskFractionPerTrade = AutonomousEvolutionSuite.clamp(riskFractionPerTrade, 0.0003, 0.0125);
            for (String key : new ArrayList<>(strategyMultipliers.keySet())) {
                strategyMultipliers.put(key, AutonomousEvolutionSuite.clamp(strategyMultipliers.get(key), 0.20, 3.00));
            }
        }

        String toPropertiesText() {
            StringBuilder b = new StringBuilder();
            b.append("updatedAtMs=").append(System.currentTimeMillis()).append('\n');
            b.append("liveTradingAllowed=true\n");
            b.append("minProbabilityTarget=").append(fmtRaw(minProbabilityTarget)).append('\n');
            b.append("minExpectedValuePercent=").append(fmtRaw(minExpectedValuePercent)).append('\n');
            b.append("minProposalScore=").append(fmtRaw(minProposalScore)).append('\n');
            b.append("maxStopProbability=").append(fmtRaw(maxStopProbability)).append('\n');
            b.append("riskFractionPerTrade=").append(fmtRaw(riskFractionPerTrade)).append('\n');
            for (Map.Entry<String, Double> e : strategyMultipliers.entrySet()) {
                b.append("strategyMultiplier.").append(e.getKey()).append('=').append(fmtRaw(e.getValue())).append('\n');
            }
            b.append("evolution.championName=").append(name).append('\n');
            b.append("evolution.species=").append(species).append('\n');
            b.append("evolution.regime=").append(regime).append('\n');
            b.append("evolution.fitness=").append(fmtRaw(fitness)).append('\n');
            return b.toString();
        }
    }

    static final class FeatureRow {
        long timestampMs;
        String ticker;
        int barCount;
        double lastPrice;
        double return1Bar;
        double return3Bars;
        double return5Bars;
        double dropFromHigh20;
        double bounceFromLow20;
        double rvol5;
        double rvol20;
        double greenVolumeRatio10;
        double atrPercent14;
        double rsi14;
        double vwapDistance;
        boolean bullishBreak;
        boolean reclaimedVwap;
        boolean failedBreakdown;
        boolean higherLows3;
        boolean noFreshLow3;
        double sentimentNet;
        double catalystScore;
        double freshnessSeconds;
        String selectedStrategy;
        String selectedAction;
        String rawLine;

        static FeatureRow from(CsvHeader h, List<String> c, String rawLine) {
            FeatureRow f = new FeatureRow();
            f.timestampMs = parseTime(h.get(c, "timestamp"));
            f.ticker = h.get(c, "ticker");
            f.barCount = (int) num(h.get(c, "barCount"), 0);
            f.lastPrice = num(h.get(c, "lastPrice"), 0);
            f.return1Bar = num(h.get(c, "return1Bar"), 0);
            f.return3Bars = num(h.get(c, "return3Bars"), 0);
            f.return5Bars = num(h.get(c, "return5Bars"), 0);
            f.dropFromHigh20 = num(h.get(c, "dropFromHigh20"), 0);
            f.bounceFromLow20 = num(h.get(c, "bounceFromLow20"), 0);
            f.rvol5 = num(h.get(c, "rvol5"), 0);
            f.rvol20 = num(h.get(c, "rvol20"), 0);
            f.greenVolumeRatio10 = num(h.get(c, "greenVolumeRatio10"), 0);
            f.atrPercent14 = num(h.get(c, "atrPercent14"), 0);
            f.rsi14 = num(h.get(c, "rsi14"), 50);
            f.vwapDistance = num(h.get(c, "vwapDistance"), 0);
            f.bullishBreak = bool(h.get(c, "bullishBreak")) || lower(rawLine).contains("bullish break");
            f.reclaimedVwap = bool(h.get(c, "reclaimedVwap")) || lower(rawLine).contains("vwap strength");
            f.failedBreakdown = bool(h.get(c, "failedBreakdown"));
            f.higherLows3 = bool(h.get(c, "higherLows3"));
            f.noFreshLow3 = bool(h.get(c, "noFreshLow3"));
            f.sentimentNet = num(h.get(c, "sentimentNet"), 0);
            f.catalystScore = num(h.get(c, "catalystScore"), 0);
            f.freshnessSeconds = num(h.get(c, "freshnessSeconds"), 999999);
            f.selectedStrategy = h.get(c, "selectedStrategy");
            f.selectedAction = h.get(c, "selectedAction");
            f.rawLine = rawLine == null ? "" : rawLine;
            return f;
        }

        boolean hasRvol() { return rvol5 > 0.0 || rvol20 > 0.0; }
        boolean hasGoodStructure() { return bullishBreak || reclaimedVwap || failedBreakdown || (higherLows3 && noFreshLow3); }
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
            OutcomeRow o = new OutcomeRow();
            o.eventType = h.get(c, "eventType");
            o.ticker = h.get(c, "ticker");
            o.strategyName = TradeOutcomeTrainingFilter.normalizeStrategy(h.get(c, "strategyName"));
            o.syncedFromBroker = h.get(c, "syncedFromBroker");
            o.realizedPnl = num(h.get(c, "realizedPnlDollars"), 0);
            o.maxGainPercent = num(h.get(c, "maxGainPercent"), 0);
            o.maxDrawdownPercent = num(h.get(c, "maxDrawdownPercent"), 0);
            return o;
        }

        boolean isClosed() {
            return TradeOutcomeTrainingFilter.isTrainingEligible(eventType, strategyName, syncedFromBroker);
        }
    }

    static final class StrategyStats {
        int trades;
        int wins;
        double pnl;
        double grossWin;
        double grossLoss;

        void add(double pnl) {
            trades++;
            this.pnl += pnl;
            if (pnl > 0) {
                wins++;
                grossWin += pnl;
            } else if (pnl < 0) {
                grossLoss += Math.abs(pnl);
            }
        }

        double expectancy() { return trades <= 0 ? 0.0 : pnl / trades; }
        double profitFactor() { return grossLoss <= 0.0 ? (grossWin > 0.0 ? 9.0 : 1.0) : grossWin / grossLoss; }
    }

    public static final class EvolutionResult {
        public final boolean promoted;
        public final String championName;
        public final String species;
        public final String regime;
        public final double fitness;
        public final int featureRows;
        public final int closedTrades;
        public final Path generationDir;
        public final boolean sourceTournamentRan;
        public final boolean sourcePolicyPromoted;

        EvolutionResult(boolean promoted, String championName, String species, String regime, double fitness,
                        int featureRows, int closedTrades, Path generationDir,
                        boolean sourceTournamentRan, boolean sourcePolicyPromoted) {
            this.promoted = promoted;
            this.championName = championName;
            this.species = species;
            this.regime = regime;
            this.fitness = fitness;
            this.featureRows = featureRows;
            this.closedTrades = closedTrades;
            this.generationDir = generationDir;
            this.sourceTournamentRan = sourceTournamentRan;
            this.sourcePolicyPromoted = sourcePolicyPromoted;
        }

        public String toConsoleSummary() {
            return "FULL AUTONOMOUS EVOLUTION COMPLETE: promoted=" + promoted +
                    " champion=" + championName +
                    " species=" + species +
                    " regime=" + regime +
                    " fitness=" + fmt(fitness) +
                    " featureRows=" + featureRows +
                    " closedTrades=" + closedTrades +
                    " sourceTournamentRan=" + sourceTournamentRan +
                    " sourcePolicyPromoted=" + sourcePolicyPromoted +
                    " generationDir=" + generationDir;
        }
    }

    static final class CsvHeader {
        final Map<String, Integer> idx = new LinkedHashMap<>();
        CsvHeader(String line) {
            List<String> cols = Csv.parse(line);
            for (int i = 0; i < cols.size(); i++) {
                idx.put(cols.get(i).trim(), i);
            }
        }
        String get(List<String> cols, String name) {
            Integer i = idx.get(name);
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
                        } else {
                            quoted = false;
                        }
                    } else {
                        cur.append(ch);
                    }
                } else {
                    if (ch == ',') {
                        out.add(cur.toString());
                        cur.setLength(0);
                    } else if (ch == '"') {
                        quoted = true;
                    } else {
                        cur.append(ch);
                    }
                }
            }
            out.add(cur.toString());
            return out;
        }
    }

    static final class FilesUtil {
        static void ensureParent(Path p) {
            Path parent = p == null ? null : p.getParent();
            if (parent != null) ensureDir(parent);
        }
        static void ensureDir(Path p) {
            try { Files.createDirectories(p); }
            catch (IOException e) { throw new RuntimeException("Failed to create " + p + ": " + e.getMessage(), e); }
        }
        static void writeString(Path p, String s) {
            try {
                ensureParent(p);
                Files.writeString(p, s == null ? "" : s, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write " + p + ": " + e.getMessage(), e);
            }
        }
    }


    private static double syntheticProfitFactor(List<Double> returns) {
        if (returns == null || returns.isEmpty()) return 0.0;
        double win = 0.0;
        double loss = 0.0;
        for (double v : returns) {
            if (v > 0) win += v;
            else if (v < 0) loss += Math.abs(v);
        }
        if (loss <= 0.0) return win > 0.0 ? 9.0 : 0.0;
        return win / loss;
    }

    private static double sharpeLike(List<Double> returns) {
        if (returns == null || returns.size() < 2) return 0.0;
        double mean = sum(returns) / returns.size();
        double var = 0.0;
        for (double v : returns) {
            double d = v - mean;
            var += d * d;
        }
        double sd = Math.sqrt(var / Math.max(1, returns.size() - 1));
        return sd <= 0.0 ? (mean > 0.0 ? 5.0 : 0.0) : mean / sd;
    }

    private static double monteCarloP05(List<Double> returns, int runs, long seed) {
        if (returns == null || returns.isEmpty()) return -10.0;
        Random r = new Random(seed);
        List<Double> totals = new ArrayList<>();
        int draw = Math.min(75, Math.max(10, returns.size()));
        for (int i = 0; i < runs; i++) {
            double sum = 0.0;
            for (int j = 0; j < draw; j++) sum += returns.get(r.nextInt(returns.size()));
            totals.add(sum);
        }
        totals.sort(Double::compare);
        return totals.get(Math.max(0, Math.min(totals.size() - 1, (int) Math.floor(totals.size() * 0.05))));
    }

    private static double maxDrawdownPenalty(List<Double> returns) {
        double equity = 0.0, peak = 0.0, maxDd = 0.0;
        for (double v : returns) {
            equity += v;
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }
        return maxDd * 0.40;
    }

    private static double winRate(List<Double> returns) {
        if (returns == null || returns.isEmpty()) return 0.0;
        int wins = 0;
        for (double v : returns) if (v > 0) wins++;
        return wins / (double) returns.size();
    }

    private static double sum(List<Double> values) {
        double s = 0.0;
        if (values != null) for (double v : values) s += v;
        return s;
    }

    private static double randomDelta(Random r, double scale) {
        return (r.nextDouble() * 2.0 - 1.0) * scale;
    }

    private static String normalize(String v) {
        if (v == null || v.isBlank()) return "UNKNOWN";
        return v.trim().toUpperCase();
    }

    private static String lower(String v) {
        return v == null ? "" : v.toLowerCase();
    }

    private static boolean bool(String v) {
        return "true".equalsIgnoreCase(v == null ? "" : v.trim());
    }

    private static double num(String v, double fallback) {
        try { return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static long parseTime(String v) {
        try {
            if (v == null || v.isBlank()) return 0L;
            return Instant.parse(v.trim()).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double clamp(double v) {
        return clamp(v, 0.0, 1.0);
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

    private static String csv(String v) {
        if (v == null) return "";
        String s = v.replace('\n', ' ').replace('\r', ' ');
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0.0;
        return new DecimalFormat("0.0000").format(v);
    }

    private static String fmtRaw(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0.0;
        return Double.toString(v);
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

    private static boolean boolEnv(String key, boolean fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : "true".equalsIgnoreCase(v.trim());
    }
}
