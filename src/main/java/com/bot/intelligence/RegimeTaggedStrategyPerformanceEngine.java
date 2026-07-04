package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 * Learns which strategy/context combinations have actually shown edge.
 *
 * The detailed context rows are used for research and reporting. The session
 * rows are intentionally simple enough for the live strategy gate to consume
 * safely without needing to know the future shape of a trade.
 */
public final class RegimeTaggedStrategyPerformanceEngine {
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final List<Path> historicalTradePaths;
    private final List<Path> outcomePaths;
    private final Path policyPath;
    private final Path reportPath;
    private final Path matrixPath;
    private final Path healthPath;
    private final Config config;

    public RegimeTaggedStrategyPerformanceEngine() {
        this(
                parsePaths(env("REGIME_STRATEGY_HISTORICAL_TRADE_PATHS", defaultHistoricalTradePaths())),
                parsePaths(env("REGIME_STRATEGY_OUTCOME_PATHS", defaultOutcomePaths())),
                Path.of(env("REGIME_STRATEGY_POLICY_PATH", "logs/regime_strategy_policy.properties")),
                Path.of(env("REGIME_STRATEGY_REPORT_PATH", "logs/regime_strategy_report.txt")),
                Path.of(env("REGIME_STRATEGY_MATRIX_PATH", "logs/regime_strategy_matrix.csv")),
                Path.of(env("REGIME_STRATEGY_HEALTH_PATH", "logs/regime_strategy_health.properties")),
                Config.fromEnv()
        );
    }

    RegimeTaggedStrategyPerformanceEngine(List<Path> historicalTradePaths,
                                          List<Path> outcomePaths,
                                          Path policyPath,
                                          Path reportPath,
                                          Path matrixPath,
                                          Path healthPath,
                                          Config config) {
        this.historicalTradePaths = historicalTradePaths == null ? List.of() : List.copyOf(historicalTradePaths);
        this.outcomePaths = outcomePaths == null ? List.of() : List.copyOf(outcomePaths);
        this.policyPath = policyPath;
        this.reportPath = reportPath;
        this.matrixPath = matrixPath;
        this.healthPath = healthPath;
        this.config = config == null ? Config.fromEnv() : config;
    }

    public Result run() {
        Map<String, RegimeStats> stats = new LinkedHashMap<>();
        int historicalSamples = readHistoricalSimulationTrades(stats);
        int journalSamples = readPaperAndShadowOutcomes(stats);

        List<Recommendation> recommendations = new ArrayList<>();
        for (RegimeStats s : stats.values()) {
            if (s.trades > 0) {
                recommendations.add(recommend(s));
            }
        }
        recommendations.sort(Comparator
                .comparing((Recommendation r) -> r.scopeRank()).reversed()
                .thenComparing((Recommendation r) -> r.decisionRank()).reversed()
                .thenComparing((Recommendation r) -> r.stats.profitFactor()).reversed()
                .thenComparing(r -> r.stats.strategy)
                .thenComparing(r -> r.stats.regimeKey));

        writePolicy(recommendations, historicalSamples, journalSamples);
        writeMatrix(recommendations);
        writeReport(recommendations, historicalSamples, journalSamples);
        writeHealth(recommendations, historicalSamples, journalSamples);

        int enabled = 0;
        int disabled = 0;
        int watched = 0;
        for (Recommendation r : recommendations) {
            if ("ENABLE_REGIME".equals(r.decision)) enabled++;
            else if ("DISABLE_REGIME".equals(r.decision)) disabled++;
            else watched++;
        }
        return new Result(stats.size(), recommendations.size(), historicalSamples, journalSamples,
                enabled, disabled, watched, policyPath, reportPath, matrixPath, healthPath);
    }

    private int readHistoricalSimulationTrades(Map<String, RegimeStats> stats) {
        int samples = 0;
        Set<Path> seen = new HashSet<>();
        for (Path path : historicalTradePaths) {
            if (path == null || !seen.add(path) || !Files.exists(path)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String header = reader.readLine();
                if (header == null) {
                    continue;
                }
                CsvHeader h = new CsvHeader(header);
                String line;
                while ((line = reader.readLine()) != null) {
                    List<String> cols = parseCsv(line);
                    String strategy = normalizeStrategy(h.get(cols, "strategy"));
                    if (isUnknownStrategy(strategy)) {
                        continue;
                    }
                    long entryMs = parseEpochMillis(h.get(cols, "entryTime"), 0L);
                    String session = sessionFor(entryMs);
                    String side = sideBucket(h.get(cols, "direction"));
                    double pnlDollars = parseDouble(h.get(cols, "pnlDollars"), 0.0);
                    double pnlPercent = parseDouble(h.get(cols, "pnlPercent"), 0.0);
                    double expectedMove = parseDouble(h.get(cols, "expectedMove"), 0.0);
                    double catalystScore = parseDouble(h.get(cols, "catalystScore"), 0.0);
                    double newsAgeMinutes = parseDouble(h.get(cols, "newsAgeMinutes"), 0.0);
                    String catalyst = catalystBucket(catalystScore, h.get(cols, "newsSource"), h.get(cols, "headline"), newsAgeMinutes);
                    String move = moveBucket(expectedMove);
                    String source = path.getFileName() == null ? "historical_simulation" : path.getFileName().toString();

                    record(stats, strategy, "SESSION_" + session, "SESSION", pnlDollars, pnlPercent, source);
                    record(stats, strategy,
                            "SESSION_" + session + "_SIDE_" + side + "_CATALYST_" + catalyst + "_MOVE_" + move,
                            "CONTEXT", pnlDollars, pnlPercent, source);
                    samples++;
                }
            } catch (IOException e) {
                System.out.println("REGIME STRATEGY HISTORICAL READ FAILED: " + path + " " + e.getMessage());
            }
        }
        return samples;
    }

    private int readPaperAndShadowOutcomes(Map<String, RegimeStats> stats) {
        int samples = 0;
        Set<Path> seen = new HashSet<>();
        for (Path path : outcomePaths) {
            if (path == null || !seen.add(path) || !Files.exists(path)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String header = reader.readLine();
                if (header == null) {
                    continue;
                }
                CsvHeader h = new CsvHeader(header);
                String line;
                while ((line = reader.readLine()) != null) {
                    List<String> cols = parseCsv(line);
                    String eventType = normalize(h.get(cols, "eventType"));
                    if (!isClosedOutcome(eventType)) {
                        continue;
                    }
                    String strategy = normalizeStrategy(firstNonBlank(
                            h.get(cols, "strategyName"),
                            h.get(cols, "strategy")));
                    if (isUnknownStrategy(strategy)) {
                        continue;
                    }
                    long entryMs = parseLong(firstNonBlank(h.get(cols, "openedAtMs"), h.get(cols, "entryAtMs")), 0L);
                    if (entryMs <= 0L) {
                        entryMs = parseEpochMillis(firstNonBlank(h.get(cols, "timestamp"), h.get(cols, "entryTime")), 0L);
                    }
                    if (entryMs <= 0L) {
                        entryMs = parseLong(h.get(cols, "eventAtMs"), 0L);
                    }
                    String session = sessionFor(entryMs);
                    String side = sideBucket(firstNonBlank(h.get(cols, "side"), h.get(cols, "direction")));
                    double pnlDollars = parseDouble(firstNonBlank(
                            h.get(cols, "realizedPnlDollars"),
                            h.get(cols, "realizedProfit"),
                            h.get(cols, "realizedPnl"),
                            h.get(cols, "pnlDollars")), 0.0);
                    double pnlPercent = parseDouble(firstNonBlank(
                            h.get(cols, "currentPnlPercent"),
                            h.get(cols, "pnlPercent"),
                            h.get(cols, "returnPercent")), 0.0);
                    String source = path.getFileName() == null ? "paper_shadow_outcome" : path.getFileName().toString();

                    record(stats, strategy, "SESSION_" + session, "SESSION", pnlDollars, pnlPercent, source);
                    record(stats, strategy,
                            "SESSION_" + session + "_SIDE_" + side + "_CATALYST_LIVE_JOURNAL_MOVE_UNKNOWN",
                            "CONTEXT", pnlDollars, pnlPercent, source);
                    samples++;
                }
            } catch (IOException e) {
                System.out.println("REGIME STRATEGY OUTCOME READ FAILED: " + path + " " + e.getMessage());
            }
        }
        return samples;
    }

    private Recommendation recommend(RegimeStats s) {
        if (s.trades < config.minSamples) {
            return new Recommendation(
                    s,
                    "INSUFFICIENT",
                    1.0,
                    false,
                    "sample_count_below_gate trades=" + s.trades + " minSamples=" + config.minSamples
            );
        }

        double expectancyDollars = s.expectancyDollars();
        double expectancyPercent = s.expectancyPercent();
        double winRate = s.winRate();
        double profitFactor = s.profitFactor();
        boolean pass = s.trades >= config.minPromoteSamples
                && expectancyDollars >= config.minExpectancyDollars
                && expectancyPercent >= config.minExpectancyPercent
                && winRate >= config.minWinRate
                && profitFactor >= config.minProfitFactor;

        boolean severe = s.trades >= config.minDisableSamples
                && (expectancyDollars <= -Math.abs(config.disableExpectancyDollars)
                || expectancyPercent <= -Math.abs(config.disableExpectancyPercent))
                && winRate <= config.disableWinRate
                && profitFactor <= config.disableProfitFactor;

        if (pass) {
            double boost = 0.05
                    + clamp((profitFactor - config.minProfitFactor) * 0.06, 0.0, 0.12)
                    + clamp((winRate - config.minWinRate) * 0.30, 0.0, 0.08)
                    + clamp((expectancyPercent - config.minExpectancyPercent) * 35.0, 0.0, 0.10);
            double multiplier = clamp(1.0 + boost, 1.03, config.maxPromoteMultiplier);
            return new Recommendation(s, "ENABLE_REGIME", multiplier, false,
                    "regime_edge_confirmed " + metrics(s));
        }

        if (severe) {
            boolean disabled = !config.protectedDisableStrategies.contains(s.strategy);
            String reason = (disabled ? "regime_loss_veto " : "protected_from_regime_disable_shrunk_only ") + metrics(s);
            return new Recommendation(s, "DISABLE_REGIME", config.disableMultiplier, disabled, reason);
        }

        boolean weak = expectancyDollars < 0.0
                || expectancyPercent < 0.0
                || winRate < config.weakWinRate
                || profitFactor < config.weakProfitFactor;
        if (weak) {
            double penalty = 0.08;
            if (expectancyDollars < 0.0 || expectancyPercent < 0.0) penalty += 0.10;
            if (profitFactor < config.weakProfitFactor) penalty += 0.06;
            if (winRate < config.weakWinRate) penalty += 0.04;
            double multiplier = clamp(1.0 - penalty, config.minShrinkMultiplier, 0.95);
            return new Recommendation(s, "WATCH_REGIME", multiplier, false,
                    "regime_edge_weak_watch_only " + metrics(s));
        }

        return new Recommendation(s, "WATCH_REGIME", 1.0, false,
                "regime_edge_neutral " + metrics(s));
    }

    private void writePolicy(List<Recommendation> recommendations, int historicalSamples, int journalSamples) {
        Properties p = new Properties();
        p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("description", "Regime-tagged strategy performance. Session rows are consumed by StrategySelectionGovernor.");
        p.setProperty("historicalTradePaths", historicalTradePaths.toString());
        p.setProperty("outcomePaths", outcomePaths.toString());
        p.setProperty("historicalSamples", Integer.toString(historicalSamples));
        p.setProperty("journalSamples", Integer.toString(journalSamples));
        p.setProperty("matrixPath", matrixPath.toString());
        p.setProperty("reportPath", reportPath.toString());
        p.setProperty("minSamples", Integer.toString(config.minSamples));
        p.setProperty("minPromoteSamples", Integer.toString(config.minPromoteSamples));
        p.setProperty("minDisableSamples", Integer.toString(config.minDisableSamples));

        Map<String, Recommendation> bestByStrategy = new HashMap<>();
        Map<String, Recommendation> worstByStrategy = new HashMap<>();
        for (Recommendation r : recommendations) {
            RegimeStats s = r.stats;
            String prefix = "strategy." + s.strategy + ".regime." + s.regimeKey + ".";
            p.setProperty("regimeDecision." + s.strategy + "." + s.regimeKey, r.decision);
            p.setProperty("strategyMultiplier." + s.strategy + "." + s.regimeKey, fmt(r.multiplier));
            p.setProperty(prefix + "scope", s.scope);
            p.setProperty(prefix + "reason", r.reason);
            p.setProperty(prefix + "trades", Integer.toString(s.trades));
            p.setProperty(prefix + "wins", Integer.toString(s.wins));
            p.setProperty(prefix + "losses", Integer.toString(s.losses));
            p.setProperty(prefix + "pnlDollars", fmt(s.totalPnlDollars));
            p.setProperty(prefix + "expectancyDollars", fmt(s.expectancyDollars()));
            p.setProperty(prefix + "expectancyPercent", fmt(s.expectancyPercent()));
            p.setProperty(prefix + "winRate", fmt(s.winRate()));
            p.setProperty(prefix + "profitFactor", fmt(s.profitFactor()));
            p.setProperty(prefix + "maxEquityDrawdownDollars", fmt(s.maxEquityDrawdownDollars));
            p.setProperty(prefix + "sources", s.sourceSummary());
            if (r.disabled) {
                p.setProperty("regimeDisabledStrategy." + s.strategy + "." + s.regimeKey, "true");
            }
            if ("SESSION".equals(s.scope)) {
                bestByStrategy.merge(s.strategy, r, (a, b) -> b.qualityScore() > a.qualityScore() ? b : a);
                worstByStrategy.merge(s.strategy, r, (a, b) -> b.qualityScore() < a.qualityScore() ? b : a);
            }
        }
        for (Map.Entry<String, Recommendation> entry : bestByStrategy.entrySet()) {
            Recommendation r = entry.getValue();
            p.setProperty("bestRegime." + entry.getKey(), r.stats.regimeKey);
            p.setProperty("bestRegime." + entry.getKey() + ".decision", r.decision);
            p.setProperty("bestRegime." + entry.getKey() + ".qualityScore", fmt(r.qualityScore()));
        }
        for (Map.Entry<String, Recommendation> entry : worstByStrategy.entrySet()) {
            Recommendation r = entry.getValue();
            p.setProperty("worstRegime." + entry.getKey(), r.stats.regimeKey);
            p.setProperty("worstRegime." + entry.getKey() + ".decision", r.decision);
            p.setProperty("worstRegime." + entry.getKey() + ".qualityScore", fmt(r.qualityScore()));
        }

        try {
            ensureParent(policyPath);
            try (OutputStream out = Files.newOutputStream(policyPath)) {
                p.store(out, "Regime-tagged strategy performance policy");
            }
        } catch (IOException e) {
            System.out.println("REGIME STRATEGY POLICY WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeMatrix(List<Recommendation> recommendations) {
        try {
            ensureParent(matrixPath);
            StringBuilder b = new StringBuilder();
            b.append("scope,strategy,regimeKey,decision,multiplier,trades,wins,losses,pnlDollars,expectancyDollars,expectancyPercent,winRate,profitFactor,maxEquityDrawdownDollars,sources,reason\n");
            for (Recommendation r : recommendations) {
                RegimeStats s = r.stats;
                b.append(csv(s.scope)).append(',')
                        .append(csv(s.strategy)).append(',')
                        .append(csv(s.regimeKey)).append(',')
                        .append(csv(r.decision)).append(',')
                        .append(fmt(r.multiplier)).append(',')
                        .append(s.trades).append(',')
                        .append(s.wins).append(',')
                        .append(s.losses).append(',')
                        .append(fmt(s.totalPnlDollars)).append(',')
                        .append(fmt(s.expectancyDollars())).append(',')
                        .append(fmt(s.expectancyPercent())).append(',')
                        .append(fmt(s.winRate())).append(',')
                        .append(fmt(s.profitFactor())).append(',')
                        .append(fmt(s.maxEquityDrawdownDollars)).append(',')
                        .append(csv(s.sourceSummary())).append(',')
                        .append(csv(r.reason))
                        .append('\n');
            }
            Files.writeString(matrixPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("REGIME STRATEGY MATRIX WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeReport(List<Recommendation> recommendations, int historicalSamples, int journalSamples) {
        try {
            ensureParent(reportPath);
            DecimalFormat df = new DecimalFormat("0.0000");
            int enabled = 0;
            int disabled = 0;
            int watch = 0;
            int insufficient = 0;
            for (Recommendation r : recommendations) {
                if ("ENABLE_REGIME".equals(r.decision)) enabled++;
                else if ("DISABLE_REGIME".equals(r.decision)) disabled++;
                else if ("INSUFFICIENT".equals(r.decision)) insufficient++;
                else watch++;
            }

            StringBuilder b = new StringBuilder();
            b.append("REGIME-TAGGED STRATEGY PERFORMANCE REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("policyPath=").append(policyPath).append('\n');
            b.append("matrixPath=").append(matrixPath).append('\n');
            b.append("historicalTradePaths=").append(historicalTradePaths).append('\n');
            b.append("outcomePaths=").append(outcomePaths).append('\n');
            b.append("historicalSamples=").append(historicalSamples).append('\n');
            b.append("journalSamples=").append(journalSamples).append('\n');
            b.append("recommendations=").append(recommendations.size()).append('\n');
            b.append("enableRegimes=").append(enabled).append('\n');
            b.append("disableRegimes=").append(disabled).append('\n');
            b.append("watchRegimes=").append(watch).append('\n');
            b.append("insufficientRegimes=").append(insufficient).append('\n');
            b.append("noLiveOrdersPlaced=true\n\n");

            b.append("SESSION POLICY ROWS\n");
            b.append("strategy,regimeKey,decision,multiplier,trades,winRate,profitFactor,expectancyDollars,pnlDollars,reason\n");
            for (Recommendation r : recommendations) {
                if (!"SESSION".equals(r.stats.scope)) {
                    continue;
                }
                RegimeStats s = r.stats;
                b.append(s.strategy).append(',')
                        .append(s.regimeKey).append(',')
                        .append(r.decision).append(',')
                        .append(df.format(r.multiplier)).append(',')
                        .append(s.trades).append(',')
                        .append(df.format(s.winRate())).append(',')
                        .append(df.format(s.profitFactor())).append(',')
                        .append(df.format(s.expectancyDollars())).append(',')
                        .append(df.format(s.totalPnlDollars)).append(',')
                        .append(clean(r.reason))
                        .append('\n');
            }

            b.append("\nTOP CONTEXT EDGES\n");
            recommendations.stream()
                    .filter(r -> "CONTEXT".equals(r.stats.scope))
                    .filter(r -> "ENABLE_REGIME".equals(r.decision))
                    .limit(25)
                    .forEach(r -> appendRecommendationLine(b, r, df));

            b.append("\nCONTEXT LOSS VETOES\n");
            recommendations.stream()
                    .filter(r -> "CONTEXT".equals(r.stats.scope))
                    .filter(r -> "DISABLE_REGIME".equals(r.decision))
                    .limit(25)
                    .forEach(r -> appendRecommendationLine(b, r, df));

            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("REGIME STRATEGY REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private static void appendRecommendationLine(StringBuilder b, Recommendation r, DecimalFormat df) {
        RegimeStats s = r.stats;
        b.append("- ").append(s.strategy)
                .append(" ").append(s.regimeKey)
                .append(" decision=").append(r.decision)
                .append(" multiplier=").append(df.format(r.multiplier))
                .append(" trades=").append(s.trades)
                .append(" winRate=").append(df.format(s.winRate()))
                .append(" profitFactor=").append(df.format(s.profitFactor()))
                .append(" expectancyDollars=").append(df.format(s.expectancyDollars()))
                .append(" pnlDollars=").append(df.format(s.totalPnlDollars))
                .append('\n');
    }

    private void writeHealth(List<Recommendation> recommendations, int historicalSamples, int journalSamples) {
        try {
            ensureParent(healthPath);
            int enabled = 0;
            int disabled = 0;
            int watch = 0;
            int insufficient = 0;
            int sessionRows = 0;
            int contextRows = 0;
            for (Recommendation r : recommendations) {
                if ("ENABLE_REGIME".equals(r.decision)) enabled++;
                else if ("DISABLE_REGIME".equals(r.decision)) disabled++;
                else if ("INSUFFICIENT".equals(r.decision)) insufficient++;
                else watch++;
                if ("SESSION".equals(r.stats.scope)) sessionRows++;
                else if ("CONTEXT".equals(r.stats.scope)) contextRows++;
            }
            Properties p = new Properties();
            p.setProperty("status", "PASS");
            p.setProperty("generatedAt", Instant.now().toString());
            p.setProperty("policyPath", policyPath.toString());
            p.setProperty("reportPath", reportPath.toString());
            p.setProperty("matrixPath", matrixPath.toString());
            p.setProperty("historicalSamples", Integer.toString(historicalSamples));
            p.setProperty("journalSamples", Integer.toString(journalSamples));
            p.setProperty("recommendations", Integer.toString(recommendations.size()));
            p.setProperty("sessionRows", Integer.toString(sessionRows));
            p.setProperty("contextRows", Integer.toString(contextRows));
            p.setProperty("enabled", Integer.toString(enabled));
            p.setProperty("disabled", Integer.toString(disabled));
            p.setProperty("watch", Integer.toString(watch));
            p.setProperty("insufficient", Integer.toString(insufficient));
            try (OutputStream out = Files.newOutputStream(healthPath)) {
                p.store(out, "Regime-tagged strategy performance health");
            }
        } catch (IOException e) {
            System.out.println("REGIME STRATEGY HEALTH WRITE FAILED: " + e.getMessage());
        }
    }

    private static void record(Map<String, RegimeStats> stats,
                               String strategy,
                               String regimeKey,
                               String scope,
                               double pnlDollars,
                               double pnlPercent,
                               String source) {
        String cleanStrategy = normalizeStrategy(strategy);
        String cleanRegime = safeKey(regimeKey);
        String cleanScope = normalize(scope);
        String key = cleanScope + "|" + cleanStrategy + "|" + cleanRegime;
        stats.computeIfAbsent(key, unused -> new RegimeStats(cleanStrategy, cleanRegime, cleanScope))
                .record(pnlDollars, pnlPercent, source);
    }

    private static String metrics(RegimeStats s) {
        return "trades=" + s.trades +
                " winRate=" + fmt(s.winRate()) +
                " expectancyDollars=" + fmt(s.expectancyDollars()) +
                " expectancyPercent=" + fmt(s.expectancyPercent()) +
                " profitFactor=" + fmt(s.profitFactor()) +
                " pnlDollars=" + fmt(s.totalPnlDollars) +
                " maxEquityDrawdownDollars=" + fmt(s.maxEquityDrawdownDollars);
    }

    private static String sessionFor(long epochMs) {
        if (epochMs <= 0L) {
            return "UNKNOWN";
        }
        LocalTime t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), MARKET_ZONE).toLocalTime();
        if (!t.isBefore(LocalTime.of(4, 0)) && t.isBefore(LocalTime.of(9, 30))) {
            return "PRE_MARKET";
        }
        if (!t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0))) {
            return "REGULAR";
        }
        if (!t.isBefore(LocalTime.of(16, 0)) && t.isBefore(LocalTime.of(20, 0))) {
            return "AFTER_HOURS";
        }
        return "CLOSED";
    }

    private static String sideBucket(String raw) {
        String value = normalize(raw);
        if (value.contains("SHORT") || value.equals("SELL")) {
            return "SHORT";
        }
        if (value.contains("LONG") || value.contains("BUY")) {
            return "LONG";
        }
        return "UNKNOWN";
    }

    private String catalystBucket(double catalystScore, String source, String headline, double newsAgeMinutes) {
        boolean hasNews = (source != null && !source.isBlank()) || (headline != null && !headline.isBlank());
        if (catalystScore >= config.strongCatalystScore) {
            return "STRONG";
        }
        if (catalystScore >= config.minCatalystScore || hasNews) {
            if (newsAgeMinutes > config.staleNewsMinutes && newsAgeMinutes > 0.0) {
                return "STALE_NEWS";
            }
            return "NEWS";
        }
        return "NONE";
    }

    private String moveBucket(double expectedMove) {
        double abs = Math.abs(expectedMove);
        if (abs >= config.highExpectedMove) {
            return "HIGH";
        }
        if (abs >= config.normalExpectedMove) {
            return "NORMAL";
        }
        return "LOW";
    }

    private static boolean isClosedOutcome(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        if ("CLOSE".equals(eventType) || "EXIT".equals(eventType) || "CLOSED".equals(eventType)) {
            return true;
        }
        return "PARTIAL_EXIT".equals(eventType) && envBool("REGIME_STRATEGY_INCLUDE_PARTIAL_EXITS", false);
    }

    private static List<Path> parsePaths(String raw) {
        List<Path> paths = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return paths;
        }
        for (String part : raw.split("[;,]")) {
            if (part != null && !part.isBlank()) {
                paths.add(Path.of(part.trim()));
            }
        }
        return paths;
    }

    private static String defaultHistoricalTradePaths() {
        String primary = env("BAR_BY_BAR_SIMULATION_TRADES_PATH", "logs/bar_by_bar_simulation_trades.csv");
        String retest = env("BAR_BY_BAR_CANDIDATE_RETEST_TRADES_PATH", "logs/bar_by_bar_candidate_retest_trades.csv");
        return primary + ";" + retest;
    }

    private static String defaultOutcomePaths() {
        String primary = env("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv");
        String shadow = env("SHADOW_TRADE_OUTCOME_PATH", "logs/shadow_trade_outcomes.csv");
        return primary + ";" + shadow;
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

    private static String csv(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.contains(",") && !value.contains("\"") && !value.contains("\n") && !value.contains("\r")) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static long parseEpochMillis(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        try {
            if (value.matches("-?\\d+")) {
                long parsed = Long.parseLong(value);
                return parsed < 10_000_000_000L ? parsed * 1000L : parsed;
            }
            return Instant.parse(value).toEpochMilli();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeKey(String raw) {
        String normalized = normalize(raw);
        if (normalized.isBlank()) {
            return "UNKNOWN";
        }
        return normalized.replaceAll("[^A-Z0-9_]+", "_").replaceAll("_+", "_");
    }

    private static boolean isUnknownStrategy(String strategy) {
        String normalized = normalizeStrategy(strategy);
        return normalized.isBlank() || "UNKNOWN".equals(normalized) || "BROKER_SYNC".equals(normalized);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
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

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            value = 0.0;
        }
        return String.format(Locale.ROOT, "%.6f", value);
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

    private static final class RegimeStats {
        final String strategy;
        final String regimeKey;
        final String scope;
        final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        int trades;
        int wins;
        int losses;
        double totalPnlDollars;
        double totalPnlPercent;
        double grossProfit;
        double grossLoss;
        double equity;
        double peakEquity;
        double maxEquityDrawdownDollars;

        RegimeStats(String strategy, String regimeKey, String scope) {
            this.strategy = strategy;
            this.regimeKey = regimeKey;
            this.scope = scope;
        }

        void record(double pnlDollars, double pnlPercent, String source) {
            trades++;
            totalPnlDollars += pnlDollars;
            totalPnlPercent += pnlPercent;
            double edge = Math.abs(pnlDollars) > 0.000001 ? pnlDollars : pnlPercent;
            if (edge > 0.0) {
                wins++;
                grossProfit += Math.abs(edge);
            } else if (edge < 0.0) {
                losses++;
                grossLoss += Math.abs(edge);
            }
            equity += pnlDollars;
            peakEquity = Math.max(peakEquity, equity);
            maxEquityDrawdownDollars = Math.max(maxEquityDrawdownDollars, peakEquity - equity);
            String cleanSource = source == null || source.isBlank() ? "unknown" : source;
            sourceCounts.merge(cleanSource, 1, Integer::sum);
        }

        double winRate() {
            return trades <= 0 ? 0.0 : (double) wins / trades;
        }

        double expectancyDollars() {
            return trades <= 0 ? 0.0 : totalPnlDollars / trades;
        }

        double expectancyPercent() {
            return trades <= 0 ? 0.0 : totalPnlPercent / trades;
        }

        double profitFactor() {
            if (grossLoss <= 0.0) {
                return grossProfit > 0.0 ? 99.0 : 0.0;
            }
            return grossProfit / grossLoss;
        }

        String sourceSummary() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
                if (b.length() > 0) {
                    b.append(';');
                }
                b.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return b.toString();
        }
    }

    private static final class Recommendation {
        final RegimeStats stats;
        final String decision;
        final double multiplier;
        final boolean disabled;
        final String reason;

        Recommendation(RegimeStats stats, String decision, double multiplier, boolean disabled, String reason) {
            this.stats = stats;
            this.decision = decision;
            this.multiplier = multiplier;
            this.disabled = disabled;
            this.reason = reason == null ? "" : reason;
        }

        int decisionRank() {
            if ("ENABLE_REGIME".equals(decision)) return 4;
            if ("WATCH_REGIME".equals(decision)) return 3;
            if ("INSUFFICIENT".equals(decision)) return 2;
            if ("DISABLE_REGIME".equals(decision)) return 1;
            return 0;
        }

        int scopeRank() {
            return "SESSION".equals(stats.scope) ? 2 : 1;
        }

        double qualityScore() {
            return (stats.expectancyDollars() * 0.10)
                    + (stats.expectancyPercent() * 100.0)
                    + (stats.winRate() * 0.25)
                    + Math.min(3.0, stats.profitFactor()) * 0.10
                    - (stats.maxEquityDrawdownDollars * 0.001);
        }
    }

    static final class Config {
        final int minSamples;
        final int minPromoteSamples;
        final int minDisableSamples;
        final double minExpectancyDollars;
        final double minExpectancyPercent;
        final double minWinRate;
        final double minProfitFactor;
        final double weakWinRate;
        final double weakProfitFactor;
        final double disableExpectancyDollars;
        final double disableExpectancyPercent;
        final double disableWinRate;
        final double disableProfitFactor;
        final double maxPromoteMultiplier;
        final double minShrinkMultiplier;
        final double disableMultiplier;
        final double minCatalystScore;
        final double strongCatalystScore;
        final double staleNewsMinutes;
        final double normalExpectedMove;
        final double highExpectedMove;
        final Set<String> protectedDisableStrategies;

        Config(int minSamples,
               int minPromoteSamples,
               int minDisableSamples,
               double minExpectancyDollars,
               double minExpectancyPercent,
               double minWinRate,
               double minProfitFactor,
               double weakWinRate,
               double weakProfitFactor,
               double disableExpectancyDollars,
               double disableExpectancyPercent,
               double disableWinRate,
               double disableProfitFactor,
               double maxPromoteMultiplier,
               double minShrinkMultiplier,
               double disableMultiplier,
               double minCatalystScore,
               double strongCatalystScore,
               double staleNewsMinutes,
               double normalExpectedMove,
               double highExpectedMove,
               Set<String> protectedDisableStrategies) {
            this.minSamples = Math.max(3, minSamples);
            this.minPromoteSamples = Math.max(this.minSamples, minPromoteSamples);
            this.minDisableSamples = Math.max(this.minSamples, minDisableSamples);
            this.minExpectancyDollars = minExpectancyDollars;
            this.minExpectancyPercent = minExpectancyPercent;
            this.minWinRate = minWinRate;
            this.minProfitFactor = minProfitFactor;
            this.weakWinRate = weakWinRate;
            this.weakProfitFactor = weakProfitFactor;
            this.disableExpectancyDollars = disableExpectancyDollars;
            this.disableExpectancyPercent = disableExpectancyPercent;
            this.disableWinRate = disableWinRate;
            this.disableProfitFactor = disableProfitFactor;
            this.maxPromoteMultiplier = maxPromoteMultiplier;
            this.minShrinkMultiplier = minShrinkMultiplier;
            this.disableMultiplier = disableMultiplier;
            this.minCatalystScore = minCatalystScore;
            this.strongCatalystScore = strongCatalystScore;
            this.staleNewsMinutes = staleNewsMinutes;
            this.normalExpectedMove = normalExpectedMove;
            this.highExpectedMove = highExpectedMove;
            this.protectedDisableStrategies = protectedDisableStrategies == null ? Set.of() : Set.copyOf(protectedDisableStrategies);
        }

        static Config fromEnv() {
            Set<String> protectedStrategies = new HashSet<>();
            for (String raw : env("REGIME_STRATEGY_PROTECTED_DISABLE_STRATEGIES",
                    "MARKET_INTELLIGENCE_AI,AI_GOVERNOR_STATE_OPPORTUNITY").split(",")) {
                if (raw != null && !raw.isBlank()) {
                    protectedStrategies.add(normalizeStrategy(raw));
                }
            }
            return new Config(
                    envInt("REGIME_STRATEGY_MIN_SAMPLES", 10),
                    envInt("REGIME_STRATEGY_MIN_PROMOTE_SAMPLES", 25),
                    envInt("REGIME_STRATEGY_MIN_DISABLE_SAMPLES", 25),
                    envDouble("REGIME_STRATEGY_MIN_EXPECTANCY_DOLLARS", 0.02),
                    envDouble("REGIME_STRATEGY_MIN_EXPECTANCY_PERCENT", 0.0003),
                    envDouble("REGIME_STRATEGY_MIN_WIN_RATE", 0.50),
                    envDouble("REGIME_STRATEGY_MIN_PROFIT_FACTOR", 1.15),
                    envDouble("REGIME_STRATEGY_WEAK_WIN_RATE", 0.43),
                    envDouble("REGIME_STRATEGY_WEAK_PROFIT_FACTOR", 0.90),
                    envDouble("REGIME_STRATEGY_DISABLE_EXPECTANCY_DOLLARS", 0.03),
                    envDouble("REGIME_STRATEGY_DISABLE_EXPECTANCY_PERCENT", 0.0005),
                    envDouble("REGIME_STRATEGY_DISABLE_WIN_RATE", 0.40),
                    envDouble("REGIME_STRATEGY_DISABLE_PROFIT_FACTOR", 0.80),
                    envDouble("REGIME_STRATEGY_MAX_PROMOTE_MULTIPLIER", 1.30),
                    envDouble("REGIME_STRATEGY_MIN_SHRINK_MULTIPLIER", 0.55),
                    envDouble("REGIME_STRATEGY_DISABLE_MULTIPLIER", 0.50),
                    envDouble("REGIME_STRATEGY_MIN_CATALYST_SCORE", 0.25),
                    envDouble("REGIME_STRATEGY_STRONG_CATALYST_SCORE", 0.65),
                    envDouble("REGIME_STRATEGY_STALE_NEWS_MINUTES", 90.0),
                    envDouble("REGIME_STRATEGY_NORMAL_EXPECTED_MOVE", 0.015),
                    envDouble("REGIME_STRATEGY_HIGH_EXPECTED_MOVE", 0.035),
                    protectedStrategies
            );
        }
    }

    public static final class Result {
        public final int regimes;
        public final int recommendations;
        public final int historicalSamples;
        public final int journalSamples;
        public final int enabled;
        public final int disabled;
        public final int watched;
        public final Path policyPath;
        public final Path reportPath;
        public final Path matrixPath;
        public final Path healthPath;

        Result(int regimes,
               int recommendations,
               int historicalSamples,
               int journalSamples,
               int enabled,
               int disabled,
               int watched,
               Path policyPath,
               Path reportPath,
               Path matrixPath,
               Path healthPath) {
            this.regimes = regimes;
            this.recommendations = recommendations;
            this.historicalSamples = historicalSamples;
            this.journalSamples = journalSamples;
            this.enabled = enabled;
            this.disabled = disabled;
            this.watched = watched;
            this.policyPath = policyPath;
            this.reportPath = reportPath;
            this.matrixPath = matrixPath;
            this.healthPath = healthPath;
        }

        public String summary() {
            return "regimes=" + regimes +
                    " recommendations=" + recommendations +
                    " historicalSamples=" + historicalSamples +
                    " journalSamples=" + journalSamples +
                    " enabled=" + enabled +
                    " disabled=" + disabled +
                    " watched=" + watched +
                    " policyPath=" + policyPath +
                    " reportPath=" + reportPath;
        }
    }
}
