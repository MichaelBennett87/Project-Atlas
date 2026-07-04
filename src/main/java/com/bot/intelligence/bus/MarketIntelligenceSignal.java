package com.bot.intelligence.bus;

import com.bot.model.NewsEvent;
import com.bot.intelligence.DataSourceReliabilityService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class MarketIntelligenceSignal {
    private final String provider;
    private final MarketIntelligenceSignalType type;
    private final String ticker;
    private final String headline;
    private final String content;
    private final long providerTimestampMs;
    private final long receivedAtMs;
    private final double confidence;
    private final double priority;
    private final Map<String, String> metadata;

    public MarketIntelligenceSignal(
            String provider,
            MarketIntelligenceSignalType type,
            String ticker,
            String headline,
            String content,
            long providerTimestampMs,
            double confidence,
            double priority,
            Map<String, String> metadata
    ) {
        this.provider = normalize(provider);
        this.type = type == null ? MarketIntelligenceSignalType.UNKNOWN : type;
        this.ticker = ticker == null ? "" : ticker.trim().toUpperCase();
        this.headline = headline == null ? "" : headline.trim();
        this.content = content == null ? "" : content.trim();
        this.providerTimestampMs = providerTimestampMs > 0 ? providerTimestampMs : System.currentTimeMillis();
        this.receivedAtMs = System.currentTimeMillis();
        this.confidence = clamp(confidence);
        this.priority = clamp(priority);
        this.metadata = metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static MarketIntelligenceSignal fromNews(NewsEvent news) {
        if (news == null) return null;
        MarketIntelligenceSignalType type = isPressRelease(news.getSource())
                ? MarketIntelligenceSignalType.PRESS_RELEASE
                : MarketIntelligenceSignalType.NEWS;
        double sourceWeight = DataSourceReliabilityService.getInstance().weightFor(news.getSource());
        double confidence = clamp((news.hasKnownSource() ? 0.75 : 0.45) * sourceWeight);
        double priority = clamp(Math.max(0.0, Math.min(1.0, news.getCatalystScore())) * Math.max(0.50, Math.min(1.50, sourceWeight)));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("newsId", nullToEmpty(news.getId()));
        metadata.put("sourceLagMs", String.valueOf(news.getSourceLagMs()));
        metadata.put("sentimentScore", String.valueOf(news.getSentimentScore()));
        metadata.put("catalystScore", String.valueOf(news.getCatalystScore()));
        metadata.put("sourceReliabilityWeight", String.valueOf(sourceWeight));
        return new MarketIntelligenceSignal(
                news.getSource(),
                type,
                news.getTicker(),
                news.getHeadline(),
                news.getContent(),
                news.getProviderTimestamp(),
                confidence,
                priority,
                metadata
        );
    }

    public NewsEvent toNewsEvent() {
        NewsEvent event = new NewsEvent(
                fingerprint(),
                ticker,
                headline,
                content,
                providerTimestampMs
        );
        event.setSource(provider);
        event.setProviderTimestamp(providerTimestampMs);
        event.setBotFirstSeenAt(receivedAtMs);
        event.setCatalystScore(parseDouble(metadata.get("catalystScore"), priority));
        event.setSentimentScore(parseDouble(metadata.get("sentimentScore"), confidence));
        return event;
    }

    public String fingerprint() {
        String raw = provider + "|" + type + "|" + ticker + "|" + headline + "|" + providerTimestampMs;
        return Integer.toHexString(raw.hashCode()) + ":" + Math.abs((long) raw.hashCode());
    }

    public String compactSummary() {
        return provider + ":" + type + ":" + ticker + ":priority=" + String.format("%.2f", priority) + ":" + headline;
    }

    public String getProvider() { return provider; }
    public MarketIntelligenceSignalType getType() { return type; }
    public String getTicker() { return ticker; }
    public String getHeadline() { return headline; }
    public String getContent() { return content; }
    public long getProviderTimestampMs() { return providerTimestampMs; }
    public long getReceivedAtMs() { return receivedAtMs; }
    public double getConfidence() { return confidence; }
    public double getPriority() { return priority; }
    public Map<String, String> getMetadata() { return metadata; }


    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isPressRelease(String source) {
        return source != null && source.toUpperCase().contains("PRESS_RELEASE");
    }

    private static String normalize(String provider) {
        if (provider == null || provider.trim().isBlank()) return "UNKNOWN";
        return provider.trim().toUpperCase().replace(' ', '_');
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
