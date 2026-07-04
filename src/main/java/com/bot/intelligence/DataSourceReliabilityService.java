package com.bot.intelligence;

import com.bot.intelligence.bus.MarketIntelligenceSignal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scores data sources like agents. Providers that repeatedly produce early,
 * actionable, non-duplicate signals can be weighted higher by the optimizer;
 * noisy providers can be de-emphasized without being removed from the system.
 */
public final class DataSourceReliabilityService {
    private static final DataSourceReliabilityService INSTANCE = new DataSourceReliabilityService();

    private final Path weightPath = Paths.get(System.getenv().getOrDefault(
            "DATA_SOURCE_RELIABILITY_PATH", "logs/data_source_reliability.properties"));
    private final Path journalPath = Paths.get(System.getenv().getOrDefault(
            "DATA_SOURCE_RELIABILITY_JOURNAL", "logs/data_source_reliability.csv"));
    private final Map<String, Double> weights = new ConcurrentHashMap<>();
    private final Map<String, ProviderStats> stats = new ConcurrentHashMap<>();
    private final boolean enabled = envBoolean("DATA_SOURCE_RELIABILITY_ENABLED", true);

    private DataSourceReliabilityService() {
        loadWeights();
        if (enabled) {
            System.out.println("DATA SOURCE RELIABILITY SERVICE READY: weights=" + weights.size() +
                    " path=" + weightPath + " journal=" + journalPath);
        }
    }

    public static DataSourceReliabilityService getInstance() {
        return INSTANCE;
    }

    public double weightFor(String provider) {
        if (!enabled) return 1.0;
        String key = normalize(provider);
        Double configured = weights.get(key);
        if (configured != null) return Math.max(0.25, Math.min(1.75, configured));
        ProviderStats stat = stats.get(key);
        if (stat == null || stat.total <= 0) return 1.0;
        double duplicatePenalty = stat.duplicates * 1.0 / Math.max(1, stat.total);
        double acceptedBoost = stat.accepted * 1.0 / Math.max(1, stat.total);
        return Math.max(0.60, Math.min(1.30, 1.0 + acceptedBoost * 0.20 - duplicatePenalty * 0.25));
    }

    public void observeSignal(MarketIntelligenceSignal signal, boolean duplicate) {
        if (!enabled || signal == null) return;
        String provider = normalize(signal.getProvider());
        ProviderStats stat = stats.computeIfAbsent(provider, ProviderStats::new);
        stat.total++;
        if (duplicate) stat.duplicates++;
        if (!duplicate && signal.getPriority() >= 0.50) stat.accepted++;
        journal(signal, duplicate, stat);
    }

    public synchronized void updateWeight(String provider, double weight, String reason) {
        if (provider == null || provider.isBlank()) return;
        weights.put(normalize(provider), Math.max(0.25, Math.min(1.75, weight)));
        persistWeights(reason);
    }

    private void journal(MarketIntelligenceSignal signal, boolean duplicate, ProviderStats stat) {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            boolean newFile = !Files.exists(journalPath) || Files.size(journalPath) == 0;
            try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (newFile) {
                    writer.write("timestamp,provider,ticker,type,priority,confidence,duplicate,total,accepted,duplicates,currentWeight,headline");
                    writer.newLine();
                }
                writer.write(String.join(",",
                        clean(Instant.now().toString()),
                        clean(signal.getProvider()),
                        clean(signal.getTicker()),
                        clean(signal.getType() == null ? "" : signal.getType().name()),
                        fmt(signal.getPriority()),
                        fmt(signal.getConfidence()),
                        clean(String.valueOf(duplicate)),
                        String.valueOf(stat.total),
                        String.valueOf(stat.accepted),
                        String.valueOf(stat.duplicates),
                        fmt(weightFor(signal.getProvider())),
                        clean(signal.getHeadline())
                ));
                writer.newLine();
            }
        } catch (Exception e) {
            if (envBoolean("DATA_SOURCE_RELIABILITY_VERBOSE_ERRORS", false)) {
                System.out.println("DATA SOURCE RELIABILITY JOURNAL WARNING: " + e.getMessage());
            }
        }
    }

    private void loadWeights() {
        weights.clear();
        if (!Files.exists(weightPath)) return;
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(weightPath)) {
            props.load(reader);
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("source.")) continue;
                try {
                    weights.put(normalize(key.substring("source.".length())), Double.parseDouble(props.getProperty(key).trim()));
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void persistWeights(String reason) {
        try {
            Path parent = weightPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Properties props = new Properties();
            for (Map.Entry<String, Double> entry : new LinkedHashMap<>(weights).entrySet()) {
                props.setProperty("source." + entry.getKey(), fmt(entry.getValue()));
            }
            props.setProperty("updatedAt", Instant.now().toString());
            props.setProperty("reason", reason == null ? "manual_or_optimizer_update" : reason);
            try (BufferedWriter writer = Files.newBufferedWriter(weightPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(writer, "Data source reliability weights");
            }
        } catch (IOException ignored) {
        }
    }

    private static final class ProviderStats {
        final String provider;
        long total;
        long accepted;
        long duplicates;
        ProviderStats(String provider) { this.provider = provider; }
    }

    private static String normalize(String provider) {
        return provider == null || provider.isBlank() ? "UNKNOWN" : provider.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
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
