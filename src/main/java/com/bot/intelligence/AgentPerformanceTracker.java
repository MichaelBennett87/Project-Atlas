package com.bot.intelligence;

import com.bot.agents.AgentOpinion;
import com.bot.agents.AgentVote;
import com.bot.agents.MultiAgentTradeDecision;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling calibration layer for multi-agent opinions.
 *
 * The live committee should not assume every agent is equally reliable forever.
 * This service keeps a small persistent reliability table and journals each
 * opinion so the overnight optimizer can later compare confidence versus actual
 * outcome and update per-agent weights.
 */
public final class AgentPerformanceTracker {
    private static final AgentPerformanceTracker INSTANCE = new AgentPerformanceTracker();

    private final Path weightPath = Paths.get(System.getenv().getOrDefault(
            "AGENT_PERFORMANCE_WEIGHT_PATH", "logs/agent_performance_weights.properties"));
    private final Path journalPath = Paths.get(System.getenv().getOrDefault(
            "AGENT_PERFORMANCE_JOURNAL", "logs/agent_performance_journal.csv"));
    private final Map<String, Double> weights = new ConcurrentHashMap<>();
    private final boolean enabled = envBoolean("AGENT_PERFORMANCE_TRACKING_ENABLED", true);

    private AgentPerformanceTracker() {
        loadWeights();
        if (enabled) {
            System.out.println("AGENT PERFORMANCE TRACKER READY: weights=" + weights.size() +
                    " path=" + weightPath + " journal=" + journalPath);
        }
    }

    public static AgentPerformanceTracker getInstance() {
        return INSTANCE;
    }

    public AgentOpinion calibrate(AgentOpinion opinion) {
        if (!enabled || opinion == null) return opinion;
        double raw = opinion.getConfidence();
        double weight = weightFor(opinion.getAgentName());
        double calibrated = clamp(raw * weight);
        String reason = opinion.getReason() + " | agentReliabilityWeight=" + fmt(weight) +
                " calibratedConfidence=" + fmt(calibrated);
        return new AgentOpinion(opinion.getAgentName(), opinion.getVote(), calibrated, reason);
    }

    public double weightFor(String agentName) {
        String key = normalize(agentName);
        Double value = weights.get(key);
        if (value == null) return 1.0;
        return Math.max(0.25, Math.min(1.75, value));
    }

    public void recordCommitteeDecision(
            StrategyContext context,
            StrategySignal candidate,
            List<AgentOpinion> opinions,
            MultiAgentTradeDecision decision,
            double approvalScore
    ) {
        if (!enabled || opinions == null || opinions.isEmpty()) return;
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            boolean newFile = !Files.exists(journalPath) || Files.size(journalPath) == 0;
            try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (newFile) {
                    writer.write("timestamp,ticker,strategy,agent,vote,confidence,agentWeight,approvalScore,committeeApproved,reason");
                    writer.newLine();
                }
                for (AgentOpinion opinion : opinions) {
                    if (opinion == null) continue;
                    writer.write(String.join(",",
                            clean(Instant.now().toString()),
                            clean(context == null ? "" : context.getTicker()),
                            clean(candidate == null ? "" : candidate.getStrategyName()),
                            clean(opinion.getAgentName()),
                            clean(opinion.getVote() == null ? "" : opinion.getVote().name()),
                            fmt(opinion.getConfidence()),
                            fmt(weightFor(opinion.getAgentName())),
                            fmt(approvalScore),
                            clean(decision != null && decision.isApproved() ? "true" : "false"),
                            clean(opinion.getReason())
                    ));
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            if (envBoolean("AGENT_PERFORMANCE_VERBOSE_ERRORS", false)) {
                System.out.println("AGENT PERFORMANCE JOURNAL WARNING: " + e.getMessage());
            }
        }
    }

    public synchronized void updateWeight(String agentName, double newWeight, String reason) {
        if (agentName == null || agentName.isBlank()) return;
        weights.put(normalize(agentName), Math.max(0.25, Math.min(1.75, newWeight)));
        persistWeights(reason);
    }

    private void loadWeights() {
        weights.clear();
        if (!Files.exists(weightPath)) return;
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(weightPath)) {
            props.load(reader);
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("agent.")) continue;
                String agent = key.substring("agent.".length());
                try {
                    weights.put(normalize(agent), Double.parseDouble(props.getProperty(key).trim()));
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
                props.setProperty("agent." + entry.getKey(), fmt(entry.getValue()));
            }
            props.setProperty("updatedAt", Instant.now().toString());
            props.setProperty("reason", reason == null ? "manual_or_optimizer_update" : reason);
            try (BufferedWriter writer = Files.newBufferedWriter(weightPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(writer, "Agent reliability weights used by MultiAgentTradeCommittee");
            }
        } catch (IOException ignored) {
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN_AGENT" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
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

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
    }
}
