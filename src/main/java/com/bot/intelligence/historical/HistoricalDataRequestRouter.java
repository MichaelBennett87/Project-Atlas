package com.bot.intelligence.historical;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Central router for active REST historical data requests.
 *
 * Analyst agents call this instead of calling Polygon/Alpha Vantage directly. The
 * router chooses a provider, rate-limits politely, writes an audit journal, and
 * stores all data in the local historical repository consumed by nightly replay.
 */
public final class HistoricalDataRequestRouter {
    private final List<HistoricalDataProviderClient> clients = new ArrayList<>();
    private final Path journalPath;
    private long lastRequestAt = 0L;

    public HistoricalDataRequestRouter() {
        clients.add(new PolygonHistoricalDataClient());
        clients.add(new AlphaVantageHistoricalDataClient());
        this.journalPath = Path.of(System.getenv().getOrDefault("HISTORICAL_REQUEST_JOURNAL", "logs/historical_data_requests.csv"));
    }

    public HistoricalDataResponse request(HistoricalDataRequest request) {
        long minSpacingMs = Math.max(0L, longEnv("HISTORICAL_REST_MIN_SPACING_MS", polygonPremiumMode() ? 150L : 12_000L));
        sleepIfNeeded(minSpacingMs);
        HistoricalDataProviderClient client = selectClient(request);
        HistoricalDataResponse response;
        if (client == null) {
            response = new HistoricalDataResponse(request, false, "NONE", "no enabled provider supports request", null, 0, 0);
        } else {
            response = client.fetch(request);
        }
        lastRequestAt = System.currentTimeMillis();
        journal(response);
        return response;
    }

    private HistoricalDataProviderClient selectClient(HistoricalDataRequest request) {
        if (request == null) return null;
        if (request.provider != HistoricalDataRequest.Provider.AUTO) {
            for (HistoricalDataProviderClient c : clients) {
                if (c.enabled() && c.providerName().equalsIgnoreCase(request.provider.name()) && c.supports(request)) return c;
            }
            return null;
        }
        // Use Polygon for large bar/trade/news history; use Alpha Vantage for fundamentals/indicators.
        for (HistoricalDataProviderClient c : clients) {
            if (!c.enabled() || !c.supports(request)) continue;
            if ((request.dataType == HistoricalDataRequest.DataType.FUNDAMENTALS ||
                 request.dataType == HistoricalDataRequest.DataType.OVERVIEW ||
                 request.dataType == HistoricalDataRequest.DataType.INDICATOR) &&
                    c.providerName().equalsIgnoreCase("ALPHA_VANTAGE")) return c;
            if ((request.dataType == HistoricalDataRequest.DataType.BARS ||
                 request.dataType == HistoricalDataRequest.DataType.TRADES ||
                 request.dataType == HistoricalDataRequest.DataType.QUOTES ||
                 request.dataType == HistoricalDataRequest.DataType.NEWS) &&
                    c.providerName().equalsIgnoreCase("POLYGON")) return c;
        }
        for (HistoricalDataProviderClient c : clients) {
            if (c.enabled() && c.supports(request)) return c;
        }
        return null;
    }

    private void sleepIfNeeded(long minSpacingMs) {
        if (minSpacingMs <= 0) return;
        long elapsed = System.currentTimeMillis() - lastRequestAt;
        long sleep = minSpacingMs - elapsed;
        if (sleep <= 0 || lastRequestAt <= 0) return;
        try { Thread.sleep(Math.min(sleep, 120_000L)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void journal(HistoricalDataResponse r) {
        try {
            Path parent = journalPath.getParent(); if (parent != null) Files.createDirectories(parent);
            boolean newFile = !Files.exists(journalPath) || Files.size(journalPath) == 0;
            try (BufferedWriter w = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (newFile) {
                    w.write("completedAt,success,provider,agent,type,ticker,from,to,interval,rows,elapsedMs,output,message");
                    w.newLine();
                }
                HistoricalDataRequest q = r.request;
                w.write(String.join(",",
                        csv(Instant.now().toString()),
                        String.valueOf(r.success),
                        csv(r.provider),
                        csv(q == null ? "" : q.requestingAgent),
                        csv(q == null ? "" : q.dataType.name()),
                        csv(q == null ? "" : q.ticker),
                        csv(q == null ? "" : q.from.toString()),
                        csv(q == null ? "" : q.to.toString()),
                        csv(q == null ? "" : q.interval),
                        String.valueOf(r.rows),
                        String.valueOf(r.elapsedMs),
                        csv(r.outputPath == null ? "" : r.outputPath.toString()),
                        csv(r.message)
                ));
                w.newLine();
            }
        } catch (Exception e) {
            System.out.println("HISTORICAL REQUEST JOURNAL FAILED: " + e.getMessage());
        }
    }

    private static boolean polygonPremiumMode() { return boolEnv("POLYGON_PREMIUM_MODE", true); }
    private static boolean boolEnv(String key, boolean fallback) { String v = System.getenv(key); if (v == null || v.isBlank()) return fallback; String s = v.trim().toLowerCase(Locale.ROOT); return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on"); }
    private static long longEnv(String key, long fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); } catch (Exception e) { return fallback; } }
    private static String csv(String v) { String s = v == null ? "" : v.replace("\r", " ").replace("\n", " "); return '"' + s.replace("\"", "\"\"") + '"'; }
}
