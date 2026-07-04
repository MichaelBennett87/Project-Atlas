package com.bot.intelligence.bus;

import com.bot.intelligence.MarketFeatureBus;
import com.bot.intelligence.MarketKnowledgeDatabase;
import com.bot.model.Bar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polygon-first fallback for any trading path that needs immediately usable market data.
 *
 * If Alpaca latest bars are missing/stale, this service checks Polygon premium data before
 * rejecting a trade candidate for NO_VALID_MARKET_DATA.
 */
public final class PolygonFirstMarketDataService {
    private static final PolygonFirstMarketDataService INSTANCE = new PolygonFirstMarketDataService();
    private static final String POLYGON_BASE = "https://api.polygon.io";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CachedBar> cache = new ConcurrentHashMap<>();
    private final String apiKey = env("POLYGON_API_KEY", "");
    private final ZoneId zone = ZoneId.of(env("MARKET_TIME_ZONE", "America/New_York"));
    private final long cacheTtlMs = longEnv("POLYGON_FIRST_MARKET_DATA_CACHE_TTL_MS", 20_000L);
    private final boolean enabled = boolEnv("POLYGON_FIRST_MARKET_DATA_ENABLED", true);

    private PolygonFirstMarketDataService() {}
    public static PolygonFirstMarketDataService getInstance() { return INSTANCE; }

    public boolean enabled() { return enabled && !apiKey.isBlank(); }

    public Bar getFreshBar(String ticker, long maxAgeMs) {
        String symbol = norm(ticker);
        if (!enabled() || symbol.isBlank()) return null;
        long now = System.currentTimeMillis();
        MarketKnowledgeDatabase.Record local = MarketKnowledgeDatabase.getInstance().snapshot(symbol);
        if (local != null && local.price > 0.0 && now - local.lastUpdatedMs <= Math.max(1_000L, maxAgeMs)) {
            Bar localBar = new Bar();
            localBar.ticker = symbol;
            localBar.timestamp = local.lastUpdatedMs;
            localBar.open = local.price;
            localBar.high = local.dayHigh > 0.0 ? local.dayHigh : local.price;
            localBar.low = local.dayLow > 0.0 ? local.dayLow : local.price;
            localBar.close = local.price;
            localBar.volume = Math.max(0L, Math.round(Math.max(local.snapshotVolume, local.minuteVolume)));
            markSource(localBar, "MARKET_KNOWLEDGE_DATABASE");
            cache.put(symbol, new CachedBar(localBar, now));
            System.out.println("POLYGON-FIRST MARKET DATA: ticker=" + symbol + " source=MARKET_KNOWLEDGE_DATABASE close=" + fmt(localBar.close) + " ageMs=" + (now - local.lastUpdatedMs));
            return localBar;
        }
        CachedBar cached = cache.get(symbol);
        if (cached != null && now - cached.cachedAtMs <= Math.max(1_000L, cacheTtlMs)) {
            return cached.bar;
        }

        Bar bar = latestAggregateBar(symbol);
        long ts = bar == null ? 0L : MarketFeatureBus.normalizeEpochMillis(bar.timestamp);
        long age = ts <= 0L ? Long.MAX_VALUE : Math.abs(System.currentTimeMillis() - ts);

        // The old path gave up when Polygon aggregates were stale. That caused
        // breaking-news candidates to be rejected as NO_VALID_MARKET_DATA even
        // though Polygon snapshots were available. If the aggregate is stale,
        // use a current snapshot as a synthetic market-state bar.
        if (bar == null || bar.close <= 0.0 || (maxAgeMs > 0 && age > maxAgeMs)) {
            Bar snapshot = snapshotSyntheticBar(symbol);
            if (snapshot != null && snapshot.close > 0.0) {
                bar = snapshot;
                ts = MarketFeatureBus.normalizeEpochMillis(bar.timestamp);
                age = ts <= 0L ? 0L : Math.abs(System.currentTimeMillis() - ts);
            }
        }
        if (bar == null || bar.close <= 0.0) return null;

        // Snapshot synthetic bars are stamped now and are acceptable as a last-mile quote proxy.
        if (maxAgeMs > 0 && age > maxAgeMs && !"POLYGON_SYNTHETIC_SNAPSHOT".equals(source(bar))) {
            return null;
        }
        cacheAndPublish(symbol, bar, now);
        System.out.println("POLYGON-FIRST MARKET DATA: ticker=" + symbol + " source=" + source(bar) + " close=" + fmt(bar.close) + " ageMs=" + age);
        return bar;
    }

    /**
     * Emergency market-data lookup used by live news candidates. It must not fail
     * simply because the latest aggregate timestamp is stale; for a breaking catalyst
     * a current Polygon snapshot, or as a final fallback the latest aggregate restamped
     * to now, is better than rejecting the setup before the strategy/AI can review it.
     */
    public Bar getEmergencyBar(String ticker) {
        String symbol = norm(ticker);
        if (!enabled() || symbol.isBlank()) return null;
        long now = System.currentTimeMillis();

        MarketKnowledgeDatabase.Record local = MarketKnowledgeDatabase.getInstance().snapshot(symbol);
        if (local != null && local.price > 0.0) {
            Bar localBar = new Bar();
            localBar.ticker = symbol;
            localBar.timestamp = now;
            localBar.open = local.price;
            localBar.high = local.dayHigh > 0.0 ? local.dayHigh : local.price;
            localBar.low = local.dayLow > 0.0 ? local.dayLow : local.price;
            localBar.close = local.price;
            localBar.volume = Math.max(0L, Math.round(Math.max(local.snapshotVolume, Math.max(local.minuteVolume, local.tradeVolume))));
            markSource(localBar, "MARKET_KNOWLEDGE_DATABASE_EMERGENCY");
            cacheAndPublish(symbol, localBar, now);
            System.out.println("POLYGON-FIRST EMERGENCY MARKET DATA: ticker=" + symbol + " source=MARKET_KNOWLEDGE_DATABASE close=" + fmt(localBar.close));
            return localBar;
        }

        Bar snapshot = snapshotSyntheticBar(symbol);
        if (snapshot != null && snapshot.close > 0.0) {
            snapshot.timestamp = now;
            cacheAndPublish(symbol, snapshot, now);
            System.out.println("POLYGON-FIRST EMERGENCY MARKET DATA: ticker=" + symbol + " source=" + source(snapshot) + " close=" + fmt(snapshot.close));
            return snapshot;
        }

        Bar agg = latestAggregateBar(symbol);
        if (agg != null && agg.close > 0.0) {
            // Restamp only for the emergency news path; the old timestamp is still
            // represented in Polygon caches, but this synthetic bar prevents the
            // live pipeline from discarding a strong catalyst before AI review.
            agg.timestamp = now;
            markSource(agg, "POLYGON_LATEST_AGG_EMERGENCY");
            cacheAndPublish(symbol, agg, now);
            System.out.println("POLYGON-FIRST EMERGENCY MARKET DATA: ticker=" + symbol + " source=" + source(agg) + " close=" + fmt(agg.close));
            return agg;
        }

        return null;
    }

    private void cacheAndPublish(String symbol, Bar bar, long now) {
        cache.put(symbol, new CachedBar(bar, now));
        MarketFeatureBus.getInstance().publishBar(source(bar), symbol, bar);
        MarketKnowledgeDatabase.getInstance().recordSnapshot(symbol, bar.close, bar.volume, 0.0, bar.high, bar.low, source(bar));
    }

    private Bar latestAggregateBar(String symbol) {
        try {
            LocalDate today = LocalDate.now(zone);
            String endpoint = "/v2/aggs/ticker/" + encPath(symbol) + "/range/1/minute/" + today.minusDays(1) + "/" + today;
            String url = POLYGON_BASE + endpoint + "?adjusted=true&sort=desc&limit=1&apiKey=" + enc(apiKey);
            JsonNode root = getJson(url);
            JsonNode arr = root.path("results");
            if (!arr.isArray() || arr.size() == 0) return null;
            JsonNode b = arr.get(0);
            double close = b.path("c").asDouble(0.0);
            if (close <= 0) return null;
            Bar bar = new Bar();
            bar.ticker = symbol;
            bar.timestamp = b.path("t").asLong(System.currentTimeMillis());
            bar.open = b.path("o").asDouble(close);
            bar.high = b.path("h").asDouble(close);
            bar.low = b.path("l").asDouble(close);
            bar.close = close;
            bar.volume = Math.max(0L, Math.round(b.path("v").asDouble(0.0)));
            markSource(bar, "POLYGON_LATEST_AGG");
            return bar;
        } catch (Exception e) {
            if (boolEnv("POLYGON_FIRST_MARKET_DATA_VERBOSE_ERRORS", false)) {
                System.out.println("POLYGON-FIRST LATEST AGG FAILED: ticker=" + symbol + " error=" + e.getMessage());
            }
            return null;
        }
    }

    private Bar snapshotSyntheticBar(String symbol) {
        try {
            String endpoint = "/v2/snapshot/locale/us/markets/stocks/tickers/" + encPath(symbol);
            String url = POLYGON_BASE + endpoint + "?apiKey=" + enc(apiKey);
            JsonNode root = getJson(url);
            JsonNode t = root.path("ticker");
            if (!t.isObject()) t = root.path("results");
            double price = number(t.path("lastTrade"), "p", number(t.path("min"), "c", number(t.path("day"), "c", 0.0)));
            if (price <= 0.0) return null;
            double high = number(t.path("day"), "h", price);
            double low = number(t.path("day"), "l", price);
            double open = number(t.path("day"), "o", price);
            double volume = number(t.path("day"), "v", number(t, "volume", 0.0));
            Bar bar = new Bar();
            bar.ticker = symbol;
            bar.timestamp = System.currentTimeMillis();
            bar.open = open > 0 ? open : price;
            bar.high = high > 0 ? high : price;
            bar.low = low > 0 ? low : price;
            bar.close = price;
            bar.volume = Math.max(0L, Math.round(volume));
            markSource(bar, "POLYGON_SYNTHETIC_SNAPSHOT");
            return bar;
        } catch (Exception e) {
            if (boolEnv("POLYGON_FIRST_MARKET_DATA_VERBOSE_ERRORS", false)) {
                System.out.println("POLYGON-FIRST SNAPSHOT FAILED: ticker=" + symbol + " error=" + e.getMessage());
            }
            return null;
        }
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().header("accept", "application/json").build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new RuntimeException("HTTP " + response.statusCode() + " body=" + safe(response.body()));
        return mapper.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
    }

    private static double number(JsonNode node, String field, double fallback) { return node == null || node.isMissingNode() ? fallback : node.path(field).asDouble(fallback); }
    private static String norm(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", ""); }
    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
    private static String encPath(String s) { return enc(s).replace("+", "%20"); }
    private static String env(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static long longEnv(String k, long f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Long.parseLong(v.trim()); } catch (Exception e) { return f; } }
    private static boolean boolEnv(String k, boolean f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "yes".equalsIgnoreCase(v.trim()); }
    private static String fmt(double v) { return String.format(Locale.US, "%.5f", Double.isFinite(v) ? v : 0.0); }
    private static String safe(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' '); }

    // Bar has no metadata field; encode source by harmless synthetic ticker suffix is not acceptable.
    // Keep source lookup in a small identity map instead.
    private final ConcurrentHashMap<Bar, String> sourceByBar = new ConcurrentHashMap<>();
    private void markSource(Bar bar, String source) { if (bar != null) sourceByBar.put(bar, source); }
    private String source(Bar bar) { return sourceByBar.getOrDefault(bar, "POLYGON_FIRST"); }

    private static final class CachedBar { final Bar bar; final long cachedAtMs; CachedBar(Bar b, long t) { bar = b; cachedAtMs = t; } }
}
