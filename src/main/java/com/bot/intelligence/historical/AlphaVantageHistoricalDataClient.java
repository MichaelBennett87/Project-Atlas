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
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Alpha Vantage historical/research REST client for the after-hours analyst tools.
 *
 * Required env var:
 *   ALPHA_VANTAGE_API_KEY
 *
 * This is deliberately conservative because the free tier is rate limited.  The
 * nightly orchestrator uses it primarily for fundamentals/news/context and small
 * historical-bar samples, while Polygon handles larger intraday replay pulls.
 */
public final class AlphaVantageHistoricalDataClient implements HistoricalDataProviderClient {
    private static final String BASE_URL = "https://www.alphavantage.co/query";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String apiKey = env("ALPHA_VANTAGE_API_KEY", "");
    private final Path outputRoot = Path.of(env("HISTORICAL_MARKET_DATA_DIR", "logs/historical_market_data"));

    @Override public String providerName() { return "ALPHA_VANTAGE"; }
    @Override public boolean enabled() { return !apiKey.isBlank(); }

    @Override
    public boolean supports(HistoricalDataRequest request) {
        return request != null && !request.ticker.isBlank() &&
                (request.dataType == HistoricalDataRequest.DataType.BARS ||
                 request.dataType == HistoricalDataRequest.DataType.NEWS ||
                 request.dataType == HistoricalDataRequest.DataType.FUNDAMENTALS ||
                 request.dataType == HistoricalDataRequest.DataType.OVERVIEW ||
                 request.dataType == HistoricalDataRequest.DataType.INDICATOR);
    }

    @Override
    public HistoricalDataResponse fetch(HistoricalDataRequest request) {
        long started = System.currentTimeMillis();
        if (!enabled()) return new HistoricalDataResponse(request, false, providerName(), "ALPHA_VANTAGE_API_KEY not set", null, 0, 0);
        if (!supports(request)) return new HistoricalDataResponse(request, false, providerName(), "unsupported request", null, 0, 0);
        try {
            switch (request.dataType) {
                case NEWS: return fetchNews(request, started);
                case FUNDAMENTALS:
                case OVERVIEW: return fetchOverview(request, started);
                case INDICATOR: return fetchIndicator(request, started);
                case BARS:
                default: return fetchBars(request, started);
            }
        } catch (Exception e) {
            return new HistoricalDataResponse(request, false, providerName(), safe(e.getMessage()), null, 0, System.currentTimeMillis() - started);
        }
    }

    private HistoricalDataResponse fetchBars(HistoricalDataRequest request, long started) throws Exception {
        String interval = normalizeInterval(request.interval);
        String function = "TIME_SERIES_INTRADAY";
        String url = BASE_URL + "?function=" + function + "&symbol=" + enc(request.ticker) + "&interval=" + enc(interval) + "&outputsize=full&apikey=" + enc(apiKey);
        JsonNode root = getJson(url);
        if (isRateLimited(root)) return new HistoricalDataResponse(request, false, providerName(), "rate limited or informational Alpha Vantage response", null, 0, System.currentTimeMillis() - started);
        String seriesName = "Time Series (" + interval + ")";
        JsonNode series = root.path(seriesName);
        if (!series.isObject()) return new HistoricalDataResponse(request, false, providerName(), "missing time series", null, 0, System.currentTimeMillis() - started);
        Path out = outputRoot.resolve(providerName().toLowerCase(Locale.ROOT)).resolve(request.ticker + "_alpha_" + interval + "_" + request.from + "_" + request.to + ".csv");
        Files.createDirectories(out.getParent());
        int rows = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("ticker,timestamp,open,high,low,close,volume,source,agent");
            writer.newLine();
            Iterator<Map.Entry<String, JsonNode>> it = series.fields();
            while (it.hasNext() && rows < request.maxRows) {
                Map.Entry<String, JsonNode> e = it.next();
                String ts = e.getKey();
                JsonNode r = e.getValue();
                writer.write(String.join(",",
                        csv(request.ticker), csv(ts),
                        fmt(num(r.path("1. open").asText("0"))),
                        fmt(num(r.path("2. high").asText("0"))),
                        fmt(num(r.path("3. low").asText("0"))),
                        fmt(num(r.path("4. close").asText("0"))),
                        String.valueOf((long) num(r.path("5. volume").asText("0"))),
                        providerName(), csv(request.requestingAgent)
                ));
                writer.newLine();
                rows++;
            }
        }
        return new HistoricalDataResponse(request, true, providerName(), "downloaded intraday bars", out, rows, System.currentTimeMillis() - started);
    }

    private HistoricalDataResponse fetchOverview(HistoricalDataRequest request, long started) throws Exception {
        JsonNode root = getJson(BASE_URL + "?function=OVERVIEW&symbol=" + enc(request.ticker) + "&apikey=" + enc(apiKey));
        if (isRateLimited(root)) return new HistoricalDataResponse(request, false, providerName(), "rate limited or informational Alpha Vantage response", null, 0, System.currentTimeMillis() - started);
        Path out = outputRoot.resolve(providerName().toLowerCase(Locale.ROOT)).resolve("overview_" + request.ticker + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, root.toPrettyString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return new HistoricalDataResponse(request, true, providerName(), "downloaded overview/fundamentals", out, root.size() > 0 ? 1 : 0, System.currentTimeMillis() - started);
    }

    private HistoricalDataResponse fetchNews(HistoricalDataRequest request, long started) throws Exception {
        String url = BASE_URL + "?function=NEWS_SENTIMENT&tickers=" + enc(request.ticker) + "&limit=" + Math.min(200, request.maxRows) + "&apikey=" + enc(apiKey);
        JsonNode root = getJson(url);
        if (isRateLimited(root)) return new HistoricalDataResponse(request, false, providerName(), "rate limited or informational Alpha Vantage response", null, 0, System.currentTimeMillis() - started);
        JsonNode feed = root.path("feed");
        Path out = outputRoot.resolve(providerName().toLowerCase(Locale.ROOT)).resolve("news_" + request.ticker + ".csv");
        Files.createDirectories(out.getParent());
        int rows = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("ticker,timePublished,title,summary,url,overallSentimentScore,overallSentimentLabel,source,agent");
            writer.newLine();
            if (feed.isArray()) {
                for (JsonNode n : feed) {
                    if (rows >= request.maxRows) break;
                    writer.write(String.join(",",
                            csv(request.ticker),
                            csv(n.path("time_published").asText("")),
                            csv(n.path("title").asText("")),
                            csv(n.path("summary").asText("")),
                            csv(n.path("url").asText("")),
                            fmt(n.path("overall_sentiment_score").asDouble(0.0)),
                            csv(n.path("overall_sentiment_label").asText("")),
                            providerName(),
                            csv(request.requestingAgent)
                    ));
                    writer.newLine();
                    rows++;
                }
            }
        }
        return new HistoricalDataResponse(request, true, providerName(), "downloaded news sentiment", out, rows, System.currentTimeMillis() - started);
    }

    private HistoricalDataResponse fetchIndicator(HistoricalDataRequest request, long started) throws Exception {
        String indicator = request.options.getOrDefault("indicator", "RSI").toUpperCase(Locale.ROOT);
        String interval = normalizeInterval(request.interval);
        String url = BASE_URL + "?function=" + enc(indicator) + "&symbol=" + enc(request.ticker) + "&interval=" + enc(interval) + "&time_period=14&series_type=close&apikey=" + enc(apiKey);
        JsonNode root = getJson(url);
        if (isRateLimited(root)) return new HistoricalDataResponse(request, false, providerName(), "rate limited or informational Alpha Vantage response", null, 0, System.currentTimeMillis() - started);
        Path out = outputRoot.resolve(providerName().toLowerCase(Locale.ROOT)).resolve("indicator_" + indicator + "_" + request.ticker + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, root.toPrettyString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return new HistoricalDataResponse(request, true, providerName(), "downloaded indicator " + indicator, out, root.size(), System.currentTimeMillis() - started);
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(45)).GET().header("accept", "application/json").build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) throw new IllegalStateException("HTTP " + res.statusCode() + " from Alpha Vantage");
        return MAPPER.readTree(res.body() == null ? "{}" : res.body());
    }

    private static boolean isRateLimited(JsonNode root) {
        if (root == null) return true;
        return root.has("Note") || root.has("Information") || root.has("Error Message");
    }

    private static String normalizeInterval(String interval) {
        String s = interval == null ? "1min" : interval.toLowerCase(Locale.ROOT).trim();
        if (s.equals("1m") || s.equals("1min") || s.equals("minute")) return "1min";
        if (s.equals("5m") || s.equals("5min")) return "5min";
        if (s.equals("15m") || s.equals("15min")) return "15min";
        if (s.equals("30m") || s.equals("30min")) return "30min";
        if (s.equals("60m") || s.equals("60min") || s.equals("1h")) return "60min";
        return "1min";
    }

    private static String env(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static String enc(String v) { return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8); }
    private static String safe(String v) { return v == null ? "" : v.replace('\n', ' ').replace('\r', ' '); }
    private static String csv(String v) { String s = safe(v).replace(',', ' '); return '"' + s.replace("\"", "\"\"") + '"'; }
    private static double num(String v) { try { return v == null || v.isBlank() ? 0.0 : Double.parseDouble(v.trim().replace("%", "")); } catch (Exception e) { return 0.0; } }
    private static String fmt(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) v = 0.0; return String.format(Locale.ROOT, "%.6f", v); }
}
