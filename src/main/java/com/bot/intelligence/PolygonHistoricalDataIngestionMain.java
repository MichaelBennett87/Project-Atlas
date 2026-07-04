package com.bot.intelligence;

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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional Polygon historical backfill utility for building the bot's local replay library.
 *
 * Required env var:
 *   POLYGON_API_KEY
 *
 * Optional env vars:
 *   POLYGON_HISTORICAL_TICKERS=AAPL,TSLA,NVDA
 *   POLYGON_HISTORICAL_FROM=2026-01-01
 *   POLYGON_HISTORICAL_TO=2026-06-28
 *   POLYGON_HISTORICAL_TIMESPAN=minute
 *   POLYGON_HISTORICAL_MULTIPLIER=1
 *   POLYGON_HISTORICAL_OUTPUT=logs/polygon_historical_bars.csv
 */
public final class PolygonHistoricalDataIngestionMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static void main(String[] args) throws Exception {
        String apiKey = env("POLYGON_API_KEY", "");
        if (apiKey.isBlank()) {
            System.out.println("POLYGON HISTORICAL INGESTION DISABLED: set POLYGON_API_KEY first.");
            return;
        }
        List<String> tickers = tickers(env("POLYGON_HISTORICAL_TICKERS", "AAPL,TSLA,NVDA"));
        LocalDate from = LocalDate.parse(env("POLYGON_HISTORICAL_FROM", LocalDate.now().minusDays(30).toString()));
        LocalDate to = LocalDate.parse(env("POLYGON_HISTORICAL_TO", LocalDate.now().toString()));
        String timespan = env("POLYGON_HISTORICAL_TIMESPAN", "minute");
        int multiplier = envInt("POLYGON_HISTORICAL_MULTIPLIER", 1);
        Path output = Path.of(env("POLYGON_HISTORICAL_OUTPUT", "logs/polygon_historical_bars.csv"));
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        boolean newFile = !Files.exists(output) || Files.size(output) == 0;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (newFile) {
                writer.write("ticker,timestamp,open,high,low,close,volume,vwap,transactions,source");
                writer.newLine();
            }
            int rows = 0;
            for (String ticker : tickers) {
                rows += fetchTicker(ticker, from, to, timespan, multiplier, apiKey, writer);
            }
            System.out.println("POLYGON HISTORICAL INGESTION COMPLETE: tickers=" + tickers.size() + " rows=" + rows + " output=" + output);
        }
    }

    private static int fetchTicker(String ticker, LocalDate from, LocalDate to, String timespan, int multiplier, String apiKey, BufferedWriter writer) throws Exception {
        String url = "https://api.polygon.io/v2/aggs/ticker/" + enc(ticker) + "/range/" + multiplier + "/" + enc(timespan) + "/" + from + "/" + to + "?adjusted=true&sort=asc&limit=50000&apiKey=" + enc(apiKey);
        int rows = 0;
        while (url != null && !url.isBlank()) {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().header("accept", "application/json").build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.out.println("POLYGON HISTORICAL WARNING: ticker=" + ticker + " http=" + response.statusCode());
                break;
            }
            JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    long ts = r.path("t").asLong(0L);
                    writer.write(String.join(",",
                            clean(ticker),
                            clean(Instant.ofEpochMilli(ts).toString()),
                            fmt(r.path("o").asDouble(0.0)),
                            fmt(r.path("h").asDouble(0.0)),
                            fmt(r.path("l").asDouble(0.0)),
                            fmt(r.path("c").asDouble(0.0)),
                            String.valueOf(r.path("v").asLong(0L)),
                            fmt(r.path("vw").asDouble(0.0)),
                            String.valueOf(r.path("n").asLong(0L)),
                            "POLYGON"
                    ));
                    writer.newLine();
                    rows++;
                }
            }
            String next = root.path("next_url").asText("");
            url = next.isBlank() ? null : next + (next.contains("apiKey=") ? "" : (next.contains("?") ? "&" : "?") + "apiKey=" + enc(apiKey));
        }
        return rows;
    }

    private static List<String> tickers(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String raw : csv.split(",")) {
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0.0;
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
