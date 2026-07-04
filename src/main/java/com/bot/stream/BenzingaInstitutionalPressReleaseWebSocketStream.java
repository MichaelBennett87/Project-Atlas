package com.bot.stream;

import com.bot.master.CatalystQualityGate;
import com.bot.model.NewsEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BenzingaInstitutionalPressReleaseWebSocketStream extends WebSocketListener {

    private static final String DEFAULT_WS_URL =
            "wss://api.benzinga.com/api/v1/news/stream";

    private static final String SOURCE_NAME =
            "BENZINGA_PRESS_RELEASE_WS";

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

    private final Consumer<NewsEvent> newsHandler;
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Set<String> seenArticleSymbols;
    private final Set<String> seenArticleIds;

    private WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean heartbeatStarted;
    private volatile int reconnectAttempts;
    private volatile long lastMessageMillis;
    private volatile long lastNewsMillis;
    private volatile boolean stopped;
    private volatile boolean sessionDisabledByRateLimit;

    private long rawMessages;
    private long controlMessages;
    private long newsJsonMessages;
    private long ignoredJsonMessages;
    private long acceptedArticles;
    private long rejectedArticles;
    private long skippedSymbols;
    private long duplicateArticles;
    private long noSymbolRejectedArticles;
    private long createdEvents;
    private long updatedEvents;
    private long nonCreatedIgnored;

    private final String token;
    private final String channels;
    private final String tickers;
    private final boolean logRawMessages;
    private final boolean allowUpdatedArticles;
    private final boolean reconnectOnFailure;
    private final int maxSymbolsPerArticle;

    public BenzingaInstitutionalPressReleaseWebSocketStream(
            Consumer<NewsEvent> newsHandler
    ) {
        this.newsHandler = newsHandler;
        this.client = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        this.mapper = new ObjectMapper();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "benzinga-press-release-websocket-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.seenArticleSymbols = new LinkedHashSet<>();
        this.seenArticleIds = new LinkedHashSet<>();

        this.token = firstNonBlank(
                System.getenv("BENZINGA_PRESS_RELEASE_TOKEN"),
                System.getenv("BENZINGA_INSTITUTIONAL_TOKEN"),
                System.getenv("BENZINGA_INSTITUTIONAL_PRESS_RELEASE_TOKEN")
        );
        this.channels = env("BENZINGA_PRESS_RELEASE_CHANNELS", "Press Releases");
        this.tickers = env("BENZINGA_PRESS_RELEASE_TICKERS", "");
        this.logRawMessages = boolEnv("BENZINGA_PRESS_RELEASE_LOG_RAW", false);
        this.allowUpdatedArticles = boolEnv("BENZINGA_PRESS_RELEASE_ALLOW_UPDATED", true);
        this.reconnectOnFailure = boolEnv("BENZINGA_PRESS_RELEASE_RECONNECT", true);
        this.maxSymbolsPerArticle = intEnv("BENZINGA_PRESS_RELEASE_MAX_SYMBOLS_PER_ARTICLE", 6);
    }

    public void start() {
        stopped = false;
        if (sessionDisabledByRateLimit) {
            System.err.println("Benzinga institutional press-release WebSocket disabled for this session after repeated 429 rate limits. REST polling remains active as backup.");
            return;
        }
        if (token == null || token.isBlank()) {
            System.err.println("Benzinga institutional press-release WebSocket disabled: missing BENZINGA_PRESS_RELEASE_TOKEN.");
            return;
        }

        String urlString = buildWebSocketUrl();

        System.out.println(
                "Benzinga institutional press-release WebSocket URL prepared: " +
                        maskToken(urlString)
        );

        Request request =
                new Request.Builder()
                        .url(urlString)
                        .addHeader("accept", "application/json")
                        .build();

        webSocket = client.newWebSocket(request, this);

        System.out.println("Benzinga institutional press-release WebSocket started.");

        if (!heartbeatStarted) {
            heartbeatStarted = true;
            heartbeatExecutor.scheduleAtFixedRate(
                    this::printHeartbeat,
                    60,
                    60,
                    TimeUnit.SECONDS
            );
        }
    }

    public void stop() {
        stopped = true;
        connected = false;
        reconnectAttempts = 0;
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try {
                socket.close(1000, "Stopped by bot");
            } catch (Exception ignored) {
            }
            try {
                socket.cancel();
            } catch (Exception ignored) {
            }
        }
        try {
            heartbeatExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        try {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onOpen(
            WebSocket webSocket,
            Response response
    ) {
        connected = true;
        reconnectAttempts = 0;
        lastMessageMillis = System.currentTimeMillis();

        System.out.println("Benzinga institutional press-release WebSocket connected.");

        sendSubscribePayload(webSocket);

        System.out.println("Waiting for Benzinga institutional press releases...");
    }

    @Override
    public void onMessage(
            WebSocket webSocket,
            String text
    ) {
        rawMessages++;
        lastMessageMillis = System.currentTimeMillis();

        if (logRawMessages) {
            System.out.println("BENZINGA PR RAW MESSAGE: " + truncate(text, 1_500));
        }

        String trimmed = text == null ? "" : text.trim();

        if (isPlainTextControlMessage(trimmed)) {
            controlMessages++;
            System.out.println("BENZINGA PR CONTROL MESSAGE: " + trimmed);
            return;
        }

        if (!looksLikeJson(trimmed)) {
            controlMessages++;
            System.out.println("BENZINGA PR NON_JSON MESSAGE IGNORED: " + truncate(trimmed, 500));
            return;
        }

        try {
            JsonNode root = mapper.readTree(trimmed);

            if (isJsonControlMessage(root)) {
                controlMessages++;
                System.out.println("BENZINGA PR JSON CONTROL MESSAGE: " + truncate(trimmed, 500));
                return;
            }

            String kind = root.path("kind").asText("");

            if (!"News/v1".equalsIgnoreCase(kind) && !"news".equalsIgnoreCase(kind)) {
                ignoredJsonMessages++;
                System.out.println(
                        "BENZINGA PR JSON MESSAGE IGNORED: kind=" +
                                kind +
                                " body=" +
                                truncate(trimmed, 700)
                );
                return;
            }

            newsJsonMessages++;

            JsonNode data = root.path("data");
            String action = data.path("action").asText("");

            if ("Created".equalsIgnoreCase(action) || "created".equalsIgnoreCase(action)) {
                createdEvents++;
            } else if ("Updated".equalsIgnoreCase(action) || "updated".equalsIgnoreCase(action)) {
                updatedEvents++;
            }

            if (!allowUpdatedArticles && !"Created".equalsIgnoreCase(action) && !"created".equalsIgnoreCase(action)) {
                nonCreatedIgnored++;
                rejectedArticles++;
                System.out.println(
                        "BENZINGA PR NEWS REJECTED: NON_CREATED_EVENT" +
                                " action=" + action +
                                " title=" + htmlDecode(data.path("content").path("title").asText("")) +
                                " createdEvents=" + createdEvents +
                                " updatedEvents=" + updatedEvents
                );
                return;
            }

            JsonNode content = data.path("content");

            if (content == null || content.isMissingNode() || content.isNull()) {
                rejectedArticles++;
                System.out.println("BENZINGA PR NEWS REJECTED: MISSING_CONTENT");
                return;
            }

            if (shouldRejectForPressReleaseFeed(content)) {
                rejectedArticles++;
                System.out.println(
                        "BENZINGA PR NEWS REJECTED: FEED_HYGIENE title=" +
                                htmlDecode(content.path("title").asText(""))
                );
                return;
            }

            processArticle(content);
        } catch (Exception e) {
            rejectedArticles++;
            System.err.println("Benzinga PR news parse error: " + e.getMessage());
        }
    }

    @Override
    public void onFailure(
            WebSocket webSocket,
            Throwable t,
            Response response
    ) {
        connected = false;
        if (stopped) {
            return;
        }

        String message = t == null ? "unknown" : t.getMessage();
        System.err.println("Benzinga institutional press-release WebSocket failure: " + message);

        if (response != null) {
            System.err.println(
                    "Benzinga institutional press-release WebSocket HTTP response: " +
                            response.code() +
                            " " +
                            response.message()
            );

            if (response.code() == 401 || response.code() == 403) {
                System.err.println("Benzinga PR auth/entitlement failure. Not reconnecting automatically.");
                return;
            }
        }

        if (response != null && response.code() == 429) {
            reconnectAttempts++;
            int max429Attempts = intEnv("BENZINGA_PR_WS_429_MAX_ATTEMPTS", 3);
            if (max429Attempts > 0 && reconnectAttempts > max429Attempts) {
                System.err.println("BENZINGA_PR_WS 429 rate limit guard active: disabling automatic WebSocket reconnect after " +
                        reconnectAttempts + " attempts. REST polling remains active as backup.");
                return;
            }
            long delayMillis = reconnectDelayMillis(429);
            System.out.println("BENZINGA_PR_WS 429 rate limited. Backing off for " +
                    (delayMillis / 1_000L) + " seconds before reconnect attempt=" + reconnectAttempts);
            sleepBeforeReconnect(delayMillis);
            if (!stopped) {
                start();
            }
            return;
        }

        if (reconnectOnFailure) {
            try {
                int responseCode = response == null ? 0 : response.code();
                reconnectAttempts++;
                long delayMillis = reconnectDelayMillis(responseCode);

                System.out.println(
                        "Reconnecting Benzinga institutional press-release WebSocket in " +
                                (delayMillis / 1_000L) +
                                " seconds... attempt=" +
                                reconnectAttempts +
                                " responseCode=" +
                                responseCode
                );

                Thread.sleep(delayMillis);
                start();
            } catch (Exception e) {
                System.err.println("Benzinga institutional PR reconnect failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void onClosed(
            WebSocket webSocket,
            int code,
            String reason
    ) {
        connected = false;
        if (stopped) {
            return;
        }
        System.out.println(
                "Benzinga institutional press-release WebSocket closed: code=" +
                        code +
                        " reason=" +
                        reason
        );
    }

    private void processArticle(
            JsonNode content
    ) {
        String articleId =
                firstNonBlank(
                        content.path("id").asText(),
                        content.path("revision_id").asText(),
                        content.path("original_id").asText(),
                        content.path("url").asText(),
                        String.valueOf(System.currentTimeMillis())
                );

        String title = htmlDecode(content.path("title").asText(""));
        String body = htmlDecode(content.path("body").asText(""));

        long timestamp =
                articleTimestampMillis(content);

        if (timestamp <= 0) {
            timestamp = System.currentTimeMillis();
        }

        if (!articleId.isBlank() && seenArticleIds.contains(articleId)) {
            duplicateArticles++;
            return;
        }

        String articleWideRejectReason = articleWideRejectReason(title, body);
        if (articleWideRejectReason != null) {
            if (!articleId.isBlank()) {
                seenArticleIds.add(articleId);
                trimSeenArticleIds();
            }
            rejectedArticles++;
            System.out.println(
                    "BENZINGA PR NEWS REJECTED BEFORE_SYMBOL_EXPANSION: " +
                            articleWideRejectReason +
                            " title=" +
                            title +
                            " source=BENZINGA_PRESS_RELEASE_WS"
            );
            return;
        }

        Set<String> symbols = supportedSymbols(content);

        if (symbols.isEmpty()) {
            if (!articleId.isBlank()) {
                seenArticleIds.add(articleId);
                trimSeenArticleIds();
            }
            rejectedArticles++;
            noSymbolRejectedArticles++;
            System.out.println("BENZINGA PR NEWS REJECTED: NO_SYMBOLS title=" + title + " extractedFromBody=false source=BENZINGA_PRESS_RELEASE_WS");
            return;
        }

        if (!articleId.isBlank()) {
            seenArticleIds.add(articleId);
            trimSeenArticleIds();
        }

        acceptedArticles++;
        lastNewsMillis = System.currentTimeMillis();

        System.out.println(
                "BENZINGA PR NEWS ACCEPTED: title=" +
                        title +
                        " symbols=" +
                        symbols +
                        " providerAgeMs=" +
                        Math.max(0L, System.currentTimeMillis() - timestamp) +
                        " articleId=" +
                        articleId
        );

        int sent = 0;

        for (String symbol : symbols) {
            if (sent >= maxSymbolsPerArticle) {
                skippedSymbols++;
                System.out.println(
                        "BENZINGA PR SYMBOL SKIPPED: " +
                                symbol +
                                " reason=MAX_SYMBOLS_PER_ARTICLE_REACHED max=" +
                                maxSymbolsPerArticle
                );
                continue;
            }

            String dedupeKey = articleId + ":" + symbol;

            if (seenArticleSymbols.contains(dedupeKey)) {
                duplicateArticles++;
                continue;
            }

            seenArticleSymbols.add(dedupeKey);
            trimSeenArticleSymbols();

            NewsEvent news =
                    new NewsEvent(
                            "BZPR:" + articleId + ":" + symbol,
                            symbol,
                            title,
                            body,
                            timestamp
                    );

            news.setSource(SOURCE_NAME);
            news.setProviderTimestamp(timestamp);
            news.setBotFirstSeenAt(System.currentTimeMillis());
            news.setSourceLagMs(0L);

            System.out.println("BENZINGA PR LIVE NEWS RECEIVED: " + symbol + " | " + title);

            newsHandler.accept(news);
            sent++;
        }
    }

    private String buildWebSocketUrl() {
        String fullUrlOverride = env("BENZINGA_PRESS_RELEASE_FULL_WS_URL", "");

        if (fullUrlOverride != null && !fullUrlOverride.isBlank()) {
            return fullUrlOverride.trim();
        }

        String base = env("BENZINGA_PRESS_RELEASE_WS_URL", DEFAULT_WS_URL);

        HttpUrl baseUrl = HttpUrl.parse(toHttpUrlForBuilder(base));

        if (baseUrl == null) {
            throw new IllegalStateException("Invalid BENZINGA_PRESS_RELEASE_WS_URL: " + base);
        }

        HttpUrl.Builder builder =
                baseUrl.newBuilder()
                        .addQueryParameter("token", token);

        if (channels != null && !channels.isBlank()) {
            builder.addQueryParameter("channels", channels.trim());
        }

        if (tickers != null && !tickers.isBlank()) {
            builder.addQueryParameter("tickers", tickers.trim());
        }

        return toWebSocketUrl(builder.build().toString());
    }

    private void sendSubscribePayload(
            WebSocket webSocket
    ) {
        try {
            ObjectNode subscribeMessage = mapper.createObjectNode();
            subscribeMessage.put("action", "subscribe");
            subscribeMessage.put("type", "news");

            String payload = mapper.writeValueAsString(subscribeMessage);
            boolean sent = webSocket.send(payload);

            System.out.println(
                    "BENZINGA PR WS SUBSCRIBE SENT: " +
                            sent +
                            " payload=" +
                            payload
            );
        } catch (Exception e) {
            System.err.println("Failed sending Benzinga PR subscribe payload: " + e.getMessage());
        }
    }

    private String articleWideRejectReason(String title, String body) {
        NewsEvent article = new NewsEvent(
                "BENZINGA_PR_WS_ARTICLE_WIDE",
                "",
                title == null ? "" : title,
                body == null ? "" : body,
                System.currentTimeMillis()
        );

        String preNlpReason = CatalystQualityGate.preNlpRejectReason(article);
        if (preNlpReason != null) {
            return preNlpReason;
        }

        return CatalystQualityGate.rejectReason(article);
    }

    private boolean shouldRejectForPressReleaseFeed(
            JsonNode content
    ) {
        String type = safe(content.path("type").asText()).toLowerCase(Locale.ROOT);
        String title = safe(content.path("title").asText()).toLowerCase(Locale.ROOT);
        String body = safe(content.path("body").asText()).toLowerCase(Locale.ROOT);
        String combined = title + " " + body;

        if (type.startsWith("benzinga_wire_") || containsNonLatinScript(title)) {
            return true;
        }

        if (containsAny(
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
        )) {
            return true;
        }

        return false;
    }

    private Set<String> supportedSymbols(
            JsonNode content
    ) {
        Set<String> symbols = new LinkedHashSet<>();

        addSymbolsFromArray(symbols, content.path("securities"), "symbol");
        addSymbolsFromArray(symbols, content.path("stocks"), "name");
        addSymbolsFromArray(symbols, content.path("stocks"), "symbol");

        addSymbolsFromQuoteLinks(symbols, content.path("title").asText(""));
        addSymbolsFromQuoteLinks(symbols, content.path("body").asText(""));
        addSymbolsFromQuoteLinks(symbols, content.path("teaser").asText(""));
        addSymbolsFromHtmlDataTickers(symbols, content.path("body").asText(""));
        addSymbolsFromOtcMarketsLinks(symbols, content.path("body").asText(""));
        addSymbolsFromExchangePatterns(symbols, content.path("title").asText(""));
        addSymbolsFromExchangePatterns(symbols, content.path("body").asText(""));
        addSymbolsFromExchangePatterns(symbols, content.path("teaser").asText(""));
        addSymbolsFromParenthesizedExchangePatterns(symbols, content.path("title").asText(""));
        addSymbolsFromParenthesizedExchangePatterns(symbols, content.path("body").asText(""));
        addSymbolsFromParenthesizedExchangePatterns(symbols, content.path("teaser").asText(""));
        addSymbolsFromBroadExchangePatterns(symbols, content.path("title").asText(""));
        addSymbolsFromBroadExchangePatterns(symbols, content.path("body").asText(""));
        addSymbolsFromBroadExchangePatterns(symbols, content.path("teaser").asText(""));

        return symbols;
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

            if (isSupportedStockSymbol(symbol, "OTC")) {
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

    private void printHeartbeat() {
        long now = System.currentTimeMillis();

        if (connected) {
            try {
                if (webSocket != null) {
                    webSocket.send("ping");
                    System.out.println("BENZINGA PR WS PING SENT");
                }
            } catch (Exception e) {
                System.err.println("BENZINGA PR WS PING FAILED: " + e.getMessage());
            }
        }

        System.out.println(
                "BENZINGA PR WS HEARTBEAT: connected=" +
                        connected +
                        " secondsSinceLastMessage=" +
                        secondsSince(lastMessageMillis, now) +
                        " secondsSinceLastNews=" +
                        (lastNewsMillis <= 0 ? "none" : secondsSince(lastNewsMillis, now)) +
                        " rawMessages=" +
                        rawMessages +
                        " controlMessages=" +
                        controlMessages +
                        " newsJsonMessages=" +
                        newsJsonMessages +
                        " ignoredJsonMessages=" +
                        ignoredJsonMessages +
                        " acceptedArticles=" +
                        acceptedArticles +
                        " rejectedArticles=" +
                        rejectedArticles +
                        " skippedSymbols=" +
                        skippedSymbols +
                        " duplicates=" +
                        duplicateArticles +
                        " noSymbolRejected=" +
                        noSymbolRejectedArticles +
                        " createdEvents=" +
                        createdEvents +
                        " updatedEvents=" +
                        updatedEvents +
                        " nonCreatedIgnored=" +
                        nonCreatedIgnored +
                        " channels=" +
                        channels
        );
    }

    private void sleepBeforeReconnect(long delayMillis) {
        long remaining = Math.max(1_000L, delayMillis);
        long deadline = System.currentTimeMillis() + remaining;
        while (!stopped && remaining > 0L) {
            try {
                Thread.sleep(Math.min(remaining, 1_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining = deadline - System.currentTimeMillis();
        }
    }

    private long reconnectDelayMillis(
            int responseCode
    ) {
        long baseSeconds =
                responseCode == 429
                        ? intEnv("BENZINGA_PR_WS_429_RECONNECT_BASE_SECONDS", 300)
                        : intEnv("BENZINGA_PR_WS_RECONNECT_BASE_SECONDS", 5);

        long maxSeconds = intEnv("BENZINGA_PR_WS_RECONNECT_MAX_SECONDS", 1800);
        long attempts = Math.max(1L, reconnectAttempts);
        long candidateSeconds = baseSeconds * attempts;

        return Math.max(1L, Math.min(maxSeconds, candidateSeconds)) * 1_000L;
    }

    private boolean isPlainTextControlMessage(
            String value
    ) {
        return "pong".equalsIgnoreCase(value) ||
                "ping".equalsIgnoreCase(value) ||
                "connected".equalsIgnoreCase(value) ||
                "subscribed".equalsIgnoreCase(value);
    }

    private boolean isJsonControlMessage(
            JsonNode root
    ) {
        if (root == null || root.isNull()) {
            return true;
        }

        String type = root.path("type").asText("");
        String status = root.path("status").asText("");
        String message = root.path("message").asText("");

        return "pong".equalsIgnoreCase(type) ||
                "ping".equalsIgnoreCase(type) ||
                "subscribed".equalsIgnoreCase(status) ||
                "connected".equalsIgnoreCase(status) ||
                "pong".equalsIgnoreCase(message) ||
                "ping".equalsIgnoreCase(message);
    }

    private boolean looksLikeJson(
            String value
    ) {
        return value != null &&
                !value.isBlank() &&
                (value.startsWith("{") || value.startsWith("["));
    }

    private String toHttpUrlForBuilder(
            String url
    ) {
        if (url == null) {
            return "";
        }

        if (url.startsWith("wss://")) {
            return "https://" + url.substring("wss://".length());
        }

        if (url.startsWith("ws://")) {
            return "http://" + url.substring("ws://".length());
        }

        return url;
    }

    private String toWebSocketUrl(
            String url
    ) {
        if (url == null) {
            return "";
        }

        if (url.startsWith("https://")) {
            return "wss://" + url.substring("https://".length());
        }

        if (url.startsWith("http://")) {
            return "ws://" + url.substring("http://".length());
        }

        return url;
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

    private void trimSeenArticleSymbols() {
        while (seenArticleSymbols.size() > intEnv("BENZINGA_PR_WS_DEDUPE_MAX_TICKER_ARTICLES", 5_000) &&
                !seenArticleSymbols.isEmpty()) {
            seenArticleSymbols.remove(seenArticleSymbols.iterator().next());
        }
    }


    private void trimSeenArticleIds() {
        while (seenArticleIds.size() > intEnv("BENZINGA_PR_WS_DEDUPE_MAX_ARTICLE_IDS", 5_000) &&
                !seenArticleIds.isEmpty()) {
            seenArticleIds.remove(seenArticleIds.iterator().next());
        }
    }

    private long secondsSince(
            long timestamp,
            long now
    ) {
        if (timestamp <= 0) {
            return -1L;
        }
        return Math.max(0L, (now - timestamp) / 1_000L);
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

    private static String env(
            String key,
            String defaultValue
    ) {
        return System.getenv().getOrDefault(key, defaultValue);
    }

    private static boolean boolEnv(
            String key,
            boolean defaultValue
    ) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
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

    private String safe(
            String value
    ) {
        return value == null ? "" : value;
    }
}
