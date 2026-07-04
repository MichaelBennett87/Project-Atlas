package com.bot.stream;

import com.bot.master.CatalystQualityGate;
import com.bot.model.NewsEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BenzingaInstitutionalPressReleaseRestPollingStream {

    private static final Pattern QUOTE_LINK_PATTERN =
            Pattern.compile("(?i)(?:benzinga\\.com/quote/|/quote/)([A-Z]{1,5})(?:[\\\"?&/#<\\s]|$)");

    private static final Pattern EXCHANGE_SYMBOL_PATTERN =
            Pattern.compile("(?i)\\b(?:NASDAQ|NYSE|AMEX|ARCA|NYSE\\s+AMERICAN|OTC|OTCQB|OTCQX)\\s*[:：]\\s*([A-Z]{1,5})\\b");

    private static final Pattern PAREN_EXCHANGE_SYMBOL_PATTERN =
            Pattern.compile("(?i)\\((?:NASDAQ|NYSE|AMEX|ARCA|NYSE\\s+AMERICAN|OTC|OTCQB|OTCQX)\\s*[:：]\\s*([A-Z]{1,5})\\)");

    private static final Pattern BROAD_EXCHANGE_SYMBOL_PATTERN =
            Pattern.compile("(?i)\\b(?:NASDAQ|NYSE|AMEX|ARCA|NYSE\\s+AMERICAN|OTC|OTCQB|OTCQX|OTCBB|TSX|TSXV|TSX\\s+VENTURE|CSE)\\s*[:：]\\s*([A-Z]{1,6})(?:\\.|\\)|,|;|\\s|<|$)");

    private static final Pattern HTML_DATA_TICKER_PATTERN =
            Pattern.compile("(?i)data-ticker=[\\\"\']([A-Z]{1,6})[\\\"\']");

    private static final Pattern OTC_MARKETS_LINK_PATTERN =
            Pattern.compile("(?i)otcmarkets\\.com/stock/([A-Z]{1,6})(?:/|\\?|\\\"|\'|<|\\s|$)");

    private static final String DEFAULT_REST_URL =
            "https://api.benzinga.com/api/v2/news";

    private static final int DEFAULT_PAGE_SIZE =
            50;

    private static final long DEFAULT_POLL_SECONDS =
            3L;

    private static final long DEFAULT_STARTUP_LOOKBACK_SECONDS =
            180L;

    private static final long DEFAULT_UPDATED_SINCE_OVERLAP_SECONDS =
            0L;

    private final String token;
    private final String tickers;
    private final String channels;
    private final Consumer<NewsEvent> newsHandler;
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final Set<String> seenArticleTickerIds;
    private final Set<String> seenArticleIds;

    private volatile boolean running = false;
    private volatile long pollCount = 0L;
    private volatile long rawArticleCount = 0L;
    private volatile long acceptedArticleCount = 0L;
    private volatile long rejectedArticleCount = 0L;
    private volatile long skippedSymbolCount = 0L;
    private volatile long duplicateArticleTickerCount = 0L;
    private volatile long duplicateArticleCount = 0L;
    private volatile long noSymbolRejectedArticleCount = 0L;
    private volatile long consecutiveFailureCount = 0L;
    private volatile long nextUpdatedSinceEpochSeconds;

    public BenzingaInstitutionalPressReleaseRestPollingStream(
            Consumer<NewsEvent> newsHandler
    ) {
        this(
                firstNonBlank(
                        System.getenv("BENZINGA_PRESS_RELEASE_TOKEN"),
                        System.getenv("BENZINGA_INSTITUTIONAL_TOKEN"),
                        System.getenv("BENZINGA_INSTITUTIONAL_PRESS_RELEASE_TOKEN")
                ),
                System.getenv("BENZINGA_PRESS_RELEASE_TICKERS"),
                System.getenv().getOrDefault("BENZINGA_PRESS_RELEASE_CHANNELS", "Press Releases"),
                newsHandler
        );
    }

    public BenzingaInstitutionalPressReleaseRestPollingStream(
            String token,
            String tickers,
            String channels,
            Consumer<NewsEvent> newsHandler
    ) {
        this.token = token;
        this.tickers = tickers;
        this.channels = channels;
        this.newsHandler = newsHandler;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(longEnv("BENZINGA_PR_REST_CONNECT_TIMEOUT_SECONDS", 10L), TimeUnit.SECONDS)
                .readTimeout(longEnv("BENZINGA_PR_REST_READ_TIMEOUT_SECONDS", 20L), TimeUnit.SECONDS)
                .writeTimeout(longEnv("BENZINGA_PR_REST_WRITE_TIMEOUT_SECONDS", 10L), TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
        this.seenArticleTickerIds = new LinkedHashSet<>();
        this.seenArticleIds = new LinkedHashSet<>();
        this.nextUpdatedSinceEpochSeconds = initialUpdatedSinceEpochSeconds();

        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing BENZINGA_PRESS_RELEASE_TOKEN environment variable.");
        }
    }

    public void start() {
        running = true;

        Thread pollingThread = new Thread(this::pollLoop);
        pollingThread.setName("benzinga-institutional-press-release-rest-polling-stream");
        pollingThread.setDaemon(true);
        pollingThread.start();

        System.out.println(
                "Benzinga institutional press-release REST polling started. pollSeconds=" +
                        pollSeconds() +
                        " pageSize=" +
                        pageSize() +
                        " startupLookbackSeconds=" +
                        startupLookbackSeconds() +
                        " updatedSince=" +
                        nextUpdatedSinceEpochSeconds +
                        " channels=" +
                        channels +
                        " rawResponseLogging=" +
                        logRawRestResponses()
        );
    }

    public void stop() {
        running = false;
    }

    private void pollLoop() {
        while (running) {
            try {
                pollOnce();
                Thread.sleep(pollSeconds() * 1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception e) {
                consecutiveFailureCount++;
                long backoffSeconds = restFailureBackoffSeconds();

                System.err.println(
                        "BENZINGA PR REST POLL ERROR: " +
                                e.getMessage() +
                                " consecutiveFailures=" +
                                consecutiveFailureCount +
                                " backoffSeconds=" +
                                backoffSeconds
                );

                sleepBackoff(backoffSeconds);
            }
        }
    }

    private void pollOnce() throws Exception {
        pollCount++;

        long requestUpdatedSince = nextUpdatedSinceEpochSeconds;
        PollStats stats = new PollStats();
        String url = buildRestUrl(requestUpdatedSince);

        Request request =
                new Request.Builder()
                        .url(url)
                        .addHeader("accept", "application/json")
                        .build();

        if (logRestRequestUrl()) {
            System.out.println("BENZINGA PR REST REQUEST URL: " + maskToken(url));
        }

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();

            if (logRawRestResponses()) {
                System.out.println(
                        "BENZINGA PR REST RAW RESPONSE: code=" +
                                response.code() +
                                " body=" +
                                truncate(body, intEnv("BENZINGA_PR_REST_RAW_LOG_MAX_CHARS", 2_000))
                );
            }

            if (!response.isSuccessful()) {
                consecutiveFailureCount++;
                long backoffSeconds = restFailureBackoffSeconds(response.code());

                System.err.println(
                        "BENZINGA PR REST POLL FAILED: code=" +
                                response.code() +
                                " message=" +
                                response.message() +
                                " body=" +
                                truncate(body, 1_000) +
                                " consecutiveFailures=" +
                                consecutiveFailureCount +
                                " backoffSeconds=" +
                                backoffSeconds
                );

                sleepBackoff(backoffSeconds);
                return;
            }

            if (body.isBlank()) {
                consecutiveFailureCount = 0L;
                advanceUpdatedSince(0L, requestUpdatedSince, 0);
                printPollComplete(requestUpdatedSince, 0, stats);
                return;
            }

            JsonNode root = mapper.readTree(body);
            int itemsSeen = handleRestRoot(root, stats);

            consecutiveFailureCount = 0L;
            advanceUpdatedSince(stats.maxProviderTimestampMillis, requestUpdatedSince, itemsSeen);
            printPollComplete(requestUpdatedSince, itemsSeen, stats);
        }
    }

    private String buildRestUrl(
            long updatedSinceEpochSeconds
    ) {
        HttpUrl baseUrl =
                HttpUrl.parse(
                        System.getenv().getOrDefault(
                                "BENZINGA_PRESS_RELEASE_REST_URL",
                                DEFAULT_REST_URL
                        )
                );

        if (baseUrl == null) {
            throw new IllegalStateException("Invalid BENZINGA_PRESS_RELEASE_REST_URL.");
        }

        HttpUrl.Builder builder =
                baseUrl.newBuilder()
                        .addQueryParameter("token", token)
                        .addQueryParameter("pageSize", String.valueOf(pageSize()))
                        .addQueryParameter("displayOutput", "full")
                        .addQueryParameter("updatedSince", String.valueOf(updatedSinceEpochSeconds));

        String sort = System.getenv().getOrDefault("BENZINGA_PRESS_RELEASE_REST_SORT", "updated:desc");

        if (sort != null && !sort.isBlank()) {
            builder.addQueryParameter("sort", sort.trim());
        }

        if (channels != null && !channels.isBlank()) {
            builder.addQueryParameter("channels", channels.trim());
        }

        if (tickers != null && !tickers.isBlank()) {
            builder.addQueryParameter("tickers", tickers.trim());
        }

        return builder.build().toString();
    }

    private int handleRestRoot(
            JsonNode root,
            PollStats stats
    ) {
        if (root == null || root.isNull()) {
            return 0;
        }

        if (root.isObject() && hasApiError(root)) {
            System.err.println("BENZINGA PR REST API ERROR OBJECT: " + truncate(root.toString(), 1_000));
            return 0;
        }

        int count = 0;

        if (root.isArray()) {
            for (JsonNode item : root) {
                count++;
                handleArticleNode(normalizeRestArticle(item), stats);
            }
            return count;
        }

        JsonNode data =
                firstExistingArray(
                        root.path("data"),
                        root.path("news"),
                        root.path("results"),
                        root.path("items")
                );

        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                count++;
                handleArticleNode(normalizeRestArticle(item), stats);
            }
            return count;
        }

        handleArticleNode(normalizeRestArticle(root), stats);
        return 1;
    }

    private JsonNode normalizeRestArticle(
            JsonNode item
    ) {
        if (item == null || item.isNull() || item.isMissingNode()) {
            return item;
        }

        if (item.has("content") && item.path("content").isObject()) {
            return item.path("content");
        }

        return item;
    }

    private void handleArticleNode(
            JsonNode article,
            PollStats stats
    ) {
        if (article == null || article.isMissingNode() || article.isNull()) {
            stats.rejectedArticles++;
            rejectedArticleCount++;
            return;
        }

        rawArticleCount++;

        long providerTimestampMillis = articleTimestampMillis(article);

        if (providerTimestampMillis > 0) {
            stats.maxProviderTimestampMillis = Math.max(stats.maxProviderTimestampMillis, providerTimestampMillis);
        }

        String articleId =
                firstNonBlank(
                        article.path("id").asText(),
                        article.path("revision_id").asText(),
                        article.path("original_id").asText(),
                        article.path("url").asText(),
                        article.path("title").asText()
                );

        if (shouldRejectForPressReleaseFeed(article)) {
            stats.rejectedArticles++;
            rejectedArticleCount++;
            System.out.println(
                    "BENZINGA PR REST NEWS REJECTED: FEED_HYGIENE title=" +
                            htmlDecode(article.path("title").asText(""))
            );
            return;
        }

        if (!articleId.isBlank() && seenArticleIds.contains(articleId)) {
            stats.duplicateArticles++;
            duplicateArticleCount++;
            return;
        }

        String title = htmlDecode(article.path("title").asText(""));
        String body = htmlDecode(article.path("body").asText(""));

        String articleWideRejectReason = articleWideRejectReason(title, body);
        if (articleWideRejectReason != null) {
            if (!articleId.isBlank()) {
                seenArticleIds.add(articleId);
                trimSeenArticleIds();
            }
            stats.rejectedArticles++;
            rejectedArticleCount++;
            System.out.println(
                    "BENZINGA PR REST NEWS REJECTED BEFORE_SYMBOL_EXPANSION: " +
                            articleWideRejectReason +
                            " title=" +
                            title
            );
            return;
        }

        Set<String> symbols = supportedSymbols(article);

        if (symbols.isEmpty()) {
            if (!articleId.isBlank()) {
                seenArticleIds.add(articleId);
                trimSeenArticleIds();
            }
            stats.rejectedArticles++;
            rejectedArticleCount++;
            noSymbolRejectedArticleCount++;
            System.out.println(
                    "BENZINGA PR REST NEWS REJECTED: NO_SYMBOLS title=" +
                            htmlDecode(article.path("title").asText("")) +
                            " extractedFromBody=false"
            );
            return;
        }

        if (!articleId.isBlank()) {
            seenArticleIds.add(articleId);
            trimSeenArticleIds();
        }

        stats.acceptedArticles++;
        acceptedArticleCount++;

        long timestamp = providerTimestampMillis > 0 ? providerTimestampMillis : System.currentTimeMillis();

        System.out.println(
                "BENZINGA PR REST NEWS ACCEPTED: title=" +
                        title +
                        " symbols=" +
                        symbols +
                        " providerAgeMs=" +
                        Math.max(0L, System.currentTimeMillis() - timestamp) +
                        " articleId=" +
                        articleId +
                        " cursor=" +
                        nextUpdatedSinceEpochSeconds
        );

        int sent = 0;
        int maxSymbols = maxSymbolsPerArticle();

        for (String symbol : symbols) {
            if (sent >= maxSymbols) {
                skippedSymbolCount++;
                System.out.println(
                        "BENZINGA PR REST SYMBOL SKIPPED: " +
                                symbol +
                                " reason=MAX_SYMBOLS_PER_ARTICLE_REACHED max=" +
                                maxSymbols
                );
                continue;
            }

            String dedupeKey = articleId + ":" + symbol;

            if (seenArticleTickerIds.contains(dedupeKey)) {
                duplicateArticleTickerCount++;
                stats.duplicateArticleTickers++;
                continue;
            }

            seenArticleTickerIds.add(dedupeKey);
            trimSeenArticleTickerIds();

            NewsEvent news =
                    new NewsEvent(
                            "BZPRREST:" + articleId + ":" + symbol,
                            symbol,
                            title,
                            body,
                            timestamp
                    );

            news.setSource("BENZINGA_PRESS_RELEASE_REST");
            news.setProviderTimestamp(timestamp);
            news.setBotFirstSeenAt(System.currentTimeMillis());
            news.setSourceLagMs(0L);

            System.out.println("BENZINGA PR REST LIVE NEWS RECEIVED: " + symbol + " | " + title);

            newsHandler.accept(news);
            sent++;
            stats.symbolsSent++;
        }
    }

    private String articleWideRejectReason(String title, String body) {
        NewsEvent article = new NewsEvent(
                "BENZINGA_PR_REST_ARTICLE_WIDE",
                "",
                title == null ? "" : title,
                body == null ? "" : body,
                System.currentTimeMillis()
        );

        String fastRejectReason = NewsPriorityGate.preFreshnessRejectReason(article);
        if (fastRejectReason != null) {
            return fastRejectReason;
        }

        String preNlpReason = CatalystQualityGate.preNlpRejectReason(article);
        if (preNlpReason != null) {
            return preNlpReason;
        }

        return CatalystQualityGate.rejectReason(article);
    }

    private boolean shouldRejectForPressReleaseFeed(
            JsonNode article
    ) {
        String type = safe(article.path("type").asText()).toLowerCase(Locale.ROOT);
        String title = safe(article.path("title").asText()).toLowerCase(Locale.ROOT);
        String body = safe(article.path("body").asText()).toLowerCase(Locale.ROOT);
        String combined = title + " " + body;

        if (type.startsWith("benzinga_wire_") || containsNonLatinScript(title)) {
            return true;
        }

        return containsAny(
                combined,
                "generated by benzinga's automated content engine",
                "generated by benzinga’s automated content engine",
                "this article was generated by benzinga",
                "automated content engine",
                "stocks moving in",
                "intraday session",
                "whale alert",
                "options activity",
                "unusual options",
                "market size",
                "market share",
                "market report",
                "market forecast",
                "industry analysis",
                "cagr",
                "swot analysis",
                "segmentation",
                "white paper",
                "survey report",
                "research report",
                "custom market insights",
                "according to a new report",
                "report ocean",
                "marketsandmarkets",
                "grand view research",
                "allied market research",
                "data bridge market research",
                "the business research company"
        );
    }

    private Set<String> supportedSymbols(
            JsonNode article
    ) {
        Set<String> symbols = new LinkedHashSet<>();

        addSymbolsFromArray(symbols, article.path("securities"), "symbol");
        addSymbolsFromArray(symbols, article.path("stocks"), "name");
        addSymbolsFromArray(symbols, article.path("stocks"), "symbol");

        addSymbolsFromQuoteLinks(symbols, article.path("body").asText(""));
        addSymbolsFromQuoteLinks(symbols, article.path("teaser").asText(""));
        addSymbolsFromExchangePatterns(symbols, article.path("title").asText(""));
        addSymbolsFromExchangePatterns(symbols, article.path("body").asText(""));
        addSymbolsFromExchangePatterns(symbols, article.path("teaser").asText(""));
        addSymbolsFromParenthesizedExchangePatterns(symbols, article.path("title").asText(""));
        addSymbolsFromParenthesizedExchangePatterns(symbols, article.path("body").asText(""));
        addSymbolsFromParenthesizedExchangePatterns(symbols, article.path("teaser").asText(""));

        return symbols;
    }

    private void addSymbolsFromQuoteLinks(
            Set<String> symbols,
            String text
    ) {
        if (text == null || text.isBlank()) {
            return;
        }

        Matcher matcher = QUOTE_LINK_PATTERN.matcher(text);

        while (matcher.find()) {
            String symbol = safe(matcher.group(1)).trim().toUpperCase(Locale.ROOT);

            if (isSupportedStockSymbol(symbol, "")) {
                symbols.add(symbol);
            }
        }
    }

    private void addSymbolsFromExchangePatterns(
            Set<String> symbols,
            String text
    ) {
        if (text == null || text.isBlank()) {
            return;
        }

        Matcher matcher = EXCHANGE_SYMBOL_PATTERN.matcher(text);

        while (matcher.find()) {
            String symbol = safe(matcher.group(1)).trim().toUpperCase(Locale.ROOT);

            if (isSupportedStockSymbol(symbol, "")) {
                symbols.add(symbol);
            }
        }
    }

    private void addSymbolsFromParenthesizedExchangePatterns(
            Set<String> symbols,
            String text
    ) {
        if (text == null || text.isBlank()) {
            return;
        }

        Matcher matcher = PAREN_EXCHANGE_SYMBOL_PATTERN.matcher(text);

        while (matcher.find()) {
            String symbol = safe(matcher.group(1)).trim().toUpperCase(Locale.ROOT);

            if (isSupportedStockSymbol(symbol, "")) {
                symbols.add(symbol);
            }
        }
    }

    private void addSymbolsFromBroadExchangePatterns(
            Set<String> symbols,
            String text
    ) {
        if (text == null || text.isBlank()) {
            return;
        }

        Matcher matcher = BROAD_EXCHANGE_SYMBOL_PATTERN.matcher(text);

        while (matcher.find()) {
            String symbol = safe(matcher.group(1)).trim().toUpperCase(Locale.ROOT);

            if (isSupportedStockSymbol(symbol, "")) {
                symbols.add(symbol);
            }
        }
    }

    private void addSymbolsFromHtmlDataTickers(
            Set<String> symbols,
            String text
    ) {
        if (text == null || text.isBlank()) {
            return;
        }

        Matcher matcher = HTML_DATA_TICKER_PATTERN.matcher(text);

        while (matcher.find()) {
            String symbol = safe(matcher.group(1)).trim().toUpperCase(Locale.ROOT);

            if (isSupportedStockSymbol(symbol, "")) {
                symbols.add(symbol);
            }
        }
    }

    private void addSymbolsFromOtcMarketsLinks(
            Set<String> symbols,
            String text
    ) {
        if (text == null || text.isBlank()) {
            return;
        }

        Matcher matcher = OTC_MARKETS_LINK_PATTERN.matcher(text);

        while (matcher.find()) {
            String symbol = safe(matcher.group(1)).trim().toUpperCase(Locale.ROOT);

            if (isSupportedStockSymbol(symbol, "")) {
                symbols.add(symbol);
            }
        }
    }

    private void addSymbolsFromArray(
            Set<String> symbols,
            JsonNode array,
            String symbolField
    ) {
        if (array == null || !array.isArray()) {
            return;
        }

        for (JsonNode security : array) {
            String symbol = safe(security.path(symbolField).asText()).trim().toUpperCase(Locale.ROOT);
            String exchange = safe(security.path("exchange").asText()).trim().toUpperCase(Locale.ROOT);

            if (isSupportedStockSymbol(symbol, exchange)) {
                symbols.add(symbol);
            }
        }
    }

    private boolean isSupportedStockSymbol(
            String symbol,
            String exchange
    ) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }

        if (symbol.contains("/") || symbol.contains(":") || symbol.contains(".") || symbol.contains("-")) {
            return false;
        }

        if (symbol.length() < 1 || symbol.length() > 5) {
            return false;
        }

        for (int i = 0; i < symbol.length(); i++) {
            if (!Character.isLetter(symbol.charAt(i))) {
                return false;
            }
        }

        return exchange.isBlank() ||
                exchange.equals("NASDAQ") ||
                exchange.equals("NYSE") ||
                exchange.equals("AMEX") ||
                exchange.equals("ARCA") ||
                exchange.equals("OTC") ||
                exchange.equals("OTCQB") ||
                exchange.equals("OTCQX") ||
                exchange.equals("NYSE AMERICAN") ||
                exchange.equals("NYSEAMERICAN");
    }

    private long articleTimestampMillis(
            JsonNode article
    ) {
        String timestamp =
                firstNonBlank(
                        article.path("created_at").asText(),
                        article.path("updated_at").asText(),
                        article.path("created").asText(),
                        article.path("updated").asText(),
                        article.path("timestamp").asText(),
                        article.path("date").asText()
                );

        long parsed = parseTimestampMillis(timestamp);

        if (parsed > 0) {
            return parsed;
        }

        long unix = firstPositiveLong(
                article.path("created").asLong(0L),
                article.path("updated").asLong(0L),
                article.path("created_at").asLong(0L),
                article.path("updated_at").asLong(0L),
                article.path("timestamp").asLong(0L)
        );

        if (unix > 0) {
            return unix < 10_000_000_000L ? unix * 1_000L : unix;
        }

        return 0L;
    }

    private long parseTimestampMillis(
            String timestamp
    ) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }

        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception ignored) {
            // Try common formats below.
        }

        try {
            return ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // Try RFC 1123 format below.
        }

        try {
            return ZonedDateTime.parse(timestamp, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void advanceUpdatedSince(
            long maxProviderTimestampMillis,
            long requestUpdatedSince,
            int itemsSeen
    ) {
        long candidate;

        long overlapSeconds = updatedSinceOverlapSeconds();

        if (maxProviderTimestampMillis > 0) {
            long providerSecond = maxProviderTimestampMillis / 1_000L;
            candidate = Math.max(0L, providerSecond + 1L - overlapSeconds);
        } else {
            candidate = Math.max(0L, Instant.now().getEpochSecond() - overlapSeconds);
        }

        if (itemsSeen > 0 && candidate <= requestUpdatedSince) {
            candidate = requestUpdatedSince + 1L;
        }

        if (candidate > nextUpdatedSinceEpochSeconds) {
            nextUpdatedSinceEpochSeconds = candidate;
        }
    }

    private long initialUpdatedSinceEpochSeconds() {
        return Math.max(0L, Instant.now().getEpochSecond() - startupLookbackSeconds());
    }

    private void printPollComplete(
            long requestUpdatedSince,
            int itemsSeen,
            PollStats stats
    ) {
        if (itemsSeen > 0 || pollCount % 12 == 0) {
            System.out.println(
                    "BENZINGA PR REST POLL COMPLETE: poll=" +
                            pollCount +
                            " itemsSeen=" +
                            itemsSeen +
                            " pollAccepted=" +
                            stats.acceptedArticles +
                            " pollRejected=" +
                            stats.rejectedArticles +
                            " pollSymbolsSent=" +
                            stats.symbolsSent +
                            " updatedSince=" +
                            requestUpdatedSince +
                            " nextUpdatedSince=" +
                            nextUpdatedSinceEpochSeconds +
                            " rawArticles=" +
                            rawArticleCount +
                            " acceptedArticles=" +
                            acceptedArticleCount +
                            " rejectedArticles=" +
                            rejectedArticleCount +
                            " skippedSymbols=" +
                            skippedSymbolCount +
                            " duplicates=" +
                            duplicateArticleTickerCount +
                            " pollDuplicateArticles=" +
                            stats.duplicateArticles +
                            " duplicateArticles=" +
                            duplicateArticleCount +
                            " noSymbolRejected=" +
                            noSymbolRejectedArticleCount +
                            " consecutiveFailures=" +
                            consecutiveFailureCount +
                            " channels=" +
                            channels
            );
        }
    }

    private long pollSeconds() {
        return longEnv("BENZINGA_PRESS_RELEASE_REST_POLL_SECONDS", DEFAULT_POLL_SECONDS);
    }

    private long restFailureBackoffSeconds() {
        return restFailureBackoffSeconds(0);
    }

    private long restFailureBackoffSeconds(
            int statusCode
    ) {
        long base = statusCode == 429
                ? longEnv("BENZINGA_PR_REST_429_BACKOFF_BASE_SECONDS", 60L)
                : longEnv("BENZINGA_PR_REST_FAILURE_BACKOFF_BASE_SECONDS", 15L);

        long max = longEnv("BENZINGA_PR_REST_FAILURE_BACKOFF_MAX_SECONDS", 300L);
        long multiplier = Math.max(1L, consecutiveFailureCount);
        long candidate = base * multiplier;
        return Math.max(pollSeconds(), Math.min(max, candidate));
    }

    private int pageSize() {
        return intEnv("BENZINGA_PRESS_RELEASE_REST_PAGE_SIZE", DEFAULT_PAGE_SIZE);
    }

    private long startupLookbackSeconds() {
        return longEnv("BENZINGA_PRESS_RELEASE_REST_STARTUP_LOOKBACK_SECONDS", DEFAULT_STARTUP_LOOKBACK_SECONDS);
    }

    private long updatedSinceOverlapSeconds() {
        return longEnv("BENZINGA_PR_REST_UPDATED_SINCE_OVERLAP_SECONDS", DEFAULT_UPDATED_SINCE_OVERLAP_SECONDS);
    }

    private int maxSymbolsPerArticle() {
        return intEnv("BENZINGA_PRESS_RELEASE_REST_MAX_SYMBOLS_PER_ARTICLE", 6);
    }

    private boolean logRawRestResponses() {
        return "true".equalsIgnoreCase(System.getenv().getOrDefault("BENZINGA_PRESS_RELEASE_REST_RAW_RESPONSE_LOGGING", "false"));
    }

    private boolean logRestRequestUrl() {
        return "true".equalsIgnoreCase(System.getenv().getOrDefault("BENZINGA_PRESS_RELEASE_REST_LOG_REQUEST_URL", "false"));
    }

    private void trimSeenArticleIds() {
        trimSet(seenArticleIds, intEnv("BENZINGA_PR_REST_DEDUPE_MAX_ARTICLES", 2_000));
    }

    private void trimSeenArticleTickerIds() {
        trimSet(seenArticleTickerIds, intEnv("BENZINGA_PR_REST_DEDUPE_MAX_TICKER_ARTICLES", 5_000));
    }

    private void trimSet(
            Set<String> set,
            int maxSize
    ) {
        while (set.size() > maxSize && !set.isEmpty()) {
            set.remove(set.iterator().next());
        }
    }

    private boolean hasApiError(
            JsonNode root
    ) {
        return root.has("error") ||
                root.has("errors") ||
                root.has("message") && !root.has("data") && !root.has("news") && !root.has("results") && !root.has("items");
    }

    private JsonNode firstExistingArray(
            JsonNode... nodes
    ) {
        if (nodes == null) {
            return null;
        }

        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }

        return null;
    }

    private boolean containsNonLatinScript(
            String text
    ) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));

            if (block == Character.UnicodeBlock.ARABIC ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                    block == Character.UnicodeBlock.HANGUL_JAMO ||
                    block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA) {
                return true;
            }
        }

        return false;
    }

    private boolean containsAny(
            String text,
            String... phrases
    ) {
        if (text == null || phrases == null) {
            return false;
        }

        String normalized = text.toLowerCase(Locale.ROOT);

        for (String phrase : phrases) {
            if (phrase != null && !phrase.isBlank() && normalized.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private long firstPositiveLong(
            long... values
    ) {
        for (long value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0L;
    }

    private String htmlDecode(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return value
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&mdash;", "—");
    }

    private String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String maskToken(
            String url
    ) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("token=[^&]+", "token=***");
    }

    private void sleepBackoff(
            long backoffSeconds
    ) {
        try {
            Thread.sleep(backoffSeconds * 1_000L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private String safe(
            String value
    ) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(
            String... values
    ) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return "";
    }

    private static int intEnv(
            String key,
            int defaultValue
    ) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static long longEnv(
            String key,
            long defaultValue
    ) {
        try {
            return Long.parseLong(System.getenv().getOrDefault(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static class PollStats {
        private int acceptedArticles;
        private int rejectedArticles;
        private int symbolsSent;
        private int duplicateArticles;
        private int duplicateArticleTickers;
        private long maxProviderTimestampMillis;
    }
}
