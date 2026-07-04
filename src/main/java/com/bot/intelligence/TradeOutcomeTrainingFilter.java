package com.bot.intelligence;

import java.util.Locale;

/**
 * Keeps self-training focused on clean realized trade outcomes.
 */
public final class TradeOutcomeTrainingFilter {

    private TradeOutcomeTrainingFilter() {
    }

    public static boolean isTrainingEligible(String eventType, String strategyName, String syncedFromBroker) {
        String event = normalize(eventType);
        if ("PARTIAL_EXIT".equals(event) && !envBool("AI_TRAINING_INCLUDE_PARTIAL_EXITS", false)) {
            return false;
        }
        if (!"CLOSE".equals(event) && (!"PARTIAL_EXIT".equals(event) || !envBool("AI_TRAINING_INCLUDE_PARTIAL_EXITS", false))) {
            return false;
        }

        String strategy = normalizeStrategy(strategyName);
        if (isUnknownStrategy(strategy) && !envBool("AI_TRAINING_INCLUDE_UNKNOWN_STRATEGY", false)) {
            return false;
        }

        if (isTrue(syncedFromBroker) && !envBool("AI_TRAINING_INCLUDE_BROKER_SYNCED", false)) {
            return false;
        }

        return true;
    }

    public static String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    public static boolean isUnknownStrategy(String strategyName) {
        String strategy = normalizeStrategy(strategyName);
        return strategy.isBlank() || "UNKNOWN".equals(strategy) || "BROKER_SYNC".equals(strategy);
    }

    public static boolean isTrue(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }
}
