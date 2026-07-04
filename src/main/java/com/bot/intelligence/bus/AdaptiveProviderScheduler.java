package com.bot.intelligence.bus;

import com.bot.intelligence.EvidenceFusionEngine;

/**
 * Lightweight adaptive polling helper for external providers.
 * High-value tape / low provider diversity speeds up polling; quiet tape slows it down.
 */
public final class AdaptiveProviderScheduler {
    private AdaptiveProviderScheduler() {}

    public static long nextSleepMs(String providerName, long baseMs, int emittedSignals) {
        if (!enabled()) return Math.max(1_000L, baseMs);
        long minMs = envLong("ADAPTIVE_PROVIDER_MIN_POLL_MS", 5_000L);
        long maxMs = envLong("ADAPTIVE_PROVIDER_MAX_POLL_MS", Math.max(baseMs, 5L * 60L * 1000L));
        EvidenceFusionEngine.FusionMarketSnapshot fusion = EvidenceFusionEngine.getInstance().marketSnapshot(envLong("ADAPTIVE_PROVIDER_LOOKBACK_MS", 15L * 60L * 1000L));
        double heat = Math.max(fusion.catalystHeat(), Math.max(fusion.parabolicHeat(), fusion.liquidityScore()));
        double multiplier;
        if (emittedSignals > 0 || heat >= 0.70) multiplier = 0.50;
        else if (heat >= 0.45 || fusion.activeProviders().size() < 2) multiplier = 0.80;
        else multiplier = 1.50;
        long sleep = (long) (Math.max(1_000L, baseMs) * multiplier);
        return Math.max(minMs, Math.min(maxMs, sleep));
    }

    private static boolean enabled() {
        String v = System.getenv().getOrDefault("ADAPTIVE_PROVIDER_SCHEDULING_ENABLED", "true");
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    private static long envLong(String key, long fallback) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
