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

public class BenzingaNewsRestPollingStream {

    private static final String DEFAULT_REST_URL =
            "https://api.benzinga.com/api/v2/news";

    private static final int DEFAULT_PAGE_SIZE =
            50;

    private static final long DEFAULT_POLL_SECONDS =
            5L;

    private static final long DEFAULT_STARTUP_LOOKBACK_SECONDS =
            120L;

    private static final long UPDATED_SINCE_OVERLAP_SECONDS =
            3L;

    private final String token;
    private final String tickers;
    private final String channels;
    private final Consumer<NewsEvent> newsHandler;
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final BenzingaNewsQualityFilter qualityFilter;
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
    private volatile long consecutiveFailureCount = 0L;
    private volatile long nextUpdatedSinceEpochSeconds;

    public BenzingaNewsRestPollingStream(
            Consumer<NewsEvent> newsHandler
    ) {
        this(
                firstNonBlank(
                        System.getenv("BENZINGA_NEWS_TOKEN"),
                        System.getenv("BENZINGA_API_KEY")
                ),
                System.getenv("BENZINGA_NEWS_TICKERS"),
                System.getenv("BENZINGA_NEWS_CHANNELS"),
                newsHandler
        );
    }

    public BenzingaNewsRestPollingStream(
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
                .connectTimeout(longEnv("BENZINGA_REST_CONNECT_TIMEOUT_SECONDS", 10L), TimeUnit.SECONDS)
                .readTimeout(longEnv("BENZINGA_REST_READ_TIMEOUT_SECONDS", 20L), TimeUnit.SECONDS)
                .writeTimeout(longEnv("BENZINGA_REST_WRITE_TIMEOUT_SECONDS", 10L), TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
        this.qualityFilter = new BenzingaNewsQualityFilter();
        this.seenArticleTickerIds = new LinkedHashSet<>();
        this.seenArticleIds = new LinkedHashSet<>();
        this.nextUpdatedSinceEpochSeconds = initialUpdatedSinceEpochSeconds();

        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Missing BENZINGA_NEWS_TOKEN or BENZINGA_API_KEY environment variable."
            );
        }
    }

    public void start() {
        running = true;

        Thread pollingThread =
                new Thread(this::pollLoop);

        pollingThread.setName("benzinga-news-rest-polling-stream");
        pollingThread.setDaemon(true);
        pollingThread.start();

        System.out.println(
                "Benzinga REST polling backup started. pollSeconds=" +
                        pollSeconds() +
                        " pageSize=" +
                        pageSize() +
                        " startupLookbackSeconds=" +
                        startupLookbackSeconds() +
                        " updatedSince=" +
                        nextUpdatedSinceEpochSeconds +
                        " filterMode=" +
                        filterMode() +
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
                        "BENZINGA REST POLL ERROR: " +
                                e.getMessage() +
                                " consecutiveFailures=" +
                                consecutiveFailureCount +
                                " backoffSeconds=" +
                                backoffSeconds
                );

                try {
                    Thread.sleep(backoffSeconds * 1_000L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    private void pollOnce() throws Exception {
        pollCount++;

        long requestUpdatedSince =
                nextUpdatedSinceEpochSeconds;

        PollStats stats =
                new PollStats();

        String url =
                buildRestUrl(requestUpdatedSince);

        Request request =
                new Request.Builder()
                        .url(url)
                        .addHeader("accept", "application/json")
                        .build();

        if (logRestRequestUrl()) {
            System.out.println("BENZINGA REST REQUEST URL: " + maskToken(url));
        }

        try (Response response = client.newCall(request).execute()) {
            String body =
                    response.body() == null ? "" : response.body().string();

            if (logRawRestResponses()) {
                System.out.println(
                        "BENZINGA REST RAW RESPONSE: code=" +
                                response.code() +
                                " body=" +
                                truncate(body, intEnv("BENZINGA_REST_RAW_LOG_MAX_CHARS", 2_000))
                );
            }

            if (!response.isSuccessful()) {
                consecutiveFailureCount++;
                long backoffSeconds = restFailureBackoffSeconds(response.code());

                System.err.println(
                        "BENZINGA REST POLL FAILED: code=" +
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

                try {
                    Thread.sleep(backoffSeconds * 1_000L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
                return;
            }

            if (body.isBlank()) {
                consecutiveFailureCount = 0L;
                advanceUpdatedSince(0L);
                printPollComplete(requestUpdatedSince, 0, stats);
                return;
            }

            JsonNode root =
                    mapper.readTree(body);

            int itemsSeen =
                    handleRestRoot(root, stats);

            consecutiveFailureCount = 0L;
            advanceUpdatedSince(stats.maxProviderTimestampMillis);

            printPollComplete(requestUpdatedSince, itemsSeen, stats);
        }
    }

    private String buildRestUrl(
            long updatedSinceEpochSeconds
    ) {
        HttpUrl baseUrl =
                HttpUrl.parse(
                        System.getenv().getOrDefault(
                                "BENZINGA_REST_NEWS_URL",
                                DEFAULT_REST_URL
                        )
                );

        if (baseUrl == null) {
            throw new IllegalStateException("Invalid BENZINGA_REST_NEWS_URL.");
        }

        HttpUrl.Builder builder =
                baseUrl.newBuilder()
                        .addQueryParameter("token", token)
                        .addQueryParameter("pageSize", String.valueOf(pageSize()))
                        .addQueryParameter("displayOutput", "full")
                        .addQueryParameter("updatedSince", String.valueOf(updatedSinceEpochSeconds));

        String sort =
                System.getenv().getOrDefault(
                        "BENZINGA_REST_SORT",
                        "updated:desc"
                );

        if (sort != null && !sort.isBlank()) {
            builder.addQueryParameter("sort", sort.trim());
        }

        if (tickers != null && !tickers.isBlank()) {
            builder.addQueryParameter("tickers", tickers.trim());
        }

        if (channels != null && !channels.isBlank()) {
            builder.addQueryParameter("channels", channels.trim());
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
            System.err.println("BENZINGA REST API ERROR OBJECT: " + truncate(root.toString(), 1_000));
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

        long providerTimestampMillis =
                articleTimestampMillis(article);

        if (providerTimestampMillis > 0) {
            stats.maxProviderTimestampMillis =
                    Math.max(stats.maxProviderTimestampMillis, providerTimestampMillis);
        }

        String articleId =
                firstNonBlank(
                        article.path("revision_id").asText(),
                        article.path("id").asText(),
                        article.path("url").asText(),
                        article.path("title").asText()
                );

        if (!articleId.isBlank() && seenArticleIds.contains(articleId)) {
            stats.duplicateArticles++;
            duplicateArticleCount++;
            return;
        }

        if (!articleId.isBlank()) {
            seenArticleIds.add(articleId);
            trimSeenArticleIds();
        }

        BenzingaNewsQualityFilter.Decision decision =
                qualityFilter.evaluate("Created", article);

        if (shouldRejectForQuality(decision)) {
            stats.rejectedArticles++;
            rejectedArticleCount++;
            System.out.println(
                    "BENZINGA REST NEWS REJECTED: " +
                            decision +
                            " title=" +
                            htmlDecode(article.path("title").asText(""))
            );
            return;
        }

        String title =
                htmlDecode(article.path("title").asText(""));

        String body =
                htmlDecode(article.path("body").asText(""));

        String articleWideRejectReason = articleWideRejectReason(title, body);
        if (articleWideRejectReason != null) {
            stats.rejectedArticles++;
            rejectedArticleCount++;
            System.out.println(
                    "BENZINGA REST NEWS REJECTED BEFORE_SYMBOL_EXPANSION: " +
                            articleWideRejectReason +
                            " title=" +
                            title
            );
            return;
        }

        Set<String> symbols =
                qualityFilter.supportedSymbols(article);

        if (symbols.isEmpty()) {
            stats.rejectedArticles++;
            rejectedArticleCount++;
            System.out.println(
                    "BENZINGA REST NEWS REJECTED: NO_SYMBOLS title=" +
                            htmlDecode(article.path("title").asText(""))
            );
            return;
        }

        stats.acceptedArticles++;
        acceptedArticleCount++;

        long timestamp =
                providerTimestampMillis > 0 ? providerTimestampMillis : System.currentTimeMillis();

        System.out.println(
                "BENZINGA REST NEWS ACCEPTED: title=" +
                        title +
                        " symbols=" +
                        symbols +
                        " providerAgeMs=" +
                        Math.max(0L, System.currentTimeMillis() - timestamp)
        );

        int sent = 0;
        int maxSymbols =
                maxSymbolsPerArticle();

        for (String symbol : symbols) {
            if (sent >= maxSymbols) {
                skippedSymbolCount++;
                System.out.println(
                        "BENZINGA REST SYMBOL SKIPPED: " +
                                symbol +
                                " reason=MAX_SYMBOLS_PER_ARTICLE_REACHED max=" +
                                maxSymbols
                );
                continue;
            }

            String dedupeKey =
                    articleId + ":" + symbol;

            if (seenArticleTickerIds.contains(dedupeKey)) {
                duplicateArticleTickerCount++;
                stats.duplicateArticleTickers++;
                continue;
            }

            seenArticleTickerIds.add(dedupeKey);
            trimSeenArticleTickerIds();

            NewsEvent news =
                    new NewsEvent(
                            "BZREST:" + articleId + ":" + symbol,
                            symbol,
                            title,
                            body,
                            timestamp
                    );

            news.setSource("BENZINGA_REST");
            news.setProviderTimestamp(timestamp);
            news.setBotFirstSeenAt(System.currentTimeMillis());
            news.setSourceLagMs(0L);

            System.out.println("BENZINGA REST LIVE NEWS RECEIVED: " + symbol + " | " + title);

            newsHandler.accept(news);
            sent++;
            stats.symbolsSent++;
        }
    }

    private String articleWideRejectReason(String title, String body) {
        NewsEvent article = new NewsEvent(
                "BENZINGA_ARTICLE_WIDE",
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

    private boolean shouldRejectForQuality(
            BenzingaNewsQualityFilter.Decision decision
    ) {
        if (decision == null || decision == BenzingaNewsQualityFilter.Decision.ACCEPT) {
            return false;
        }

        // These are feed hygiene blocks, not strategy tuning. They stay active even when
        // BENZINGA_NEWS_FILTER_MODE=OFF so translations, generated spam, options-flow,
        // broad macro roundups, and no-symbol payloads do not reach FinBERT or the
        // signal journal.
        if (decision == BenzingaNewsQualityFilter.Decision.REJECT_TRANSLATION ||
                decision == BenzingaNewsQualityFilter.Decision.REJECT_NON_ENGLISH ||
                decision == BenzingaNewsQualityFilter.Decision.REJECT_AUTOMATED_CONTENT ||
                decision == BenzingaNewsQualityFilter.Decision.REJECT_BROAD_MULTI_SYMBOL_ARTICLE ||
                decision == BenzingaNewsQualityFilter.Decision.REJECT_MOVERS_RECAP ||
                decision == BenzingaNewsQualityFilter.Decision.REJECT_OPTIONS_FLOW ||
                decision == BenzingaNewsQualityFilter.Decision.REJECT_NO_SYMBOLS ||
                decision == BenzingaNewsQualityFilter.Decision.REJECT_NON_STOCK_SECURITY) {
            return true;
        }

        return !"OFF".equalsIgnoreCase(filterMode());
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

    private long articleTimestampMillis(
            JsonNode article
    ) {
        String timestamp =
                firstNonBlank(
                        article.path("created_at").asText(),
                        article.path("updated_at").asText(),
                        article.path("created").asText(),
                        article.path("updated").asText(),
                        article.path("date").asText()
                );

        long parsed =
                parseTimestampMillis(timestamp);

        if (parsed > 0) {
            return parsed;
        }

        long unix =
                firstPositiveLong(
                        article.path("created").asLong(0L),
                        article.path("updated").asLong(0L),
                        article.path("created_at").asLong(0L),
                        article.path("updated_at").asLong(0L),
                        article.path("timestamp").asLong(0L)
                );

        if (unix > 0) {
            if (unix < 10_000_000_000L) {
                return unix * 1_000L;
            }
            return unix;
        }

        return System.currentTimeMillis();
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

    private long parseTimestampMillis(
            String timestamp
    ) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }

        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception ignored) {
            // Try common offset format below.
        }

        try {
            return ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void advanceUpdatedSince(
            long maxProviderTimestampMillis
    ) {
        long candidate;

        if (maxProviderTimestampMillis > 0) {
            candidate =
                    Math.max(0L, (maxProviderTimestampMillis / 1_000L) - UPDATED_SINCE_OVERLAP_SECONDS);
        } else {
            candidate =
                    Math.max(0L, Instant.now().getEpochSecond() - UPDATED_SINCE_OVERLAP_SECONDS);
        }

        if (candidate > nextUpdatedSinceEpochSeconds) {
            nextUpdatedSinceEpochSeconds = candidate;
        }
    }

    private long initialUpdatedSinceEpochSeconds() {
        return Math.max(
                0L,
                Instant.now().getEpochSecond() - startupLookbackSeconds()
        );
    }

    private void printPollComplete(
            long requestUpdatedSince,
            int itemsSeen,
            PollStats stats
    ) {
        if (itemsSeen > 0 || pollCount % 12 == 0) {
            System.out.println(
                    "BENZINGA REST POLL COMPLETE: poll=" +
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
                            " consecutiveFailures=" +
                            consecutiveFailureCount +
                            " filterMode=" +
                            filterMode()
            );
        }
    }

    private long pollSeconds() {
        return longEnv("BENZINGA_REST_POLL_SECONDS", DEFAULT_POLL_SECONDS);
    }

    private long restFailureBackoffSeconds() {
        return restFailureBackoffSeconds(0);
    }

    private long restFailureBackoffSeconds(
            int statusCode
    ) {
        long base =
                statusCode == 429
                        ? longEnv("BENZINGA_REST_429_BACKOFF_BASE_SECONDS", 60L)
                        : longEnv("BENZINGA_REST_FAILURE_BACKOFF_BASE_SECONDS", 15L);

        long max = longEnv("BENZINGA_REST_FAILURE_BACKOFF_MAX_SECONDS", 300L);
        long multiplier = Math.max(1L, consecutiveFailureCount);
        long candidate = base * multiplier;
        return Math.max(pollSeconds(), Math.min(max, candidate));
    }

    private int pageSize() {
        return intEnv("BENZINGA_REST_PAGE_SIZE", DEFAULT_PAGE_SIZE);
    }

    private long startupLookbackSeconds() {
        return longEnv("BENZINGA_REST_STARTUP_LOOKBACK_SECONDS", DEFAULT_STARTUP_LOOKBACK_SECONDS);
    }

    private int maxSymbolsPerArticle() {
        return intEnv("BENZINGA_REST_MAX_SYMBOLS_PER_ARTICLE", 8);
    }

    private String filterMode() {
        return System.getenv().getOrDefault("BENZINGA_NEWS_FILTER_MODE", "BALANCED");
    }

    private boolean logRawRestResponses() {
        String explicit =
                firstNonBlank(
                        System.getenv("BENZINGA_REST_RAW_RESPONSE_LOGGING"),
                        System.getenv("BENZINGA_REST_LOG_RAW")
                );

        if (explicit == null || explicit.isBlank()) {
            return false;
        }

        return "true".equalsIgnoreCase(explicit.trim());
    }

    private boolean logRestRequestUrl() {
        return "true".equalsIgnoreCase(System.getenv().getOrDefault("BENZINGA_REST_LOG_REQUEST_URL", "false"));
    }

    private void trimSeenArticleIds() {
        trimSet(seenArticleIds, intEnv("BENZINGA_REST_DEDUPE_MAX_ARTICLES", 2_000));
    }

    private void trimSeenArticleTickerIds() {
        trimSet(seenArticleTickerIds, intEnv("BENZINGA_REST_DEDUPE_MAX_TICKER_ARTICLES", 5_000));
    }

    private void trimSet(
            Set<String> set,
            int maxSize
    ) {
        while (set.size() > maxSize && !set.isEmpty()) {
            String first =
                    set.iterator().next();
            set.remove(first);
        }
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
                .replace("&gt;", ">");
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
