package com.bot.intelligence;

import com.bot.model.Bar;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped tracker for intraday high-volume/parabolic candidates.
 *
 * The scanner observes every latest bar it receives. This service aggregates the
 * observed session volume and keeps a ranked list of the names currently drawing
 * the most activity. Strategies can then specialize in the exact names where the
 * violent intraday moves usually occur instead of treating all symbols equally.
 */
public final class ParabolicTopVolumeTracker {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final ParabolicTopVolumeTracker INSTANCE = new ParabolicTopVolumeTracker();

    private final Map<String, VolumeProfile> profiles = new ConcurrentHashMap<>();
    private final int defaultTopCount;
    private volatile String currentSessionKey = sessionKey(System.currentTimeMillis());

    private ParabolicTopVolumeTracker() {
        this.defaultTopCount = Math.max(5, envInt("PARABOLIC_TOP_VOLUME_COUNT", 25));
    }

    public static ParabolicTopVolumeTracker getInstance() {
        return INSTANCE;
    }

    public void observeBar(String ticker, Bar bar) {
        if (ticker == null || ticker.isBlank() || bar == null || bar.close <= 0.0) {
            return;
        }

        long now = System.currentTimeMillis();
        long barTime = bar.timestamp > 0 ? bar.timestamp : now;
        long maxSessionAgeMs = Math.max(60_000L, envLong("PARABOLIC_TOP_VOLUME_MAX_BAR_SESSION_AGE_MS", 20L * 60L * 60L * 1000L));
        // Latest-bar/history providers can return old timestamps during startup.
        // Do not let those old bars repeatedly roll the session backward/forward
        // and clear the tracker dozens of times in one live session.
        String session = sessionKey(Math.abs(now - barTime) > maxSessionAgeMs ? now : barTime);
        rolloverIfNeeded(session);

        String normalized = ticker.trim().toUpperCase();
        VolumeProfile profile = profiles.computeIfAbsent(normalized, VolumeProfile::new);
        profile.observe(bar, session);
    }

    public boolean isTopVolumeTicker(String ticker) {
        return isTopVolumeTicker(ticker, defaultTopCount);
    }

    public boolean isTopVolumeTicker(String ticker, int topCount) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }
        String normalized = ticker.trim().toUpperCase();
        for (String symbol : topSymbols(topCount)) {
            if (normalized.equals(symbol)) {
                return true;
            }
        }
        return false;
    }

    public List<String> topSymbols() {
        return topSymbols(defaultTopCount);
    }

    public List<String> topSymbols(int count) {
        int limit = Math.max(1, count);
        List<VolumeProfile> snapshot = new ArrayList<>(profiles.values());
        snapshot.sort(Comparator
                .comparingLong(VolumeProfile::scoreVolume).reversed()
                .thenComparingDouble(VolumeProfile::latestDollarVolume).reversed());
        List<String> result = new ArrayList<>(Math.min(limit, snapshot.size()));
        for (VolumeProfile profile : snapshot) {
            if (result.size() >= limit) {
                break;
            }
            if (profile.scoreVolume() > 0) {
                result.add(profile.ticker);
            }
        }
        return result;
    }

    public long observedSessionVolume(String ticker) {
        VolumeProfile profile = profile(ticker);
        return profile == null ? 0L : profile.cumulativeVolume;
    }

    public double latestDollarVolume(String ticker) {
        VolumeProfile profile = profile(ticker);
        return profile == null ? 0.0 : profile.latestDollarVolume();
    }

    public String describe(String ticker) {
        VolumeProfile profile = profile(ticker);
        if (profile == null) {
            return "topVolumeProfile=missing";
        }
        return "topVolumeRank=" + rankOf(ticker) +
                " observedVolume=" + profile.cumulativeVolume +
                " latestBarVolume=" + profile.latestBarVolume +
                " latestDollarVolume=" + String.format("%.0f", profile.latestDollarVolume());
    }

    private int rankOf(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return -1;
        }
        String normalized = ticker.trim().toUpperCase();
        List<String> symbols = topSymbols(Math.max(defaultTopCount, 100));
        for (int i = 0; i < symbols.size(); i++) {
            if (normalized.equals(symbols.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private VolumeProfile profile(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return null;
        }
        rolloverIfNeeded(sessionKey(System.currentTimeMillis()));
        return profiles.get(ticker.trim().toUpperCase());
    }

    private void rolloverIfNeeded(String session) {
        if (session == null || session.isBlank()) {
            return;
        }
        String existing = currentSessionKey;
        if (session.equals(existing)) {
            return;
        }
        synchronized (this) {
            if (!session.equals(currentSessionKey)) {
                profiles.clear();
                currentSessionKey = session;
                System.out.println("PARABOLIC TOP VOLUME TRACKER RESET: session=" + session);
            }
        }
    }

    private static String sessionKey(long timestampMs) {
        ZonedDateTime time = Instant.ofEpochMilli(timestampMs).atZone(MARKET_ZONE);
        return String.format("%04d-%02d-%02d", time.getYear(), time.getMonthValue(), time.getDayOfMonth());
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class VolumeProfile {
        private final String ticker;
        private long lastTimestamp = Long.MIN_VALUE;
        private long cumulativeVolume = 0L;
        private long latestBarVolume = 0L;
        private double latestClose = 0.0;
        private String sessionKey = "";

        private VolumeProfile(String ticker) {
            this.ticker = ticker;
        }

        private synchronized void observe(Bar bar, String session) {
            if (bar == null) {
                return;
            }
            if (!session.equals(sessionKey)) {
                sessionKey = session;
                cumulativeVolume = 0L;
                lastTimestamp = Long.MIN_VALUE;
            }
            long ts = bar.timestamp > 0 ? bar.timestamp : System.currentTimeMillis();
            latestBarVolume = Math.max(0L, bar.volume);
            latestClose = Math.max(0.0, bar.close);
            if (ts != lastTimestamp) {
                cumulativeVolume += latestBarVolume;
                lastTimestamp = ts;
            }
        }

        private long scoreVolume() {
            return Math.max(cumulativeVolume, latestBarVolume);
        }

        private double latestDollarVolume() {
            return latestClose * Math.max(0L, latestBarVolume);
        }
    }
}
