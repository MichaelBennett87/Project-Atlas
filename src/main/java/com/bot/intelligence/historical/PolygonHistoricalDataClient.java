package com.bot.intelligence.historical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Polygon historical REST client used by the after-hours research agents.
 *
 * Required env var:
 *   POLYGON_API_KEY
 *
 * Outputs bars into HISTORICAL_MARKET_DATA_DIR so HistoricalReplayEngine and
 * OfflineReplayClusterMain can train on the downloaded market history.
 */
public final class PolygonHistoricalDataClient implements HistoricalDataProviderClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String apiKey = env("POLYGON_API_KEY", "");
    private final Path outputRoot = Path.of(env("HISTORICAL_MARKET_DATA_DIR", "logs/historical_market_data"));

    @Override public String providerName() { return "POLYGON"; }
    @Override public boolean enabled() { return !apiKey.isBlank(); }

    @Override
    public boolean supports(HistoricalDataRequest request) {
        return request != null && !request.ticker.isBlank() &&
                (request.dataType == HistoricalDataRequest.DataType.BARS ||
                 request.dataType == HistoricalDataRequest.DataType.NEWS ||
                 request.dataType == HistoricalDataRequest.DataType.TRADES ||
                 request.dataType == HistoricalDataRequest.DataType.QUOTES);
    }

    @Override
    public HistoricalDataResponse fetch(HistoricalDataRequest request) {
        long started = System.currentTimeMillis();
        if (!enabled()) return new HistoricalDataResponse(request, false, providerName(), "POLYGON_API_KEY not set", null, 0, 0);
        if (!supports(request)) return new HistoricalDataResponse(request, false, providerName(), "unsupported request", null, 0, 0);
        try {
            if (request.dataType == HistoricalDataRequest.DataType.NEWS) {
                return fetchNews(request, started);
            }
            // Trades/quotes require higher Polygon plans for many accounts.  We still route the
            // request, but default to aggregate bars unless explicitly enabled.
            if ((request.dataType == HistoricalDataRequest.DataType.TRADES || request.dataType == HistoricalDataRequest.DataType.QUOTES)
                    && !envBoolean("POLYGON_ENABLE_TRADE_QUOTE_HISTORY", false)) {
                return fetchBars(request, started, "1", "minute", "requested_" + request.dataType + "_fallback_bars");
            }
            return fetchBars(request, started, multiplier(request.interval), timespan(request.interval), "bars");
        } catch (Exception e) {
            return new HistoricalDataResponse(request, false, providerName(), safe(e.getMessage()), null, 0, System.currentTimeMillis() - started);
        }
    }

    private HistoricalDataResponse fetchBars(HistoricalDataRequest request, long started, String multiplier, String timespan, String mode) throws Exception {
        Path out = outputRoot.resolve(providerName().toLowerCase(Locale.ROOT)).resolve(request.ticker + "_" + request.from + "_" + request.to + "_" + request.interval + ".csv");
        Files.createDirectories(out.getParent());
        boolean newFile = !Files.exists(out) || Files.size(out) == 0;
        int rows = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (newFile) {
                writer.write("ticker,timestamp,open,high,low,close,volume,vwap,transactions,source,agent,requestMode");
                writer.newLine();
            }
            String url = "https://api.polygon.io/v2/aggs/ticker/" + enc(request.ticker) + "/range/" + multiplier + "/" + enc(timespan) + "/" + request.from + "/" + request.to + "?adjusted=true&sort=asc&limit=" + Math.min(50000, request.maxRows) + "&apiKey=" + enc(apiKey);
            while (url != null && !url.isBlank() && rows < request.maxRows) {
                JsonNode root = getJson(url);
                JsonNode results = root.path("results");
                if (results.isArray()) {
                    for (JsonNode r : results) {
                        if (rows >= request.maxRows) break;
                        long ts = r.path("t").asLong(0L);
                        writer.write(String.join(",",
                                csv(request.ticker),
                                csv(Instant.ofEpochMilli(ts).toString()),
                                fmt(r.path("o").asDouble(0.0)),
                                fmt(r.path("h").asDouble(0.0)),
                                fmt(r.path("l").asDouble(0.0)),
                                fmt(r.path("c").asDouble(0.0)),
                                String.valueOf(r.path("v").asLong(0L)),
                                fmt(r.path("vw").asDouble(0.0)),
                                String.valueOf(r.path("n").asLong(0L)),
                                providerName(),
                                csv(request.requestingAgent),
                                csv(mode)
                        ));
                        writer.newLine();
                        rows++;
                    }
                }
                String next = root.path("next_url").asText("");
                url = next.isBlank() ? null : next + (next.contains("apiKey=") ? "" : (next.contains("?") ? "&" : "?") + "apiKey=" + enc(apiKey));
            }
        }
        return new HistoricalDataResponse(request, true, providerName(), "downloaded " + mode, out, rows, System.currentTimeMillis() - started);
    }

    private HistoricalDataResponse fetchNews(HistoricalDataRequest request, long started) throws Exception {
        Path out = outputRoot.resolve(providerName().toLowerCase(Locale.ROOT)).resolve("news_" + request.ticker + "_" + request.from + "_" + request.to + ".csv");
        Files.createDirectories(out.getParent());
        boolean newFile = !Files.exists(out) || Files.size(out) == 0;
        int rows = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (newFile) {
                writer.write("ticker,publishedUtc,title,description,articleUrl,source,agent");
                writer.newLine();
            }
            String url = "https://api.polygon.io/v2/reference/news?ticker=" + enc(request.ticker) +
                    "&published_utc.gte=" + request.from + "&published_utc.lte=" + request.to +
                    "&limit=" + Math.min(100, request.maxRows) + "&order=asc&sort=published_utc&apiKey=" + enc(apiKey);
            while (url != null && !url.isBlank() && rows < request.maxRows) {
                JsonNode root = getJson(url);
                JsonNode results = root.path("results");
                if (results.isArray()) {
                    for (JsonNode n : results) {
                        if (rows >= request.maxRows) break;
                        writer.write(String.join(",",
                                csv(request.ticker),
                                csv(n.path("published_utc").asText("")),
                                csv(n.path("title").asText("")),
                                csv(n.path("description").asText("")),
                                csv(n.path("article_url").asText("")),
                                providerName(),
                                csv(request.requestingAgent)
                        ));
                        writer.newLine();
                        rows++;
                    }
                }
                String next = root.path("next_url").asText("");
                url = next.isBlank() ? null : next + (next.contains("apiKey=") ? "" : (next.contains("?") ? "&" : "?") + "apiKey=" + enc(apiKey));
            }
        }
        return new HistoricalDataResponse(request, true, providerName(), "downloaded news", out, rows, System.currentTimeMillis() - started);
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(45)).GET().header("accept", "application/json").build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) throw new IllegalStateException("HTTP " + res.statusCode() + " from Polygon");
        return MAPPER.readTree(res.body() == null ? "{}" : res.body());
    }

    private static String multiplier(String interval) {
        String s = interval == null ? "1min" : interval.toLowerCase(Locale.ROOT).trim();
        String n = s.replaceAll("[^0-9]", "");
        return n.isBlank() ? "1" : n;
    }

    private static String timespan(String interval) {
        String s = interval == null ? "1min" : interval.toLowerCase(Locale.ROOT).trim();
        if (s.contains("hour")) return "hour";
        if (s.contains("day") || s.equals("1d")) return "day";
        if (s.contains("week")) return "week";
        if (s.contains("month")) return "month";
        return "minute";
    }

    private static String env(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static boolean envBoolean(String k, boolean f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : Boolean.parseBoolean(v.trim()); }
    private static String enc(String v) { return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8); }
    private static String safe(String v) { return v == null ? "" : v.replace('\n', ' ').replace('\r', ' '); }
    private static String csv(String v) { String s = safe(v).replace(',', ' '); return '"' + s.replace("\"", "\"\"") + '"'; }
    private static String fmt(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) v = 0.0; return String.format(Locale.ROOT, "%.6f", v); }
}
