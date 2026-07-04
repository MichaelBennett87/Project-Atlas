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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Native Alpha Vantage adapter for the Market Intelligence Bus.
 *
 * This adapter is intentionally conservative because the free Alpha Vantage tier is rate limited.
 * It is best used as a corroboration/research provider for fundamentals, headline sentiment,
 * broad-market context, and slower technical confirmation rather than as the primary real-time feed.
 *
 * Environment variables:
 *   ALPHA_VANTAGE_API_KEY              required to enable this provider
 *   ALPHA_VANTAGE_PROVIDER_ENABLED     optional, default true
 *   ALPHA_VANTAGE_SYMBOLS              optional comma-separated symbols, default SPY,QQQ,IWM,AAPL,NVDA,TSLA
 *   ALPHA_VANTAGE_POLL_MS              optional, default 300000
 *   ALPHA_VANTAGE_MAX_CALLS_PER_CYCLE  optional, default 2
 *   ALPHA_VANTAGE_ENABLE_QUOTES        optional, default true
 *   ALPHA_VANTAGE_ENABLE_OVERVIEW      optional, default true
 *   ALPHA_VANTAGE_ENABLE_NEWS          optional, default true
 *   ALPHA_VANTAGE_ENABLE_RSI           optional, default false
 */
public class AlphaVantageMarketDataProvider implements MarketDataProvider {
    private static final String BASE_URL = "https://www.alphavantage.co/query";
    private static final long DEFAULT_POLL_MS = 300_000L;
    private static final long DEFAULT_OVERVIEW_REFRESH_MS = 6L * 60L * 60L * 1000L;
    private static final long DEFAULT_NEWS_REFRESH_MS = 30L * 60L * 1000L;

    private final String apiKey;
    private final boolean enabled;
    private final long pollMs;
    private final int maxCallsPerCycle;
    private final List<String> symbols;
    private final boolean quotesEnabled;
    private final boolean overviewEnabled;
    private final boolean newsEnabled;
    private final boolean rsiEnabled;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Long> overviewLastPollMs = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile int cursor = 0;
    private volatile long lastNewsPollMs = 0L;

    public AlphaVantageMarketDataProvider() {
        this.apiKey = env("ALPHA_VANTAGE_API_KEY", "");
        this.enabled = envBoolean("ALPHA_VANTAGE_PROVIDER_ENABLED", true) && !apiKey.isBlank();
        this.pollMs = Math.max(60_000L, envLong("ALPHA_VANTAGE_POLL_MS", DEFAULT_POLL_MS));
        this.maxCallsPerCycle = Math.max(1, Math.min(10, envInt("ALPHA_VANTAGE_MAX_CALLS_PER_CYCLE", 2)));
        this.symbols = parseSymbols(env("ALPHA_VANTAGE_SYMBOLS", env("ALPHA_VANTAGE_DEFAULT_SYMBOLS", "SPY,QQQ,IWM,AAPL,NVDA,TSLA")));
        this.quotesEnabled = envBoolean("ALPHA_VANTAGE_ENABLE_QUOTES", true);
        this.overviewEnabled = envBoolean("ALPHA_VANTAGE_ENABLE_OVERVIEW", true);
        this.newsEnabled = envBoolean("ALPHA_VANTAGE_ENABLE_NEWS", true);
        this.rsiEnabled = envBoolean("ALPHA_VANTAGE_ENABLE_RSI", false);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return "ALPHA_VANTAGE";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void start(Consumer<MarketIntelligenceSignal> signalConsumer) {
        if (!enabled()) {
            if (!envBoolean("ALPHA_VANTAGE_SUPPRESS_DISABLED_LOG", false)) {
                System.out.println("ALPHA VANTAGE PROVIDER DISABLED: set ALPHA_VANTAGE_API_KEY to enable native Alpha Vantage ingestion.");
            }
            MarketIntelligenceBus.getInstance().recordProviderStatus(name(), "DISABLED_NO_API_KEY");
            return;
        }
        if (running) return;
        running = true;
        Thread thread = new Thread(() -> loop(signalConsumer));
        thread.setName("market-intelligence-provider-alpha-vantage");
        thread.setDaemon(true);
        thread.start();
        System.out.println("ALPHA VANTAGE PROVIDER STARTED: pollMs=" + pollMs +
                " maxCallsPerCycle=" + maxCallsPerCycle +
                " symbols=" + symbols.size() +
                " quotes=" + quotesEnabled +
                " overview=" + overviewEnabled +
                " news=" + newsEnabled +
                " rsi=" + rsiEnabled +
                " adaptiveScheduling=" + System.getenv().getOrDefault("ADAPTIVE_PROVIDER_SCHEDULING_ENABLED", "true") +
                " env=ALPHA_VANTAGE_API_KEY");
    }

    @Override
    public void stop() {
        running = false;
    }

    private void loop(Consumer<MarketIntelligenceSignal> signalConsumer) {
        while (running) {
            try {
                int emitted = poll(signalConsumer);
                MarketIntelligenceBus.getInstance().recordProviderStatus(name(), "OK emitted=" + emitted);
                Thread.sleep(AdaptiveProviderScheduler.nextSleepMs(name(), pollMs, emitted));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception e) {
                MarketIntelligenceBus.getInstance().recordProviderStatus(name(), "ERROR:" + safe(e.getMessage()));
                sleep(AdaptiveProviderScheduler.nextSleepMs(name(), Math.max(pollMs, 300_000L), 0));
            }
        }
    }

    private int poll(Consumer<MarketIntelligenceSignal> signalConsumer) throws Exception {
        int callsUsed = 0;
        int emitted = 0;
        if (symbols.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        if (newsEnabled && callsUsed < maxCallsPerCycle && now - lastNewsPollMs >= envLong("ALPHA_VANTAGE_NEWS_REFRESH_MS", DEFAULT_NEWS_REFRESH_MS)) {
            emitted += pollNews(signalConsumer);
            callsUsed++;
            lastNewsPollMs = now;
        }

        int visited = 0;
        while (callsUsed < maxCallsPerCycle && visited < symbols.size()) {
            String symbol = symbols.get(cursor % symbols.size());
            cursor = (cursor + 1) % Math.max(1, symbols.size());
            visited++;

            if (quotesEnabled && callsUsed < maxCallsPerCycle) {
                emitted += pollQuote(symbol, signalConsumer);
                callsUsed++;
            }

            if (rsiEnabled && callsUsed < maxCallsPerCycle) {
                emitted += pollRsi(symbol, signalConsumer);
                callsUsed++;
            }

            if (overviewEnabled && callsUsed < maxCallsPerCycle && overviewDue(symbol, now)) {
                emitted += pollOverview(symbol, signalConsumer);
                callsUsed++;
                overviewLastPollMs.put(symbol, now);
            }
        }
        return emitted;
    }

    private boolean overviewDue(String symbol, long now) {
        long refreshMs = envLong("ALPHA_VANTAGE_OVERVIEW_REFRESH_MS", DEFAULT_OVERVIEW_REFRESH_MS);
        return now - overviewLastPollMs.getOrDefault(symbol, 0L) >= refreshMs;
    }

    private int pollQuote(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) throws Exception {
        JsonNode root = getJson(url("GLOBAL_QUOTE", "symbol=" + enc(symbol)));
        if (isRateLimited(root)) return 0;
        JsonNode quote = root.path("Global Quote");
        if (!quote.isObject()) return 0;
        double price = parseDouble(quote.path("05. price").asText("0"));
        double changePct = parsePercent(quote.path("10. change percent").asText("0"));
        double volume = parseDouble(quote.path("06. volume").asText("0"));
        if (price <= 0.0) return 0;
        double priority = clamp(Math.abs(changePct) / 12.0 * 0.55 + Math.min(1.0, Math.log10(Math.max(10.0, volume)) / 8.0) * 0.45);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("provider", name());
        metadata.put("rawMode", "ALPHA_VANTAGE_GLOBAL_QUOTE");
        metadata.put("price", fmt(price));
        metadata.put("changePct", fmt(changePct));
        metadata.put("volume", fmt(volume));
        signalConsumer.accept(new MarketIntelligenceSignal(
                name(),
                MarketIntelligenceSignalType.MARKET_DATA,
                symbol,
                "Alpha Vantage quote: " + symbol + " change=" + fmt(changePct) + "% volume=" + Math.round(volume) + " price=" + fmt(price),
                "",
                System.currentTimeMillis(),
                0.62,
                priority,
                metadata
        ));
        return 1;
    }

    private int pollOverview(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) throws Exception {
        JsonNode root = getJson(url("OVERVIEW", "symbol=" + enc(symbol)));
        if (isRateLimited(root)) return 0;
        String name = root.path("Name").asText("");
        String sector = root.path("Sector").asText("");
        String industry = root.path("Industry").asText("");
        double marketCap = parseDouble(root.path("MarketCapitalization").asText("0"));
        double pe = parseDouble(root.path("PERatio").asText("0"));
        double beta = parseDouble(root.path("Beta").asText("0"));
        double profitMargin = parseDouble(root.path("ProfitMargin").asText("0"));
        if (name.isBlank() && sector.isBlank() && marketCap <= 0.0) return 0;
        double quality = 0.35;
        if (marketCap > 0) quality += 0.15;
        if (profitMargin > 0) quality += 0.15;
        if (beta > 1.5) quality += 0.10;
        if (!sector.isBlank()) quality += 0.10;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("provider", name());
        metadata.put("rawMode", "ALPHA_VANTAGE_OVERVIEW");
        metadata.put("companyName", name);
        metadata.put("sector", sector);
        metadata.put("industry", industry);
        metadata.put("marketCap", fmt(marketCap));
        metadata.put("peRatio", fmt(pe));
        metadata.put("beta", fmt(beta));
        metadata.put("profitMargin", fmt(profitMargin));
        signalConsumer.accept(new MarketIntelligenceSignal(
                name(),
                MarketIntelligenceSignalType.FUNDAMENTAL,
                symbol,
                "Alpha Vantage fundamentals: " + symbol + " sector=" + emptyToUnknown(sector) + " marketCap=" + Math.round(marketCap) + " beta=" + fmt(beta),
                root.path("Description").asText(""),
                System.currentTimeMillis(),
                0.58,
                clamp(quality),
                metadata
        ));
        return 1;
    }

    private int pollRsi(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) throws Exception {
        String interval = env("ALPHA_VANTAGE_RSI_INTERVAL", "5min");
        String timePeriod = env("ALPHA_VANTAGE_RSI_TIME_PERIOD", "14");
        JsonNode root = getJson(url("RSI", "symbol=" + enc(symbol) + "&interval=" + enc(interval) + "&time_period=" + enc(timePeriod) + "&series_type=close"));
        if (isRateLimited(root)) return 0;
        JsonNode series = root.path("Technical Analysis: RSI");
        if (!series.isObject()) return 0;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = series.fields();
        if (!fields.hasNext()) return 0;
        Map.Entry<String, JsonNode> latest = fields.next();
        double rsi = parseDouble(latest.getValue().path("RSI").asText("0"));
        if (rsi <= 0.0) return 0;
        double priority = rsi >= 70.0 || rsi <= 30.0 ? 0.70 : 0.40;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("provider", name());
        metadata.put("rawMode", "ALPHA_VANTAGE_RSI");
        metadata.put("interval", interval);
        metadata.put("timePeriod", timePeriod);
        metadata.put("rsi", fmt(rsi));
        metadata.put("barTime", latest.getKey());
        signalConsumer.accept(new MarketIntelligenceSignal(
                name(),
                MarketIntelligenceSignalType.TECHNICAL_INDICATOR,
                symbol,
                "Alpha Vantage RSI: " + symbol + " rsi=" + fmt(rsi) + " interval=" + interval,
                "",
                System.currentTimeMillis(),
                0.55,
                priority,
                metadata
        ));
        return 1;
    }

    private int pollNews(Consumer<MarketIntelligenceSignal> signalConsumer) throws Exception {
        String joined = String.join(",", symbols.subList(0, Math.min(symbols.size(), envInt("ALPHA_VANTAGE_NEWS_MAX_TICKERS", 10))));
        JsonNode root = getJson(url("NEWS_SENTIMENT", "tickers=" + enc(joined) + "&sort=LATEST&limit=" + envInt("ALPHA_VANTAGE_NEWS_LIMIT", 20)));
        if (isRateLimited(root)) return 0;
        JsonNode feed = root.path("feed");
        if (!feed.isArray()) return 0;
        int emitted = 0;
        for (JsonNode item : feed) {
            String title = item.path("title").asText("");
            String summary = item.path("summary").asText("");
            long publishedMs = parseAlphaTime(item.path("time_published").asText(""));
            JsonNode tickerSentiment = item.path("ticker_sentiment");
            if (!tickerSentiment.isArray()) continue;
            for (JsonNode ts : tickerSentiment) {
                String ticker = ts.path("ticker").asText("").trim().toUpperCase(Locale.ROOT);
                if (!isUsEquityTicker(ticker)) continue;
                double relevance = parseDouble(ts.path("relevance_score").asText("0"));
                double sentiment = parseDouble(ts.path("ticker_sentiment_score").asText("0"));
                if (relevance < envDouble("ALPHA_VANTAGE_MIN_NEWS_RELEVANCE", 0.10)) continue;
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("provider", name());
                metadata.put("rawMode", "ALPHA_VANTAGE_NEWS_SENTIMENT");
                metadata.put("source", item.path("source").asText(""));
                metadata.put("url", item.path("url").asText(""));
                metadata.put("overallSentimentScore", item.path("overall_sentiment_score").asText(""));
                metadata.put("overallSentimentLabel", item.path("overall_sentiment_label").asText(""));
                metadata.put("tickerRelevance", fmt(relevance));
                metadata.put("tickerSentiment", fmt(sentiment));
                metadata.put("routeToNews", "true");
                double priority = clamp(0.35 + relevance * 0.35 + Math.abs(sentiment) * 0.30 + headlinePriority(title + " " + summary) * 0.20);
                signalConsumer.accept(new MarketIntelligenceSignal(
                        name(),
                        MarketIntelligenceSignalType.NEWS,
                        ticker,
                        title.isBlank() ? "Alpha Vantage news sentiment" : title,
                        summary,
                        publishedMs,
                        0.66,
                        priority,
                        metadata
                ));
                emitted++;
                if (emitted >= envInt("ALPHA_VANTAGE_MAX_NEWS_SIGNALS", 25)) return emitted;
            }
        }
        return emitted;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .GET()
                .header("accept", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            MarketIntelligenceBus.getInstance().recordProviderStatus(name(), "HTTP_" + code);
            throw new IllegalStateException("Alpha Vantage HTTP " + code);
        }
        return mapper.readTree(response.body() == null ? "{}" : response.body());
    }

    private boolean isRateLimited(JsonNode root) {
        if (root == null) return false;
        String note = root.path("Note").asText("");
        String info = root.path("Information").asText("");
        String error = root.path("Error Message").asText("");
        if (!note.isBlank() || !info.isBlank() || !error.isBlank()) {
            String status = !error.isBlank() ? "ERROR:" + safe(error) : "RATE_LIMIT_OR_INFO:" + safe(note.isBlank() ? info : note);
            MarketIntelligenceBus.getInstance().recordProviderStatus(name(), status);
            return true;
        }
        return false;
    }

    private String url(String function, String query) {
        String q = "function=" + enc(function) + (query == null || query.isBlank() ? "" : "&" + query) + "&apikey=" + enc(apiKey);
        return BASE_URL + "?" + q;
    }

    private static List<String> parseSymbols(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String s = part.trim().toUpperCase(Locale.ROOT);
            if (isUsEquityTicker(s) && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static boolean isUsEquityTicker(String ticker) {
        if (ticker == null) return false;
        String t = ticker.trim().toUpperCase(Locale.ROOT);
        if (t.isBlank() || t.length() > 8) return false;
        if (t.endsWith("USD") || t.contains("/") || t.contains(":")) return false;
        return t.matches("[A-Z][A-Z0-9.\\-]{0,7}");
    }

    private static long parseAlphaTime(String value) {
        try {
            if (value == null || value.isBlank()) return System.currentTimeMillis();
            String v = value.trim();
            if (v.matches("\\d{8}T\\d{6}")) {
                String iso = v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8) + "T" + v.substring(9, 11) + ":" + v.substring(11, 13) + ":" + v.substring(13, 15) + "Z";
                return Instant.parse(iso).toEpochMilli();
            }
            return Instant.parse(v).toEpochMilli();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    private static double headlinePriority(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
        double p = 0.25;
        if (s.contains("fda") || s.contains("approval") || s.contains("clearance")) p += 0.30;
        if (s.contains("guidance") || s.contains("raises") || s.contains("beats")) p += 0.20;
        if (s.contains("contract") || s.contains("award") || s.contains("merger") || s.contains("acquire")) p += 0.20;
        if (s.contains("offering") || s.contains("dilution") || s.contains("bankruptcy")) p += 0.25;
        return clamp(p);
    }

    private static double parsePercent(String value) {
        if (value == null) return 0.0;
        return parseDouble(value.replace("%", "").trim());
    }

    private static double parseDouble(String value) {
        try {
            if (value == null || value.isBlank() || "None".equalsIgnoreCase(value) || "-".equals(value)) return 0.0;
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static String emptyToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
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

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
}
