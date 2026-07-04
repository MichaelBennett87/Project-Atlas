package com.bot.intelligence;

import com.bot.master.CatalystQualityGate;
import com.bot.model.NewsEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Flexible local historical-news CSV library for offline replay.
 *
 * Supported columns are intentionally broad: ticker/symbol/symbols, timestamp/
 * created_at/published_at/date, headline/title, content/summary/description,
 * source/provider, catalystScore, and sentimentScore.
 */
public final class HistoricalNewsRepository {
    private final Path root = Path.of(env("HISTORICAL_NEWS_DATA_DIR", "logs/historical_news"));
    private final Path jsonRoot = Path.of(env("HISTORICAL_NEWS_JSON_DIR", "logs/active_rest_research_cache/polygon/news"));
    private final Path explicitPath = Path.of(env("HISTORICAL_NEWS_CSV_PATH", "logs/historical_news.csv"));
    private final ObjectMapper mapper = new ObjectMapper();

    public LoadedNews load(int maxRows) {
        Map<String, List<NewsEvent>> byTicker = new LinkedHashMap<>();
        List<Path> files = csvFiles();
        int rows = 0;
        for (Path file : files) {
            if (rows >= maxRows) {
                break;
            }
            rows += loadFile(file, Math.max(0, maxRows - rows), byTicker);
        }
        List<Path> jsonFiles = jsonFiles();
        for (Path file : jsonFiles) {
            if (rows >= maxRows) {
                break;
            }
            rows += loadJsonFile(file, Math.max(0, maxRows - rows), byTicker);
        }
        for (List<NewsEvent> events : byTicker.values()) {
            events.sort(Comparator
                    .comparingLong(NewsEvent::getTimestamp)
                    .thenComparing(e -> e.getId() == null ? "" : e.getId()));
        }
        return new LoadedNews(byTicker, files.size() + jsonFiles.size(), rows, root);
    }

    private int loadFile(Path file, int maxRows, Map<String, List<NewsEvent>> byTicker) {
        if (file == null || !Files.exists(file) || maxRows <= 0) {
            return 0;
        }
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return 0;
            }
            CsvHeader header = new CsvHeader(HistoricalMarketDataRepository.parse(headerLine));
            String line;
            while ((line = reader.readLine()) != null && rows < maxRows) {
                List<String> c = HistoricalMarketDataRepository.parse(line);
                String headline = firstNonBlank(header.get(c, "headline"), header.get(c, "title"));
                if (headline.isBlank()) {
                    continue;
                }
                String rawTickers = firstNonBlank(header.get(c, "ticker"), header.get(c, "symbol"),
                        header.get(c, "symbols"), header.get(c, "tickers"));
                List<String> tickers = splitTickers(rawTickers);
                if (tickers.isEmpty()) {
                    continue;
                }
                String id = firstNonBlank(header.get(c, "id"), header.get(c, "article_id"), header.get(c, "url"));
                String content = firstNonBlank(header.get(c, "content"), header.get(c, "summary"),
                        header.get(c, "description"), header.get(c, "body"), header.get(c, "text"));
                String source = firstNonBlank(header.get(c, "source"), header.get(c, "provider"), header.get(c, "publisher"));
                long timestamp = parseTimestamp(firstNonBlank(
                        header.get(c, "timestamp"),
                        header.get(c, "created_at"),
                        header.get(c, "createdAt"),
                        header.get(c, "published_at"),
                        header.get(c, "published_utc"),
                        header.get(c, "time"),
                        header.get(c, "date")));
                double catalyst = num(firstNonBlank(header.get(c, "catalystScore"), header.get(c, "catalyst_score")), Double.NaN);
                double sentiment = num(firstNonBlank(header.get(c, "sentimentScore"), header.get(c, "sentiment_score")), 0.0);

                addEvents(byTicker, tickers, id.isBlank() ? file.getFileName() + ":" + rows : id, headline,
                        content, source.isBlank() ? "HISTORICAL_NEWS_CSV" : source, timestamp, sentiment, catalyst);
                rows++;
            }
        } catch (Exception e) {
            System.out.println("HISTORICAL NEWS LOAD FAILED: file=" + file + " error=" + e.getMessage());
        }
        return rows;
    }

    private int loadJsonFile(Path file, int maxRows, Map<String, List<NewsEvent>> byTicker) {
        if (file == null || !Files.exists(file) || maxRows <= 0) {
            return 0;
        }
        int rows = 0;
        try {
            JsonNode rootNode = mapper.readTree(file.toFile());
            JsonNode articles = articleArray(rootNode);
            if (!articles.isArray()) {
                return 0;
            }
            for (JsonNode item : articles) {
                if (rows >= maxRows) {
                    break;
                }
                String headline = firstText(item, "headline", "title");
                if (headline.isBlank()) {
                    continue;
                }
                List<String> tickers = tickersFromJson(item);
                if (tickers.isEmpty()) {
                    continue;
                }
                String id = firstText(item, "id", "article_id", "article_url", "url");
                String content = firstText(item, "content", "summary", "description", "body", "text");
                String source = firstText(item, "source", "provider", "publisher");
                if (source.isBlank() && item.path("publisher").isObject()) {
                    source = firstText(item.path("publisher"), "name", "homepage_url");
                }
                long timestamp = parseTimestamp(firstText(item, "timestamp", "created_at", "createdAt",
                        "published_at", "published_utc", "time", "date"));
                double sentiment = num(firstText(item, "sentimentScore", "sentiment_score"), 0.0);
                double catalyst = num(firstText(item, "catalystScore", "catalyst_score"), Double.NaN);
                addEvents(byTicker, tickers, id.isBlank() ? file.getFileName() + ":" + rows : id, headline,
                        content, source.isBlank() ? "POLYGON_NEWS_JSON" : source, timestamp, sentiment, catalyst);
                rows++;
            }
        } catch (Exception e) {
            System.out.println("HISTORICAL NEWS JSON LOAD FAILED: file=" + file + " error=" + e.getMessage());
        }
        return rows;
    }

    private void addEvents(Map<String, List<NewsEvent>> byTicker, List<String> tickers, String id, String headline,
                           String content, String source, long timestamp, double sentiment, double catalyst) {
        for (String ticker : tickers) {
            NewsEvent event = new NewsEvent(
                    (id == null || id.isBlank() ? "historical_news" : id) + ":" + ticker,
                    ticker,
                    headline,
                    content,
                    timestamp
            );
            event.setSource(source == null || source.isBlank() ? "HISTORICAL_NEWS" : source);
            event.setProviderTimestamp(timestamp);
            event.setBotFirstSeenAt(timestamp);
            event.setSentimentScore(sentiment);
            event.setCatalystScore(Double.isFinite(catalyst) ? catalyst : CatalystQualityGate.tradeableCatalystScore(event));
            byTicker.computeIfAbsent(ticker, ignored -> new ArrayList<>()).add(event);
        }
    }

    private List<Path> csvFiles() {
        Set<Path> files = new LinkedHashSet<>();
        addIfCsv(files, explicitPath);
        for (String raw : env("BAR_SIM_HISTORICAL_NEWS_PATHS", "").split(",")) {
            if (raw != null && !raw.isBlank()) {
                addIfCsv(files, Path.of(raw.trim()));
            }
        }
        addIfCsv(files, Path.of("logs/news_events.csv"));
        addIfCsv(files, Path.of("logs/alpaca_historical_news.csv"));
        addIfCsv(files, Path.of("logs/polygon_news.csv"));
        addIfCsv(files, Path.of("logs/catalyst_news.csv"));
        if (Files.exists(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(files::add);
            } catch (Exception e) {
                System.out.println("HISTORICAL NEWS SCAN FAILED: " + e.getMessage());
            }
        }
        return new ArrayList<>(files);
    }

    private List<Path> jsonFiles() {
        List<Path> out = new ArrayList<>();
        int maxFiles = Math.max(1, intEnv("BAR_SIM_MAX_NEWS_JSON_FILES", 2_000));
        if (!Files.exists(jsonRoot)) {
            return out;
        }
        try (Stream<Path> stream = Files.walk(jsonRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(maxFiles)
                    .forEach(out::add);
        } catch (Exception e) {
            System.out.println("HISTORICAL NEWS JSON SCAN FAILED: " + e.getMessage());
        }
        return out;
    }

    private static JsonNode articleArray(JsonNode rootNode) {
        if (rootNode == null) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        if (rootNode.isArray()) {
            return rootNode;
        }
        if (rootNode.path("results").isArray()) {
            return rootNode.path("results");
        }
        if (rootNode.path("news").isArray()) {
            return rootNode.path("news");
        }
        if (rootNode.path("articles").isArray()) {
            return rootNode.path("articles");
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static List<String> tickersFromJson(JsonNode item) {
        List<String> out = new ArrayList<>();
        for (String key : List.of("ticker", "symbol", "symbols", "tickers")) {
            JsonNode node = item.path(key);
            if (node.isArray()) {
                for (JsonNode tickerNode : node) {
                    String ticker = normalizeTicker(tickerNode.asText(""));
                    if (!ticker.isBlank()) {
                        out.add(ticker);
                    }
                }
            } else if (!node.asText("").isBlank()) {
                out.addAll(splitTickers(node.asText("")));
            }
        }
        return out;
    }

    private static String firstText(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        for (String name : names) {
            JsonNode child = node.path(name);
            if (child.isValueNode()) {
                String value = child.asText("");
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static void addIfCsv(Set<Path> files, Path path) {
        if (path != null && Files.exists(path) && Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            files.add(path);
        }
    }

    public static NewsEvent latestFresh(Map<String, List<NewsEvent>> byTicker, String ticker, long atMs, long maxAgeMs) {
        if (byTicker == null || ticker == null || ticker.isBlank()) {
            return null;
        }
        List<NewsEvent> events = byTicker.get(ticker.trim().toUpperCase(Locale.ROOT));
        if (events == null || events.isEmpty()) {
            return null;
        }
        int lo = 0;
        int hi = events.size() - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long ts = events.get(mid).getTimestamp();
            if (ts <= atMs) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best < 0) {
            return null;
        }
        NewsEvent event = events.get(best);
        long age = Math.max(0L, atMs - event.getTimestamp());
        if (maxAgeMs > 0L && age > maxAgeMs) {
            return null;
        }
        return copyForReplay(event, age);
    }

    private static NewsEvent copyForReplay(NewsEvent source, long ageMs) {
        NewsEvent copy = new NewsEvent(
                source.getId(),
                source.getTicker(),
                source.getHeadline(),
                source.getContent(),
                source.getTimestamp()
        );
        copy.setSource(source.getSource());
        copy.setProviderTimestamp(source.getProviderTimestamp());
        copy.setBotFirstSeenAt(source.getTimestamp());
        copy.setSourceLagMs(ageMs);
        copy.setSentimentScore(source.getSentimentScore());
        copy.setCatalystScore(source.getCatalystScore());
        copy.setFreshnessReason("historical_replay_age_ms=" + ageMs);
        return copy;
    }

    private static List<String> splitTickers(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.replace('[', ' ').replace(']', ' ').replace('"', ' ').split("[,;|\\s]+")) {
            String ticker = normalizeTicker(part);
            if (!ticker.isBlank()) {
                out.add(ticker);
            }
        }
        return out;
    }

    private static String normalizeTicker(String raw) {
        if (raw == null) {
            return "";
        }
        String ticker = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (ticker.isBlank() || ticker.endsWith("USD") || ticker.length() > 6) {
            return "";
        }
        return ticker;
    }

    private static long parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String value = raw.trim();
        try {
            if (value.matches("^-?\\d+$")) {
                long n = Long.parseLong(value);
                if (n > 100_000_000_000L) {
                    return n;
                }
                return n * 1_000L;
            }
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static double num(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intEnv(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    static final class CsvHeader {
        private final Map<String, Integer> idx = new LinkedHashMap<>();

        CsvHeader(List<String> h) {
            for (int i = 0; i < h.size(); i++) {
                idx.put(h.get(i).trim().toLowerCase(Locale.ROOT), i);
            }
        }

        String get(List<String> c, String name) {
            Integer i = idx.get(name.toLowerCase(Locale.ROOT));
            return i == null || i >= c.size() ? "" : c.get(i);
        }
    }

    public static final class LoadedNews {
        public final Map<String, List<NewsEvent>> byTicker;
        public final int files;
        public final int rows;
        public final Path root;

        LoadedNews(Map<String, List<NewsEvent>> byTicker, int files, int rows, Path root) {
            this.byTicker = byTicker;
            this.files = files;
            this.rows = rows;
            this.root = root;
        }

        public static LoadedNews empty(Path root) {
            return new LoadedNews(new LinkedHashMap<>(), 0, 0, root);
        }
    }
}
