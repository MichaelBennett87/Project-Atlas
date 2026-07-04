package com.bot.intelligence;

import com.bot.master.MasterStrategyDecision;
import com.bot.master.MasterStrategyEngine;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.master.TradingStrategy;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.risk.MarketHoursService;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live-hours sampler for watchlisted strategies.
 *
 * This evaluates only candidate strategies that the retest queue marked
 * KEEP_WATCHING. It never sends broker orders; actionable signals are written
 * into ShadowTradeJournal so the paper gate can build real-time evidence.
 */
public final class WatchlistShadowSampler {
    private static final WatchlistShadowSampler INSTANCE = new WatchlistShadowSampler();

    private final Path policyPath = Path.of(env("CANDIDATE_RETEST_QUEUE_POLICY_PATH", "logs/candidate_retest_queue_policy.properties"));
    private final Path healthPath = Path.of(env("WATCHLIST_SHADOW_SAMPLER_HEALTH_PATH", "logs/watchlist_shadow_sampler_health.properties"));
    private final Path eventPath = Path.of(env("WATCHLIST_SHADOW_SAMPLER_EVENTS_PATH", "logs/watchlist_shadow_sampler_events.csv"));
    private final boolean enabled = envBool("WATCHLIST_SHADOW_SAMPLER_ENABLED", true);
    private final boolean requireMarketOpen = envBool("WATCHLIST_SHADOW_SAMPLER_REQUIRE_MARKET_OPEN", true);
    private final boolean journalAllEvaluations = envBool("WATCHLIST_SHADOW_SAMPLER_JOURNAL_ALL_EVALUATIONS", false);
    private final long reloadIntervalMs = Math.max(1_000L, envLong("WATCHLIST_SHADOW_SAMPLER_RELOAD_MS", 30_000L));
    private final long minSignalCooldownMs = Math.max(0L, envLong("WATCHLIST_SHADOW_SAMPLER_MIN_SIGNAL_MS", 120_000L));
    private final int maxStrategiesPerEvent = Math.max(1, envInt("WATCHLIST_SHADOW_SAMPLER_MAX_STRATEGIES_PER_EVENT", 12));
    private final double minConfidence = clamp(envDouble("WATCHLIST_SHADOW_SAMPLER_MIN_CONFIDENCE", 0.35), 0.0, 1.0);
    private final MarketHoursService marketHoursService = new MarketHoursService();
    private final ShadowTradeJournal shadowTradeJournal = ShadowTradeJournal.getInstance();
    private final Map<String, TradingStrategy> strategiesByName = new LinkedHashMap<>();
    private final Map<String, Long> sampledAtByKey = new ConcurrentHashMap<>();

    private volatile Set<String> activeStrategies = Set.of();
    private volatile long lastReloadAttemptMs = 0L;
    private volatile long loadedPolicyModifiedMs = -1L;
    private volatile long sampledSignals = 0L;
    private volatile long evaluatedSignals = 0L;
    private volatile String lastStatus = "INIT";
    private volatile String lastDetail = "";

    private WatchlistShadowSampler() {
        for (TradingStrategy strategy : MasterStrategyEngine.defaultStrategies()) {
            if (strategy != null && strategy.name() != null && !strategy.name().isBlank()) {
                strategiesByName.put(normalizeStrategy(strategy.name()), strategy);
            }
        }
        ensureEventHeader();
        writeHealth("INIT", "enabled=" + enabled + " strategies=" + strategiesByName.size());
    }

    public static WatchlistShadowSampler getInstance() {
        return INSTANCE;
    }

    public SampleResult onMarketEvent(String ticker,
                                      MarketDataCache marketData,
                                      NewsEvent news,
                                      double referencePrice,
                                      double accountEquity,
                                      String trigger) {
        String normalizedTicker = normalizeTicker(ticker);
        if (!enabled) {
            return SampleResult.skipped("DISABLED");
        }
        if (normalizedTicker.isBlank()) {
            return SampleResult.skipped("BLANK_TICKER");
        }
        if (requireMarketOpen && !marketHoursService.isExtendedMarketOpenNow()) {
            writeHealthThrottled("MARKET_CLOSED", "session=" + marketHoursService.currentSessionName());
            return SampleResult.skipped("MARKET_CLOSED");
        }
        reloadIfNeeded();
        Set<String> strategyNames = activeStrategies;
        if (strategyNames.isEmpty()) {
            writeHealthThrottled("NO_ACTIVE_CANDIDATES", "policy=" + policyPath);
            return SampleResult.skipped("NO_ACTIVE_CANDIDATES");
        }

        double price = referencePrice > 0.0 && Double.isFinite(referencePrice)
                ? referencePrice
                : latestPrice(marketData, normalizedTicker);
        if (price <= 0.0 || !Double.isFinite(price)) {
            writeHealthThrottled("NO_REFERENCE_PRICE", "ticker=" + normalizedTicker);
            return SampleResult.skipped("NO_REFERENCE_PRICE");
        }

        StrategyContext context = new StrategyContext(
                normalizedTicker,
                marketData,
                news,
                null,
                price,
                accountEquity > 0.0 && Double.isFinite(accountEquity) ? accountEquity : 100_000.0
        );

        int evaluated = 0;
        int sampled = 0;
        int missing = 0;
        List<String> sampledStrategies = new ArrayList<>();
        for (String strategyName : strategyNames) {
            if (evaluated >= maxStrategiesPerEvent) {
                break;
            }
            TradingStrategy strategy = strategiesByName.get(strategyName);
            if (strategy == null) {
                missing++;
                continue;
            }
            evaluated++;
            try {
                StrategySignal signal = strategy.evaluate(context);
                evaluatedSignals++;
                boolean actionable = signal != null
                        && signal.isActionableBuy()
                        && normalizeTicker(signal.getTicker()).equals(normalizedTicker)
                        && signal.getConfidence() >= minConfidence;
                if (!actionable) {
                    if (journalAllEvaluations) {
                        appendEvent(trigger, normalizedTicker, strategyName, signal, false, "NO_ACTIONABLE_SIGNAL");
                    }
                    continue;
                }

                String key = normalizedTicker + "|" + normalizeStrategy(signal.getStrategyName()) + "|" + signal.getDirection();
                long now = System.currentTimeMillis();
                Long lastSampledAt = sampledAtByKey.get(key);
                if (lastSampledAt != null && now - lastSampledAt < minSignalCooldownMs) {
                    if (journalAllEvaluations) {
                        appendEvent(trigger, normalizedTicker, strategyName, signal, false, "COOLDOWN");
                    }
                    continue;
                }

                sampledAtByKey.put(key, now);
                MasterStrategyDecision decision = MasterStrategyDecision.buy(
                        normalizedTicker,
                        signal,
                        List.of(signal),
                        "WATCHLIST_SHADOW_SAMPLE strategy=" + signal.getStrategyName() +
                                " sourcePolicy=" + policyPath +
                                " trigger=" + safe(trigger)
                );
                shadowTradeJournal.observePrice(normalizedTicker, price);
                shadowTradeJournal.recordDecision(decision, news, "WATCHLIST_SHADOW_SAMPLER_" + safe(trigger),
                        price, false);
                sampled++;
                sampledSignals++;
                sampledStrategies.add(normalizeStrategy(signal.getStrategyName()));
                appendEvent(trigger, normalizedTicker, strategyName, signal, true, "SAMPLED");
            } catch (Exception e) {
                appendEvent(trigger, normalizedTicker, strategyName, null, false, "ERROR:" + safe(e.getMessage()));
            }
        }

        String detail = "ticker=" + normalizedTicker +
                " trigger=" + safe(trigger) +
                " activeStrategies=" + strategyNames.size() +
                " evaluated=" + evaluated +
                " sampled=" + sampled +
                " missing=" + missing;
        writeHealth("PASS", detail);
        return new SampleResult(evaluated, sampled, missing, sampledStrategies, detail);
    }

    private void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastReloadAttemptMs < reloadIntervalMs) {
            return;
        }
        lastReloadAttemptMs = now;
        try {
            long modifiedMs = Files.exists(policyPath)
                    ? Files.getLastModifiedTime(policyPath).toMillis()
                    : -1L;
            if (modifiedMs == loadedPolicyModifiedMs) {
                return;
            }
            loadedPolicyModifiedMs = modifiedMs;
            Properties p = new Properties();
            if (Files.exists(policyPath)) {
                try (var reader = Files.newBufferedReader(policyPath, StandardCharsets.UTF_8)) {
                    p.load(reader);
                }
            }
            Set<String> keepWatching = new LinkedHashSet<>();
            for (String key : p.stringPropertyNames()) {
                String prefix = "candidateRetest.strategy.";
                String suffix = ".decision";
                if (!key.startsWith(prefix) || !key.endsWith(suffix)) {
                    continue;
                }
                String decision = p.getProperty(key, "").trim().toUpperCase(Locale.ROOT);
                if (!"KEEP_WATCHING".equals(decision)) {
                    continue;
                }
                String strategy = key.substring(prefix.length(), key.length() - suffix.length());
                strategy = normalizeStrategy(strategy);
                if (!strategy.isBlank()) {
                    keepWatching.add(strategy);
                }
            }
            activeStrategies = Set.copyOf(keepWatching);
            writeHealth("RELOADED", "activeStrategies=" + activeStrategies.size() + " policy=" + policyPath);
        } catch (Exception e) {
            writeHealth("RELOAD_FAILED", safe(e.getMessage()));
        }
    }

    private double latestPrice(MarketDataCache marketData, String ticker) {
        try {
            double price = marketData == null ? 0.0 : marketData.latestClose(ticker);
            return price > 0.0 && Double.isFinite(price) ? price : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void ensureEventHeader() {
        try {
            Path parent = eventPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(eventPath)) {
                Files.writeString(eventPath,
                        "timestamp,trigger,ticker,strategy,signalAction,confidence,expectedMovePercent,quantity,sampled,reason,signalReason" +
                                System.lineSeparator(),
                        StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.out.println("WATCHLIST SHADOW SAMPLER INIT FAILED: " + e.getMessage());
        }
    }

    private void appendEvent(String trigger,
                             String ticker,
                             String strategy,
                             StrategySignal signal,
                             boolean sampled,
                             String reason) {
        try {
            Path parent = eventPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(eventPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(csv(Instant.now().toString()));
                writer.write(',');
                writer.write(csv(trigger));
                writer.write(',');
                writer.write(csv(ticker));
                writer.write(',');
                writer.write(csv(strategy));
                writer.write(',');
                writer.write(csv(signal == null || signal.getAction() == null ? "" : signal.getAction().name()));
                writer.write(',');
                writer.write(fmt(signal == null ? 0.0 : signal.getConfidence()));
                writer.write(',');
                writer.write(fmt(signal == null ? 0.0 : signal.getExpectedMovePercent()));
                writer.write(',');
                writer.write(Integer.toString(signal == null ? 0 : signal.getSuggestedQuantity()));
                writer.write(',');
                writer.write(Boolean.toString(sampled));
                writer.write(',');
                writer.write(csv(reason));
                writer.write(',');
                writer.write(csv(signal == null ? "" : signal.getReason()));
                writer.newLine();
            }
        } catch (Exception e) {
            System.out.println("WATCHLIST SHADOW SAMPLER EVENT WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeHealthThrottled(String status, String detail) {
        long now = System.currentTimeMillis();
        if (now - lastReloadAttemptMs < Math.min(reloadIntervalMs, 10_000L)
                && status.equals(lastStatus)
                && detail.equals(lastDetail)) {
            return;
        }
        writeHealth(status, detail);
    }

    private void writeHealth(String status, String detail) {
        lastStatus = status == null ? "" : status;
        lastDetail = detail == null ? "" : detail;
        try {
            Path parent = healthPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties p = new Properties();
            p.setProperty("status", lastStatus);
            p.setProperty("updatedAt", Instant.now().toString());
            p.setProperty("enabled", Boolean.toString(enabled));
            p.setProperty("requireMarketOpen", Boolean.toString(requireMarketOpen));
            p.setProperty("policyPath", policyPath.toString());
            p.setProperty("eventPath", eventPath.toString());
            p.setProperty("activeStrategies", Integer.toString(activeStrategies.size()));
            p.setProperty("knownStrategies", Integer.toString(strategiesByName.size()));
            p.setProperty("sampledSignals", Long.toString(sampledSignals));
            p.setProperty("evaluatedSignals", Long.toString(evaluatedSignals));
            p.setProperty("lastDetail", lastDetail);
            try (OutputStream out = Files.newOutputStream(healthPath)) {
                p.store(out, "Watchlist shadow sampler health. Samples never place live orders.");
            }
        } catch (Exception e) {
            System.out.println("WATCHLIST SHADOW SAMPLER HEALTH WRITE FAILED: " + e.getMessage());
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = env(key, "");
        if (value.isBlank()) {
            return fallback;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static String normalizeStrategy(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String normalizeTicker(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String csv(String value) {
        String safe = safe(value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", Double.isFinite(value) ? value : 0.0);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static final class SampleResult {
        public final int evaluated;
        public final int sampled;
        public final int missingStrategies;
        public final List<String> sampledStrategies;
        public final String reason;

        private SampleResult(int evaluated, int sampled, int missingStrategies,
                             List<String> sampledStrategies, String reason) {
            this.evaluated = evaluated;
            this.sampled = sampled;
            this.missingStrategies = missingStrategies;
            this.sampledStrategies = sampledStrategies == null ? List.of() : List.copyOf(sampledStrategies);
            this.reason = reason == null ? "" : reason;
        }

        static SampleResult skipped(String reason) {
            return new SampleResult(0, 0, 0, List.of(), reason);
        }
    }
}
