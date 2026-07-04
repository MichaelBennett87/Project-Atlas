package com.bot.intelligence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

public class AdaptiveTradingPolicyStore {

    private final Path path;
    private final long reloadIntervalMs;
    private final AtomicReference<AdaptiveTradingPolicy> cached =
            new AtomicReference<>(AdaptiveTradingPolicy.defaults());
    private final AtomicReference<Map<String, AdaptiveTradingPolicy>> regimePolicies =
            new AtomicReference<>(new HashMap<>());
    private volatile long lastLoadedAt = 0L;
    private volatile long lastModifiedAt = -1L;

    public AdaptiveTradingPolicyStore() {
        this(
                Path.of(System.getenv().getOrDefault("AI_POLICY_PATH", "logs/ai_policy.properties")),
                envLong("AI_POLICY_RELOAD_SECONDS", 30L) * 1000L
        );
    }

    public AdaptiveTradingPolicyStore(Path path, long reloadIntervalMs) {
        this.path = path;
        this.reloadIntervalMs = Math.max(1_000L, reloadIntervalMs);
        reloadNow();
    }

    public AdaptiveTradingPolicy currentPolicy() {
        long now = System.currentTimeMillis();
        if (now - lastLoadedAt > reloadIntervalMs) {
            reloadNow();
        }
        return cached.get();
    }

    /**
     * Returns the best available policy for the current feature/proposal regime.
     * Falls back to the global champion policy when no matching portfolio champion exists.
     */
    public AdaptiveTradingPolicy policyFor(MarketFeatureSnapshot snapshot, String proposalStrategy) {
        long now = System.currentTimeMillis();
        if (now - lastLoadedAt > reloadIntervalMs) {
            reloadNow();
        }

        Map<String, AdaptiveTradingPolicy> policies = regimePolicies.get();
        if (policies == null || policies.isEmpty()) {
            return cached.get();
        }

        String regime = detectRegime(snapshot);
        String species = speciesForProposal(proposalStrategy);

        AdaptiveTradingPolicy exact = policies.get(key(regime, species));
        if (exact != null) return exact;

        AdaptiveTradingPolicy regimeOnly = policies.get(key(regime, "ANY"));
        if (regimeOnly != null) return regimeOnly;

        AdaptiveTradingPolicy speciesOnly = policies.get(key("ANY", species));
        if (speciesOnly != null) return speciesOnly;

        AdaptiveTradingPolicy balanced = policies.get(key("ANY", "BALANCED_ENSEMBLE"));
        return balanced == null ? cached.get() : balanced;
    }

    public synchronized void save(AdaptiveTradingPolicy policy) {
        if (policy == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties p = policy.toProperties();
            try (OutputStream out = Files.newOutputStream(path)) {
                p.store(out, "Auto-generated adaptive trading policy. Do not edit while bot is running unless you know what you are doing.");
            }
            cached.set(policy);
            lastLoadedAt = System.currentTimeMillis();
            lastModifiedAt = Files.getLastModifiedTime(path).toMillis();
            System.out.println("AI POLICY UPDATED: " + path + " minP=" + policy.minProbabilityTarget +
                    " minEV=" + policy.minExpectedValuePercent + " risk=" + policy.riskFractionPerTrade);
        } catch (IOException e) {
            System.out.println("AI POLICY SAVE FAILED: " + e.getMessage());
        }
    }

    public synchronized void reloadNow() {
        lastLoadedAt = System.currentTimeMillis();
        try {
            if (!Files.exists(path)) {
                save(AdaptiveTradingPolicy.defaults());
                return;
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            boolean baseChanged = modified != lastModifiedAt;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            }
            mergeSupplementalPolicy(p, Path.of(System.getenv().getOrDefault("PORTFOLIO_POLICY_PATH", "logs/portfolio_policy.properties")), "allocation.", "strategyMultiplier.", 3.0);
            mergeModelPolicy(p, Path.of(System.getenv().getOrDefault("MODEL_WEIGHTS_PATH", "logs/model_weights.properties")));
            mergeLifecycleOptimizationPolicy(p, Path.of(System.getenv().getOrDefault("TRADE_LIFECYCLE_RECOMMENDATION_PATH", "logs/trade_lifecycle_recommendations.properties")));
            mergeLifecycleOptimizationPolicy(p, Path.of(System.getenv().getOrDefault("REPLAY_STRATEGY_POLICY_PATH", "logs/replay_strategy_policy.properties")));
            mergeSimulationStrategyPolicy(p, Path.of(System.getenv().getOrDefault("SIMULATION_STRATEGY_POLICY_PATH", "logs/simulation_strategy_policy.properties")));
            mergeSimulationStrategyPolicy(p, Path.of(System.getenv().getOrDefault("BAR_BY_BAR_SIMULATION_POLICY_PATH", "logs/bar_by_bar_simulation_policy.properties")));
            mergeSimulationStrategyPolicy(p, Path.of(System.getenv().getOrDefault("PAPER_TRADING_STRATEGY_POLICY_PATH", "logs/paper_trading_strategy_policy.properties")));
            mergePredictionCalibrationPolicy(p, Path.of(System.getenv().getOrDefault("PREDICTION_CALIBRATION_POLICY_PATH", "logs/prediction_calibration_policy.properties")));
            loadRegimePolicyPortfolio(Path.of(System.getenv().getOrDefault("AI_POLICY_PORTFOLIO_PATH", "logs/ai_policy_portfolio.properties")));
            AdaptiveTradingPolicy policy = AdaptiveTradingPolicy.fromProperties(p);
            cached.set(policy);
            lastModifiedAt = modified;
            if (baseChanged || envBool("AI_POLICY_VERBOSE_RELOAD", false)) {
                System.out.println("AI POLICY LOADED: " + path + " minP=" + policy.minProbabilityTarget +
                        " minEV=" + policy.minExpectedValuePercent + " minProposal=" + policy.minProposalScore);
            }
        } catch (Exception e) {
            System.out.println("AI POLICY LOAD FAILED: " + e.getMessage());
            cached.set(AdaptiveTradingPolicy.defaults());
        }
    }

    private void mergeSupplementalPolicy(Properties target, Path supplementalPath, String sourcePrefix, String targetPrefix, double multiplierScale) {
        try {
            if (supplementalPath == null || !Files.exists(supplementalPath)) {
                return;
            }
            Properties supplemental = new Properties();
            try (InputStream in = Files.newInputStream(supplementalPath)) {
                supplemental.load(in);
            }
            for (String key : supplemental.stringPropertyNames()) {
                if (!key.startsWith(sourcePrefix)) {
                    continue;
                }
                String strategy = key.substring(sourcePrefix.length()).trim().toUpperCase();
                double allocation = parseDouble(supplemental.getProperty(key), 0.0);
                double multiplier = Math.max(0.20, Math.min(3.00, allocation * multiplierScale));
                target.setProperty(targetPrefix + strategy, Double.toString(multiplier));
            }
        } catch (Exception e) {
            System.out.println("AI SUPPLEMENTAL POLICY MERGE FAILED: " + supplementalPath + " " + e.getMessage());
        }
    }

    private void mergeModelPolicy(Properties target, Path modelPath) {
        try {
            if (modelPath == null || !Files.exists(modelPath)) {
                return;
            }
            Properties model = new Properties();
            try (InputStream in = Files.newInputStream(modelPath)) {
                model.load(in);
            }
            double structure = parseDouble(model.getProperty("weight.structure"), 0.0);
            double rvol = parseDouble(model.getProperty("weight.rvol"), 0.0);
            double proposal = parseDouble(model.getProperty("weight.proposal"), 0.0);
            double adjustment = Math.max(-0.05, Math.min(0.05, (structure + rvol + proposal) * 0.01));
            double currentMinP = parseDouble(target.getProperty("minProbabilityTarget"), AdaptiveTradingPolicy.defaults().minProbabilityTarget);
            target.setProperty("minProbabilityTarget", Double.toString(Math.max(0.45, Math.min(0.82, currentMinP - adjustment))));
        } catch (Exception e) {
            System.out.println("AI MODEL POLICY MERGE FAILED: " + modelPath + " " + e.getMessage());
        }
    }



    private void mergeLifecycleOptimizationPolicy(Properties target, Path lifecyclePath) {
        try {
            if (lifecyclePath == null || !Files.exists(lifecyclePath)) {
                return;
            }
            Properties lifecycle = new Properties();
            try (InputStream in = Files.newInputStream(lifecyclePath)) {
                lifecycle.load(in);
            }
            int merged = 0;
            for (String key : lifecycle.stringPropertyNames()) {
                if (!key.startsWith("strategyMultiplier.")) {
                    continue;
                }
                String strategy = key.substring("strategyMultiplier.".length()).trim().toUpperCase();
                if (strategy.isEmpty()) {
                    continue;
                }
                double multiplier = Math.max(0.50, Math.min(1.50, parseDouble(lifecycle.getProperty(key), 1.0)));
                target.setProperty("strategyMultiplier." + strategy, Double.toString(multiplier));
                merged++;
            }
            if (merged > 0 && "true".equalsIgnoreCase(System.getenv().getOrDefault("TRADE_LIFECYCLE_POLICY_VERBOSE", "false"))) {
                System.out.println("AI LIFECYCLE POLICY MERGED: " + lifecyclePath + " strategyMultipliers=" + merged);
            }
        } catch (Exception e) {
            System.out.println("AI LIFECYCLE POLICY MERGE FAILED: " + lifecyclePath + " " + e.getMessage());
        }
    }

    private void mergePredictionCalibrationPolicy(Properties target, Path calibrationPath) {
        try {
            if (calibrationPath == null || !Files.exists(calibrationPath)) {
                return;
            }
            Properties calibration = new Properties();
            try (InputStream in = Files.newInputStream(calibrationPath)) {
                calibration.load(in);
            }
            boolean requireValidatedIncreases =
                    envBool("PREDICTION_CALIBRATION_REQUIRE_VALIDATED_INCREASES", true);
            int merged = 0;
            int blockedIncreases = 0;
            for (String key : calibration.stringPropertyNames()) {
                if (!key.startsWith("strategyMultiplier.")) {
                    continue;
                }
                String strategy = key.substring("strategyMultiplier.".length()).trim().toUpperCase();
                if (strategy.isEmpty()) {
                    continue;
                }
                double multiplier = Math.max(0.50, Math.min(1.50, parseDouble(calibration.getProperty(key), 1.0)));
                String validationStatus =
                        calibration.getProperty("validationStatus." + strategy, "").trim().toUpperCase();
                if (requireValidatedIncreases && multiplier > 1.0 && !"PASSED".equals(validationStatus)) {
                    blockedIncreases++;
                    continue;
                }
                target.setProperty("strategyMultiplier." + strategy, Double.toString(multiplier));
                merged++;
            }
            if ((merged > 0 || blockedIncreases > 0)
                    && envBool("PREDICTION_CALIBRATION_POLICY_VERBOSE", false)) {
                System.out.println("AI PREDICTION CALIBRATION POLICY MERGED: " +
                        calibrationPath +
                        " strategyMultipliers=" + merged +
                        " blockedUnvalidatedIncreases=" + blockedIncreases);
            }
        } catch (Exception e) {
            System.out.println("AI PREDICTION CALIBRATION POLICY MERGE FAILED: " + calibrationPath + " " + e.getMessage());
        }
    }

    private void mergeSimulationStrategyPolicy(Properties target, Path simulationPath) {
        try {
            if (simulationPath == null || !Files.exists(simulationPath)) {
                return;
            }
            Properties simulation = new Properties();
            try (InputStream in = Files.newInputStream(simulationPath)) {
                simulation.load(in);
            }
            int merged = 0;
            int blockedIncreases = 0;
            boolean requirePassed =
                    envBool("SIMULATION_STRATEGY_REQUIRE_PASSED_FOR_INCREASES", true);
            for (String key : simulation.stringPropertyNames()) {
                if (!key.startsWith("strategyMultiplier.")) {
                    continue;
                }
                String strategy = key.substring("strategyMultiplier.".length()).trim().toUpperCase();
                if (strategy.isEmpty()) {
                    continue;
                }
                double multiplier = Math.max(0.50, Math.min(1.50, parseDouble(simulation.getProperty(key), 1.0)));
                String status = simulation.getProperty("simulationStatus." + strategy, "").trim().toUpperCase();
                if (requirePassed && multiplier > 1.0 && !"PASSED".equals(status)) {
                    blockedIncreases++;
                    continue;
                }
                target.setProperty("strategyMultiplier." + strategy, Double.toString(multiplier));
                merged++;
            }
            if ((merged > 0 || blockedIncreases > 0)
                    && envBool("SIMULATION_STRATEGY_POLICY_VERBOSE", false)) {
                System.out.println("AI SIMULATION STRATEGY POLICY MERGED: " +
                        simulationPath +
                        " strategyMultipliers=" + merged +
                        " blockedUnpassedIncreases=" + blockedIncreases);
            }
        } catch (Exception e) {
            System.out.println("AI SIMULATION STRATEGY POLICY MERGE FAILED: " + simulationPath + " " + e.getMessage());
        }
    }

    private void loadRegimePolicyPortfolio(Path portfolioPath) {
        Map<String, AdaptiveTradingPolicy> loaded = new HashMap<>();
        try {
            if (portfolioPath == null || !Files.exists(portfolioPath)) {
                regimePolicies.set(loaded);
                return;
            }
            Properties all = new Properties();
            try (InputStream in = Files.newInputStream(portfolioPath)) {
                all.load(in);
            }
            for (String slot : all.getProperty("slots", "").split(",")) {
                String id = slot == null ? "" : slot.trim();
                if (id.isEmpty()) continue;
                String prefix = "slot." + id + ".";
                Properties p = new Properties();
                for (String key : all.stringPropertyNames()) {
                    if (key.startsWith(prefix)) {
                        p.setProperty(key.substring(prefix.length()), all.getProperty(key));
                    }
                }
                AdaptiveTradingPolicy policy = AdaptiveTradingPolicy.fromProperties(p);
                String regime = p.getProperty("evolution.regime", "ANY").trim().toUpperCase();
                String species = p.getProperty("evolution.species", "ANY").trim().toUpperCase();
                loaded.put(key(regime, species), policy);
                loaded.putIfAbsent(key(regime, "ANY"), policy);
                loaded.putIfAbsent(key("ANY", species), policy);
            }
            regimePolicies.set(loaded);
            if (!loaded.isEmpty()) {
                System.out.println("AI POLICY PORTFOLIO LOADED: " + portfolioPath + " slots=" + loaded.size());
            }
        } catch (Exception e) {
            System.out.println("AI POLICY PORTFOLIO LOAD FAILED: " + portfolioPath + " " + e.getMessage());
            regimePolicies.set(new HashMap<>());
        }
    }

    private static String detectRegime(MarketFeatureSnapshot f) {
        if (f == null || f.barCount < 3) return "LOW_DATA";
        double rvol = Math.max(f.rvol5, f.rvol20);
        double absRet = Math.abs(f.return3Bars);
        boolean freshNews = f.catalystScore > 0.25 || Math.abs(f.sentimentNet) > 0.25 || f.freshnessSeconds < 600;
        boolean freshNegativeNews = f.sentimentNet <= -0.25 && f.freshnessSeconds <= envLong("AI_NEGATIVE_NEWS_SHORT_MAX_FRESHNESS_SECONDS", 900L);
        if (freshNegativeNews) return "NEGATIVE_NEWS";
        if (rvol <= 0.0 || (rvol < 0.75 && f.barCount < 20)) return "LOW_VOLUME";
        if (rvol >= 1.8 && absRet > 0.003) return "HIGH_RVOL_MOMENTUM";
        if (freshNews) return "NEWS_CATALYST";
        if (absRet < 0.0015) return "CHOPPY_MEAN_REVERSION";
        return "BALANCED";
    }

    private static String speciesForProposal(String strategy) {
        String s = strategy == null ? "" : strategy.trim().toUpperCase();
        if (s.contains("NEGATIVE_NEWS_SHORT") || s.contains("SHORT_ALPHA") || s.contains("OFFERING_FADE") ||
                s.contains("FAILED_VWAP_SHORT") || s.contains("PARABOLIC_EXHAUSTION_SHORT")) return "SHORT_ALPHA";
        if (s.contains("SQUEEZE")) return "SQUEEZE";
        if (s.contains("MEAN") || s.contains("PANIC") || s.contains("GAP")) return "MEAN_REVERSION";
        if (s.contains("VWAP")) return "VWAP_RECLAIM";
        if (s.contains("FAILED_BREAKDOWN")) return "FAILED_BREAKDOWN";
        if (s.contains("MOMENTUM") || s.contains("NEWS")) return "MOMENTUM";
        return "BALANCED_ENSEMBLE";
    }

    private static String key(String regime, String species) {
        return (regime == null ? "ANY" : regime.trim().toUpperCase()) + "|" +
                (species == null ? "ANY" : species.trim().toUpperCase());
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public Path path() {
        return path;
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }
}
