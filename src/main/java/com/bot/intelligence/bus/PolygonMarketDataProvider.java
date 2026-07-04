package com.bot.intelligence.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Native Polygon.io adapter for the Market Intelligence Bus.
 *
 * This provider does not require a custom POLYGON_SIGNAL_URL. If POLYGON_API_KEY is
 * present it polls Polygon's market snapshot and optional Polygon news endpoint,
 * then emits normalized MarketIntelligenceSignal objects that the WorldModel,
 * EvidenceGraph, DataSourceReliabilityService, and strategy agents already consume.
 *
 * Required env var:
 *   POLYGON_API_KEY
 *
 * Optional env vars:
 *   POLYGON_PROVIDER_ENABLED=true|false
 *   POLYGON_SNAPSHOT_URL=https://api.polygon.io/v2/snapshot/locale/us/markets/stocks/tickers
 *   POLYGON_NEWS_URL=https://api.polygon.io/v2/reference/news
 *   POLYGON_SNAPSHOT_POLL_MS=30000
 *   POLYGON_NEWS_POLL_MS=60000
 *   POLYGON_MAX_SNAPSHOT_SIGNALS=75
 *   POLYGON_MAX_NEWS_SIGNALS=50
 *   POLYGON_ENABLE_NEWS=true|false
 */
public class PolygonMarketDataProvider implements MarketDataProvider {
    private static final String DEFAULT_SNAPSHOT_URL = "https://api.polygon.io/v2/snapshot/locale/us/markets/stocks/tickers";
    private static final String DEFAULT_NEWS_URL = "https://api.polygon.io/v2/reference/news";

    private final String apiKey;
    private final String snapshotUrl;
    private final String newsUrl;
    private final long snapshotPollMs;
    private final long newsPollMs;
    private final int maxSnapshotSignals;
    private final int maxNewsSignals;
    private final boolean enabled;
    private final boolean newsEnabled;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> seenNewsIds = java.util.Collections.synchronizedSet(new HashSet<>());

    private volatile boolean running = false;
    private volatile long lastNewsPollAt = 0L;

    public PolygonMarketDataProvider() {
        this.apiKey = env("POLYGON_API_KEY", "");
        this.snapshotUrl = env("POLYGON_SNAPSHOT_URL", DEFAULT_SNAPSHOT_URL);
        this.newsUrl = env("POLYGON_NEWS_URL", DEFAULT_NEWS_URL);
        this.snapshotPollMs = envLong("POLYGON_SNAPSHOT_POLL_MS", envLong("POLYGON_SIGNAL_POLL_MS", 30_000L));
        this.newsPollMs = envLong("POLYGON_NEWS_POLL_MS", 60_000L);
        this.maxSnapshotSignals = envInt("POLYGON_MAX_SNAPSHOT_SIGNALS", 75);
        this.maxNewsSignals = envInt("POLYGON_MAX_NEWS_SIGNALS", 50);
        this.newsEnabled = envBoolean("POLYGON_ENABLE_NEWS", true);
        this.enabled = envBoolean("POLYGON_PROVIDER_ENABLED", true) && apiKey != null && !apiKey.isBlank();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return "POLYGON";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void start(Consumer<MarketIntelligenceSignal> signalConsumer) {
        if (!enabled()) {
            if (!envBoolean("POLYGON_SUPPRESS_DISABLED_LOG", false)) {
                System.out.println("POLYGON DATA PROVIDER DISABLED: set POLYGON_API_KEY to enable native Polygon snapshot/news ingestion.");
            }
            MarketIntelligenceBus.getInstance().recordProviderStatus(name(), "DISABLED_NO_API_KEY");
            return;
        }
        if (running) return;
        running = true;
        Thread thread = new Thread(() -> loop(signalConsumer));
        thread.setName("market-intelligence-provider-polygon");
        thread.setDaemon(true);
        thread.start();
        System.out.println("POLYGON DATA PROVIDER STARTED: snapshotPollMs=" + snapshotPollMs +
                " newsEnabled=" + newsEnabled +
                " newsPollMs=" + newsPollMs +
                " maxSnapshotSignals=" + maxSnapshotSignals +
                " maxNewsSignals=" + maxNewsSignals +
                " adaptiveScheduling=" + System.getenv().getOrDefault("ADAPTIVE_PROVIDER_SCHEDULING_ENABLED", "true") +
                " env=POLYGON_API_KEY");
    }

    @Override
    public void stop() {
        running = false;
    }

    private void loop(Consumer<MarketIntelligenceSignal> signalConsumer) {
        while (running) {
            try {
                int snapshotCount = pollSnapshot(signalConsumer);
                int newsCount = 0;
                long now = System.currentTimeMillis();
                if (newsEnabled && now - lastNewsPollAt >= Math.max(15_000L, newsPollMs)) {
                    newsCount = pollNews(signalConsumer);
                    lastNewsPollAt = now;
                }
                int emitted = snapshotCount + newsCount;
                MarketIntelligenceBus.getInstance().recordProviderStatus(name(), "OK snapshot=" + snapshotCount + " news=" + newsCount);
                Thread.sleep(AdaptiveProviderScheduler.nextSleepMs(name(), Math.max(10_000L, snapshotPollMs), emitted));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception e) {
                MarketIntelligenceBus.getInstance().recordProviderStatus(name(), "ERROR:" + safe(e.getMessage()));
                sleep(AdaptiveProviderScheduler.nextSleepMs(name(), Math.max(30_000L, snapshotPollMs), 0));
            }
        }
    }

    private int pollSnapshot(Consumer<MarketIntelligenceSignal> signalConsumer) throws Exception {
        JsonNode root = getJson(withApiKey(snapshotUrl));
        JsonNode tickers = root.path("tickers");
        if (!tickers.isArray()) tickers = root.path("results");
        if (!tickers.isArray()) return 0;

        List<SnapshotCandidate> candidates = new ArrayList<>();
        for (JsonNode node : tickers) {
            String ticker = text(node, "ticker", text(node, "T", ""));
            if (ticker.isBlank() || !isUsEquityTicker(ticker)) continue;
            double volume = number(node.path("day"), "v", number(node, "volume", 0.0));
            double changePct = number(node, "todaysChangePerc", number(node, "changePercent", 0.0));
            double lastPrice = number(node.path("lastTrade"), "p", number(node.path("min"), "c", number(node.path("day"), "c", 0.0)));
            double dayOpen = number(node.path("day"), "o", 0.0);
            double dayHigh = number(node.path("day"), "h", 0.0);
            double dayLow = number(node.path("day"), "l", 0.0);
            if (lastPrice <= 0.0 || volume <= 0.0) continue;
            double moveScore = Math.min(1.0, Math.abs(changePct) / 25.0);
            double volumeScore = Math.min(1.0, Math.log10(Math.max(10.0, volume)) / 8.0);
            double rangeScore = dayHigh > 0.0 && dayLow > 0.0 && lastPrice > 0.0 ? Math.min(1.0, ((dayHigh - dayLow) / lastPrice) / 0.20) : 0.0;
            double score = clamp(moveScore * 0.45 + volumeScore * 0.35 + rangeScore * 0.20);
            if (score < envDouble("POLYGON_MIN_SNAPSHOT_SCORE", 0.35)) continue;
            candidates.add(new SnapshotCandidate(ticker, volume, changePct, lastPrice, dayOpen, dayHigh, dayLow, score));
        }

        candidates.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            return Double.compare(b.volume, a.volume);
        });

        int emitted = 0;
        for (SnapshotCandidate c : candidates) {
            if (emitted >= maxSnapshotSignals) break;
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("provider", "POLYGON");
            metadata.put("rawMode", "POLYGON_SNAPSHOT");
            metadata.put("volume", fmt(c.volume));
            metadata.put("changePct", fmt(c.changePct));
            metadata.put("lastPrice", fmt(c.lastPrice));
            metadata.put("dayOpen", fmt(c.dayOpen));
            metadata.put("dayHigh", fmt(c.dayHigh));
            metadata.put("dayLow", fmt(c.dayLow));
            metadata.put("rankScore", fmt(c.score));
            String headline = "Polygon snapshot: " + c.ticker + " change=" + fmt(c.changePct) + "% volume=" + Math.round(c.volume) + " last=" + fmt(c.lastPrice);
            signalConsumer.accept(new MarketIntelligenceSignal(
                    "POLYGON",
                    MarketIntelligenceSignalType.MARKET_DATA,
                    c.ticker,
                    headline,
                    "",
                    System.currentTimeMillis(),
                    clamp(0.55 + c.score * 0.35),
                    c.score,
                    metadata
            ));
            emitted++;
        }
        return emitted;
    }

    private int pollNews(Consumer<MarketIntelligenceSignal> signalConsumer) throws Exception {
        String url = withApiKey(appendQuery(newsUrl, "limit=" + Math.max(1, Math.min(100, maxNewsSignals)) + "&order=desc&sort=published_utc"));
        JsonNode root = getJson(url);
        JsonNode results = root.path("results");
        if (!results.isArray()) return 0;
        int emitted = 0;
        for (JsonNode node : results) {
            if (emitted >= maxNewsSignals) break;
            String id = text(node, "id", text(node, "article_url", ""));
            if (!id.isBlank() && !seenNewsIds.add(id)) continue;
            String title = text(node, "title", "");
            String description = text(node, "description", "");
            long publishedMs = parseTime(text(node, "published_utc", ""));
            JsonNode tickers = node.path("tickers");
            if (!tickers.isArray()) continue;
            for (JsonNode tickerNode : tickers) {
                String ticker = tickerNode.asText("").trim().toUpperCase(Locale.ROOT);
                if (!isUsEquityTicker(ticker)) continue;
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("provider", "POLYGON");
                metadata.put("rawMode", "POLYGON_NEWS");
                metadata.put("polygonId", id);
                metadata.put("publisher", node.path("publisher").path("name").asText(""));
                metadata.put("articleUrl", text(node, "article_url", ""));
                metadata.put("routeToNews", "true");
                signalConsumer.accept(new MarketIntelligenceSignal(
                        "POLYGON",
                        MarketIntelligenceSignalType.NEWS,
                        ticker,
                        title.isBlank() ? "Polygon news signal" : title,
                        description,
                        publishedMs,
                        0.70,
                        headlinePriority(title + " " + description),
                        metadata
                ));
                emitted++;
                if (emitted >= maxNewsSignals) break;
            }
        }
        return emitted;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .header("accept", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            MarketIntelligenceBus.getInstance().recordProviderStatus(name(), "HTTP_" + code);
            throw new IllegalStateException("Polygon HTTP " + code);
        }
        return mapper.readTree(response.body() == null ? "{}" : response.body());
    }

    private String withApiKey(String url) {
        if (url == null || url.isBlank()) return url;
        if (url.contains("apiKey=") || url.contains("apikey=")) return url;
        return appendQuery(url, "apiKey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
    }

    private static String appendQuery(String url, String query) {
        if (url == null || url.isBlank() || query == null || query.isBlank()) return url;
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static double headlinePriority(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
        double p = 0.35;
        if (s.contains("fda") || s.contains("approval") || s.contains("clearance")) p += 0.30;
        if (s.contains("guidance") || s.contains("raises") || s.contains("beats")) p += 0.20;
        if (s.contains("contract") || s.contains("award") || s.contains("merger") || s.contains("acquire")) p += 0.20;
        if (s.contains("offering") || s.contains("dilution") || s.contains("bankruptcy")) p += 0.25;
        return clamp(p);
    }

    private static boolean isUsEquityTicker(String ticker) {
        if (ticker == null) return false;
        String t = ticker.trim().toUpperCase(Locale.ROOT);
        if (t.isBlank() || t.length() > 8) return false;
        if (t.endsWith("USD") || t.contains("/") || t.contains(":")) return false;
        return t.matches("[A-Z][A-Z0-9.\\-]{0,7}");
    }

    private static long parseTime(String value) {
        try {
            if (value == null || value.isBlank()) return System.currentTimeMillis();
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || field == null) return fallback == null ? "" : fallback;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? (fallback == null ? "" : fallback) : value.asText(fallback == null ? "" : fallback).trim();
    }

    private static double number(JsonNode node, String field, double fallback) {
        if (node == null || field == null) return fallback;
        JsonNode value = node.path(field);
        if (value.isNumber()) return value.asDouble(fallback);
        try {
            String s = value.asText("");
            return s.isBlank() ? fallback : Double.parseDouble(s);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
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

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y") || v.equals("on");
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0.0;
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class SnapshotCandidate {
        final String ticker;
        final double volume;
        final double changePct;
        final double lastPrice;
        final double dayOpen;
        final double dayHigh;
        final double dayLow;
        final double score;

        SnapshotCandidate(String ticker, double volume, double changePct, double lastPrice, double dayOpen, double dayHigh, double dayLow, double score) {
            this.ticker = ticker;
            this.volume = volume;
            this.changePct = changePct;
            this.lastPrice = lastPrice;
            this.dayOpen = dayOpen;
            this.dayHigh = dayHigh;
            this.dayLow = dayLow;
            this.score = score;
        }
    }
}
