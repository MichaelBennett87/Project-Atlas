package com.bot.intelligence;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Local historical market-data library.
 *
 * Drop CSV files exported from Polygon/Databento/FirstRate/Nasdaq into HISTORICAL_MARKET_DATA_DIR
 * (default logs/historical_market_data). Files can be named TICKER.csv or include a ticker column.
 * Expected columns are flexible: timestamp/time/date, ticker/symbol, open/high/low/close/volume.
 */
public final class HistoricalMarketDataRepository {
    private final Path root;
    private final int maxFiles;

    public HistoricalMarketDataRepository() {
        this.root = Path.of(System.getenv().getOrDefault("HISTORICAL_MARKET_DATA_DIR", "logs/historical_market_data"));
        this.maxFiles = Math.max(1, envInt("HISTORICAL_MARKET_DATA_MAX_FILES", 10_000));
    }

    public Path root() { return root; }

    public List<Path> csvFiles() {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(maxFiles)
                    .toList();
        } catch (Exception e) {
            System.out.println("HISTORICAL MARKET DATA SCAN FAILED: " + e.getMessage());
            return List.of();
        }
    }

    public List<HistoricalBar> loadBars(Path file, int maxRows) {
        List<HistoricalBar> bars = new ArrayList<>();
        if (file == null || !Files.exists(file)) return bars;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return bars;
            CsvHeader header = new CsvHeader(parse(headerLine));
            String inferredTicker = inferTicker(file);
            String line;
            while ((line = reader.readLine()) != null && bars.size() < maxRows) {
                List<String> c = parse(line);
                String ticker = firstNonBlank(header.get(c, "ticker"), header.get(c, "symbol"), inferredTicker);
                double open = num(firstNonBlank(header.get(c, "open"), header.get(c, "o")), 0);
                double high = num(firstNonBlank(header.get(c, "high"), header.get(c, "h")), 0);
                double low = num(firstNonBlank(header.get(c, "low"), header.get(c, "l")), 0);
                double close = num(firstNonBlank(header.get(c, "close"), header.get(c, "c"), header.get(c, "price")), 0);
                double volume = num(firstNonBlank(header.get(c, "volume"), header.get(c, "v")), 0);
                String ts = firstNonBlank(header.get(c, "timestamp"), header.get(c, "time"), header.get(c, "date"), header.get(c, "datetime"));
                if (ticker.isBlank() || close <= 0.0) continue;
                bars.add(new HistoricalBar(ticker.toUpperCase(Locale.ROOT), ts, open, high, low, close, volume));
            }
        } catch (Exception e) {
            System.out.println("HISTORICAL MARKET DATA LOAD FAILED: file=" + file + " error=" + e.getMessage());
        }
        return bars;
    }

    private static String inferTicker(Path file) {
        String name = file.getFileName().toString();
        int dot = name.indexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replaceAll("[^A-Za-z0-9.-]", "").toUpperCase(Locale.ROOT);
    }

    public static final class HistoricalBar {
        public final String ticker;
        public final String timestamp;
        public final double open, high, low, close, volume;
        HistoricalBar(String ticker, String timestamp, double open, double high, double low, double close, double volume) {
            this.ticker = ticker; this.timestamp = timestamp; this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
        }
    }

    static final class CsvHeader {
        private final java.util.Map<String, Integer> idx = new java.util.LinkedHashMap<>();
        CsvHeader(List<String> h) { for (int i = 0; i < h.size(); i++) idx.put(h.get(i).trim().toLowerCase(Locale.ROOT), i); }
        String get(List<String> c, String name) { Integer i = idx.get(name.toLowerCase(Locale.ROOT)); return i == null || i >= c.size() ? "" : c.get(i); }
    }

    static List<String> parse(String line) {
        List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false;
        if (line == null) return out;
        for (int i = 0; i < line.length(); i++) { char ch = line.charAt(i); if (q) { if (ch == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false; } else cur.append(ch); } else { if (ch == ',') { out.add(cur.toString()); cur.setLength(0); } else if (ch == '"') q = true; else cur.append(ch); } }
        out.add(cur.toString()); return out;
    }
    static String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v.trim(); return ""; }
    static double num(String v, double f) { try { return v == null || v.isBlank() ? f : Double.parseDouble(v.trim().replace("%", "")); } catch (Exception e) { return f; } }
    static int envInt(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; } }
}
