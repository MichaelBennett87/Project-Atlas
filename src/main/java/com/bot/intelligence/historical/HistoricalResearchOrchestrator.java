package com.bot.intelligence.historical;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * After-hours historical research coordinator.
 *
 * UnifiedStrategyMain should be stopped before this runs.  It lets specialized
 * analyst agents actively request REST historical data, caches that data locally,
 * builds training labels, and runs replay/simulation so tomorrow's policies are
 * improved from real historical market behavior rather than only today's live logs.
 */
public final class HistoricalResearchOrchestrator {
    private final HistoricalDataRequestRouter router = new HistoricalDataRequestRouter();
    private final AgentHistoricalResearchTool tool = new AgentHistoricalResearchTool(router);

    public Result runNightlyResearch() {
        long started = System.currentTimeMillis();
        if (!envBoolean("NIGHTLY_HISTORICAL_RESEARCH_ENABLED", true)) {
            return new Result(0, 0, 0, 0, "disabled", System.currentTimeMillis() - started);
        }

        LocalDate to = LocalDate.parse(env("NIGHTLY_HISTORICAL_TO", LocalDate.now().toString()));
        LocalDate from = LocalDate.parse(env("NIGHTLY_HISTORICAL_FROM", to.minusDays(intEnv("NIGHTLY_HISTORICAL_LOOKBACK_DAYS", 5)).toString()));
        String interval = env("NIGHTLY_HISTORICAL_INTERVAL", "1min");
        int maxTickers = Math.max(1, intEnv("NIGHTLY_HISTORICAL_MAX_TICKERS", polygonPremiumMode() ? 600 : 25));
        int maxRowsPerTicker = Math.max(100, intEnv("NIGHTLY_HISTORICAL_MAX_ROWS_PER_TICKER", 50_000));
        List<String> tickers = selectResearchTickers(maxTickers);

        List<HistoricalDataResponse> responses = new ArrayList<>();
        for (String ticker : tickers) {
            responses.add(tool.requestBars("TECHNICAL_ANALYST_AGENT", ticker, from, to, interval, maxRowsPerTicker));
            if (polygonPremiumMode() && envBoolean("NIGHTLY_POLYGON_PREMIUM_TRADE_QUOTE_RESEARCH", false)) {
                responses.add(tool.requestTrades("MICROSTRUCTURE_AGENT", ticker, from, to, maxRowsPerTicker));
                responses.add(tool.requestQuotes("LIQUIDITY_AGENT", ticker, from, to, maxRowsPerTicker));
            }
            if (envBoolean("NIGHTLY_HISTORICAL_NEWS_ENABLED", true)) {
                responses.add(tool.requestNews("NEWS_SENTIMENT_ANALYST_AGENT", ticker, from, to, intEnv("NIGHTLY_HISTORICAL_MAX_NEWS_ROWS", 100)));
            }
            if (envBoolean("NIGHTLY_HISTORICAL_FUNDAMENTALS_ENABLED", true)) {
                responses.add(tool.requestFundamentals("FUNDAMENTAL_ANALYST_AGENT", ticker));
            }
        }

        int success = 0, failed = 0, rows = 0;
        for (HistoricalDataResponse r : responses) {
            if (r.success) success++; else failed++;
            rows += Math.max(0, r.rows);
        }
        NightlySimulationRunner.Result sim = new NightlySimulationRunner().run();
        writeReport(tickers, responses, sim, from, to, interval);
        return new Result(tickers.size(), success, failed, rows, sim.summary(), System.currentTimeMillis() - started);
    }

    private List<String> selectResearchTickers(int maxTickers) {
        Set<String> out = new LinkedHashSet<>();
        addCsv(out, env("NIGHTLY_HISTORICAL_TICKERS", ""));
        readTickerColumn(out, Path.of(env("NIGHTLY_RESEARCH_TICKER_SOURCE", "logs/unified_candidate_scores.csv")), 0, maxTickers);
        readTickerColumn(out, Path.of("logs/nightly_ticker_personality.csv"), 0, maxTickers);
        readTickerColumn(out, Path.of("logs/opportunity_memory.csv"), 0, maxTickers);
        readTickerColumn(out, Path.of("logs/stock_memory.csv"), 0, maxTickers);
        if (out.isEmpty()) addCsv(out, env("NIGHTLY_HISTORICAL_DEFAULT_TICKERS", "SPY,QQQ,IWM,AAPL,NVDA,TSLA,META,AMD,SMCI,COIN"));
        List<String> list = new ArrayList<>();
        for (String t : out) {
            if (list.size() >= maxTickers) break;
            if (isTicker(t)) list.add(t.toUpperCase(Locale.ROOT));
        }
        return list;
    }

    private static void readTickerColumn(Set<String> out, Path path, int fallbackColumn, int maxTickers) {
        if (out.size() >= maxTickers || !Files.exists(path)) return;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            int idx = findTickerIndex(header, fallbackColumn);
            String line;
            while ((line = r.readLine()) != null && out.size() < maxTickers) {
                List<String> c = parse(line);
                if (idx >= 0 && idx < c.size()) {
                    String t = c.get(idx).trim().toUpperCase(Locale.ROOT);
                    if (isTicker(t)) out.add(t);
                }
            }
        } catch (Exception ignored) {}
    }

    private static int findTickerIndex(String header, int fallback) {
        if (header == null) return fallback;
        List<String> h = parse(header);
        for (int i = 0; i < h.size(); i++) {
            String n = h.get(i).trim().toLowerCase(Locale.ROOT);
            if (n.equals("ticker") || n.equals("symbol")) return i;
        }
        return fallback;
    }

    private void writeReport(List<String> tickers, List<HistoricalDataResponse> responses, NightlySimulationRunner.Result sim,
                             LocalDate from, LocalDate to, String interval) {
        Path report = Path.of(env("NIGHTLY_HISTORICAL_RESEARCH_REPORT", "logs/nightly_historical_research_report.csv"));
        try {
            Path parent = report.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(report, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                w.write("generatedAt,from,to,interval,tickers,simulationSummary");
                w.newLine();
                w.write(String.join(",", csv(Instant.now().toString()), csv(from.toString()), csv(to.toString()), csv(interval), csv(String.join("|", tickers)), csv(sim.summary())));
                w.newLine();
                w.newLine();
                w.write("agent,provider,type,ticker,success,rows,elapsedMs,output,message");
                w.newLine();
                for (HistoricalDataResponse r : responses) {
                    HistoricalDataRequest q = r.request;
                    w.write(String.join(",",
                            csv(q.requestingAgent), csv(r.provider), csv(q.dataType.name()), csv(q.ticker),
                            String.valueOf(r.success), String.valueOf(r.rows), String.valueOf(r.elapsedMs),
                            csv(r.outputPath == null ? "" : r.outputPath.toString()), csv(r.message)
                    ));
                    w.newLine();
                }
            }
        } catch (Exception e) {
            System.out.println("NIGHTLY HISTORICAL RESEARCH REPORT FAILED: " + e.getMessage());
        }
    }

    public static final class Result {
        public final int tickers, success, failed, rows; public final String simulationSummary; public final long elapsedMs;
        Result(int tickers, int success, int failed, int rows, String simulationSummary, long elapsedMs) { this.tickers = tickers; this.success = success; this.failed = failed; this.rows = rows; this.simulationSummary = simulationSummary; this.elapsedMs = elapsedMs; }
        public String summary() { return "tickers=" + tickers + " success=" + success + " failed=" + failed + " rows=" + rows + " simulation={" + simulationSummary + "} elapsedMs=" + elapsedMs; }
    }

    private static void addCsv(Set<String> out, String csv) { if (csv == null) return; for (String raw : csv.split(",")) { String t = raw.trim().toUpperCase(Locale.ROOT); if (isTicker(t)) out.add(t); } }
    private static boolean isTicker(String t) { return t != null && t.matches("[A-Z][A-Z0-9.-]{0,9}"); }
    private static String env(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static int intEnv(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static boolean envBoolean(String k, boolean f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : Boolean.parseBoolean(v.trim()); }
    private static boolean polygonPremiumMode() { return envBoolean("POLYGON_PREMIUM_MODE", true); }
    private static String csv(String v) { String s = v == null ? "" : v.replace("\r", " ").replace("\n", " "); return '"' + s.replace("\"", "\"\"") + '"'; }
    private static List<String> parse(String line) { List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false; if (line == null) return out; for (int i=0;i<line.length();i++){ char ch=line.charAt(i); if(q){ if(ch=='\"'){ if(i+1<line.length()&&line.charAt(i+1)=='\"'){cur.append('"');i++;}else q=false;} else cur.append(ch);} else { if(ch==','){out.add(cur.toString());cur.setLength(0);} else if(ch=='\"') q=true; else cur.append(ch);} } out.add(cur.toString()); return out; }
}
