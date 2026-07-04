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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class BenzingaNewsWebSocketStream extends WebSocketListener {

    private static final String DEFAULT_BENZINGA_NEWS_WS_URL =
            "wss://api.benzinga.com/api/v1/news/stream";

    private static final String SOURCE_NAME =
            "BENZINGA_DIRECT";

    private final Consumer<NewsEvent> newsHandler;
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Set<String> seenArticleSymbols;
    private final BenzingaNewsQualityFilter qualityFilter;

    private WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean heartbeatStarted;
    private volatile int reconnectAttempts;
    private volatile long lastMessageMillis;
    private volatile long lastNewsMillis;
    private volatile boolean sessionDisabledByRateLimit;
    private volatile boolean stopped;

    private long rawMessages;
    private long controlMessages;
    private long newsJsonMessages;
    private long ignoredJsonMessages;
    private long acceptedArticles;
    private long rejectedArticles;
    private long skippedSymbols;
    private long duplicateArticles;
    private long createdEvents;
    private long updatedEvents;
    private long nonCreatedIgnored;

    private final String token;
    private final String filterMode;
    private final boolean logRawMessages;
    private final boolean allowUpdatedArticles;
    private final boolean reconnectOnFailure;
    private final int maxSymbolsPerArticle;

    public BenzingaNewsWebSocketStream(
            Consumer<NewsEvent> newsHandler
    ) {
        this.newsHandler = newsHandler;
        this.client = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        this.mapper = new ObjectMapper();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "benzinga-news-websocket-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.seenArticleSymbols = new LinkedHashSet<>();
        this.qualityFilter = new BenzingaNewsQualityFilter();

        this.token = firstNonBlank(
                System.getenv("BENZINGA_NEWS_TOKEN"),
                System.getenv("BENZINGA_API_KEY")
        );
        this.filterMode = env("BENZINGA_NEWS_FILTER_MODE", "BALANCED");
        this.logRawMessages = boolEnv("BENZINGA_NEWS_LOG_RAW", true);
        this.allowUpdatedArticles = boolEnv("BENZINGA_NEWS_ALLOW_UPDATED", true);
        this.reconnectOnFailure = boolEnv("BENZINGA_NEWS_RECONNECT", true);
        this.maxSymbolsPerArticle = intEnv("BENZINGA_MAX_SYMBOLS_PER_ARTICLE", 8);
    }

    public void start() {
        stopped = false;
        if (sessionDisabledByRateLimit) {
            System.err.println("Benzinga news WebSocket disabled for this session after repeated 429 rate limits. REST polling remains active as backup.");
            return;
        }
        if (token == null || token.isBlank()) {
            System.err.println("Benzinga news WebSocket disabled: missing BENZINGA_NEWS_TOKEN.");
            return;
        }

        String urlString =
                buildWebSocketUrl();

        System.out.println(
                "Benzinga news WebSocket URL prepared: " +
                        maskToken(urlString)
        );

        Request.Builder requestBuilder =
                new Request.Builder()
                        .url(urlString)
                        .addHeader("accept", "application/json");

        if ("bearer".equalsIgnoreCase(env("BENZINGA_NEWS_AUTH_MODE", "query"))) {
            requestBuilder.addHeader("Authorization", "Bearer " + token);
        }

        webSocket =
                client.newWebSocket(requestBuilder.build(), this);

        System.out.println("Benzinga news WebSocket started.");

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

        System.out.println("Benzinga news WebSocket connected.");

        sendExactSubscribePayload(webSocket);

        System.out.println("Waiting for Benzinga direct news articles...");
    }

    @Override
    public void onMessage(
            WebSocket webSocket,
            String text
    ) {
        rawMessages++;
        lastMessageMillis = System.currentTimeMillis();

        if (logRawMessages) {
            System.out.println("BENZINGA RAW MESSAGE: " + truncate(text, 1_500));
        }

        String trimmed =
                text == null ? "" : text.trim();

        if (isPlainTextControlMessage(trimmed)) {
            controlMessages++;
            System.out.println("BENZINGA CONTROL MESSAGE: " + trimmed);
            return;
        }

        if (!looksLikeJson(trimmed)) {
            controlMessages++;
            System.out.println("BENZINGA NON_JSON MESSAGE IGNORED: " + truncate(trimmed, 500));
            return;
        }

        try {
            JsonNode root =
                    mapper.readTree(trimmed);

            if (isJsonControlMessage(root)) {
                controlMessages++;
                System.out.println("BENZINGA JSON CONTROL MESSAGE: " + truncate(trimmed, 500));
                return;
            }

            String kind =
                    root.path("kind").asText("");

            if (!"News/v1".equalsIgnoreCase(kind)) {
                ignoredJsonMessages++;
                System.out.println(
                        "BENZINGA JSON MESSAGE IGNORED: kind=" +
                                kind +
                                " body=" +
                                truncate(trimmed, 700)
                );
                return;
            }

            newsJsonMessages++;

            JsonNode data =
                    root.path("data");

            String action =
                    data.path("action").asText("");

            if ("Created".equalsIgnoreCase(action)) {
                createdEvents++;
            } else if ("Updated".equalsIgnoreCase(action)) {
                updatedEvents++;
            }

            if (!allowUpdatedArticles && !"Created".equalsIgnoreCase(action)) {
                nonCreatedIgnored++;
                rejectedArticles++;
                System.out.println(
                        "BENZINGA NEWS REJECTED: NON_CREATED_EVENT" +
                                " action=" +
                                action +
                                " title=" +
                                data.path("content").path("title").asText("") +
                                " createdEvents=" +
                                createdEvents +
                                " updatedEvents=" +
                                updatedEvents
                );
                return;
            }

            JsonNode content =
                    data.path("content");

            if (content == null || content.isMissingNode() || content.isNull()) {
                rejectedArticles++;
                System.out.println("BENZINGA NEWS REJECTED: MISSING_CONTENT");
                return;
            }

            BenzingaNewsQualityFilter.Decision decision =
                    qualityFilter.evaluate(action, content);

            if (shouldRejectForQuality(decision)) {
                rejectedArticles++;
                System.out.println(
                        "BENZINGA NEWS REJECTED: " +
                                decision +
                                " title=" +
                                htmlDecode(content.path("title").asText(""))
                );
                return;
            }

            processArticle(content, action);
        } catch (Exception e) {
            rejectedArticles++;
            System.err.println(
                    "Benzinga news parse error: " +
                            e.getMessage()
            );
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

        String message =
                t == null ? "unknown" : t.getMessage();

        System.err.println("Benzinga news WebSocket failure: " + message);

        if (response != null) {
            System.err.println(
                    "Benzinga news WebSocket HTTP response: " +
                            response.code() +
                            " " +
                            response.message()
            );

            if (response.code() == 401 || response.code() == 403) {
                System.err.println("Benzinga auth/entitlement failure. Not reconnecting automatically.");
                return;
            }
        }

        if (response != null && response.code() == 429) {
            reconnectAttempts++;
            int max429Attempts = intEnv("BENZINGA_WS_429_MAX_ATTEMPTS", 1);
            if (max429Attempts > 0 && reconnectAttempts >= max429Attempts) {
                sessionDisabledByRateLimit = true;
                System.err.println("BENZINGA_WS 429 rate limit guard active: disabling automatic WebSocket reconnect for this session after " +
                        reconnectAttempts + " attempts. REST polling remains active as backup.");
                return;
            }
            long delayMillis = reconnectDelayMillis(429);
            System.out.println("BENZINGA_WS 429 rate limited. Backing off for " +
                    (delayMillis / 1_000L) + " seconds before reconnect attempt=" + reconnectAttempts);
            sleepBeforeReconnect(delayMillis);
            if (!stopped) {
                start();
            }
            return;
        }

        if (reconnectOnFailure) {
            try {
                int responseCode =
                        response == null ? 0 : response.code();

                reconnectAttempts++;

                long delayMillis =
                        reconnectDelayMillis(responseCode);

                System.out.println(
                        "Reconnecting Benzinga news WebSocket in " +
                                (delayMillis / 1_000L) +
                                " seconds... attempt=" +
                                reconnectAttempts +
                                " responseCode=" +
                                responseCode
                );

                Thread.sleep(delayMillis);
                start();
            } catch (Exception e) {
                System.err.println("Benzinga reconnect failed: " + e.getMessage());
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
                "Benzinga news WebSocket closed: code=" +
                        code +
                        " reason=" +
                        reason
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
                        ? intEnv("BENZINGA_WS_429_RECONNECT_BASE_SECONDS", 300)
                        : intEnv("BENZINGA_WS_RECONNECT_BASE_SECONDS", 5);

        long maxSeconds =
                intEnv("BENZINGA_WS_RECONNECT_MAX_SECONDS", 1800);

        long attempts =
                Math.max(1L, reconnectAttempts);

        long candidateSeconds =
                baseSeconds * attempts;

        return Math.max(1L, Math.min(maxSeconds, candidateSeconds)) * 1_000L;
    }

    private void sendExactSubscribePayload(
            WebSocket webSocket
    ) {
        try {
            ObjectNode subscribeMessage =
                    mapper.createObjectNode();

            subscribeMessage.put("action", "subscribe");
            subscribeMessage.put("type", "news");

            String payload =
                    mapper.writeValueAsString(subscribeMessage);

            boolean sent =
                    webSocket.send(payload);

            System.out.println(
                    "BENZINGA NEWS WS SUBSCRIBE SENT: " +
                            sent +
                            " payload=" +
                            payload
            );
        } catch (Exception e) {
            System.err.println(
                    "Failed sending Benzinga news subscribe payload: " +
                            e.getMessage()
            );
        }
    }

    private String articleWideRejectReason(String title, String body) {
        NewsEvent article = new NewsEvent(
                "BENZINGA_WS_ARTICLE_WIDE",
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

        return !"OFF".equalsIgnoreCase(filterMode);
    }

    private void processArticle(
            JsonNode content,
            String action
    ) {
        String articleId =
                "Updated".equalsIgnoreCase(action)
                        ? firstNonBlank(
                        content.path("revision_id").asText(),
                        content.path("id").asText(),
                        String.valueOf(System.currentTimeMillis())
                )
                        : firstNonBlank(
                        content.path("id").asText(),
                        content.path("revision_id").asText(),
                        String.valueOf(System.currentTimeMillis())
                );

        String title =
                htmlDecode(content.path("title").asText(""));

        String body =
                htmlDecode(content.path("body").asText(""));

        long timestamp =
                parseTimestampMillis(
                        firstNonBlank(
                                content.path("created_at").asText(),
                                content.path("updated_at").asText(),
                                Instant.now().toString()
                        )
                );

        String articleWideRejectReason = articleWideRejectReason(title, body);
        if (articleWideRejectReason != null) {
            rejectedArticles++;
            System.out.println(
                    "BENZINGA NEWS REJECTED BEFORE_SYMBOL_EXPANSION: " +
                            articleWideRejectReason +
                            " title=" +
                            title
            );
            return;
        }

        Set<String> symbols =
                qualityFilter.supportedSymbols(content);

        if (symbols.isEmpty()) {
            rejectedArticles++;
            System.out.println("BENZINGA NEWS REJECTED: NO_SYMBOLS title=" + title);
            return;
        }

        acceptedArticles++;
        lastNewsMillis = System.currentTimeMillis();

        System.out.println(
                "BENZINGA NEWS ACCEPTED: action=" +
                        action +
                        " title=" +
                        title +
                        " symbols=" +
                        symbols
        );

        int sent = 0;

        for (String symbol : symbols) {
            if (sent >= maxSymbolsPerArticle) {
                skippedSymbols++;
                System.out.println(
                        "BENZINGA SYMBOL SKIPPED: " +
                                symbol +
                                " reason=MAX_SYMBOLS_PER_ARTICLE_REACHED max=" +
                                maxSymbolsPerArticle
                );
                continue;
            }

            String dedupeKey =
                    articleId + ":" + symbol;

            if (seenArticleSymbols.contains(dedupeKey)) {
                duplicateArticles++;
                continue;
            }

            seenArticleSymbols.add(dedupeKey);

            NewsEvent news =
                    new NewsEvent(
                            "BZ:" + articleId + ":" + symbol,
                            symbol,
                            title,
                            body,
                            timestamp
                    );

            news.setSource(SOURCE_NAME);
            news.setProviderTimestamp(timestamp);
            news.setBotFirstSeenAt(System.currentTimeMillis());
            news.setSourceLagMs(0L);

            System.out.println("BENZINGA LIVE NEWS RECEIVED: " + symbol + " | " + title);

            newsHandler.accept(news);
            sent++;
        }
    }

    private String buildWebSocketUrl() {
        String fullUrlOverride =
                env("BENZINGA_NEWS_FULL_WS_URL", "");

        if (fullUrlOverride != null && !fullUrlOverride.isBlank()) {
            return fullUrlOverride.trim();
        }

        String base =
                env("BENZINGA_NEWS_WS_URL", DEFAULT_BENZINGA_NEWS_WS_URL);

        HttpUrl baseUrl =
                HttpUrl.parse(toHttpUrlForBuilder(base));

        if (baseUrl == null) {
            throw new IllegalStateException("Invalid Benzinga WebSocket URL: " + base);
        }

        HttpUrl httpUrl =
                baseUrl.newBuilder()
                        .addQueryParameter("token", token)
                        .build();

        return toWebSocketUrl(httpUrl.toString());
    }

    private void printHeartbeat() {
        long now =
                System.currentTimeMillis();

        long secondsSinceLastMessage =
                lastMessageMillis <= 0 ? -1 : (now - lastMessageMillis) / 1_000L;

        String secondsSinceLastNews =
                lastNewsMillis <= 0 ? "none" : String.valueOf((now - lastNewsMillis) / 1_000L);

        System.out.println(
                "BENZINGA NEWS WS HEARTBEAT: connected=" +
                        connected +
                        " secondsSinceLastMessage=" +
                        secondsSinceLastMessage +
                        " secondsSinceLastNews=" +
                        secondsSinceLastNews +
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
                        " createdEvents=" +
                        createdEvents +
                        " updatedEvents=" +
                        updatedEvents +
                        " nonCreatedIgnored=" +
                        nonCreatedIgnored +
                        " filterMode=" +
                        filterMode +
                        " allowUpdated=" +
                        allowUpdatedArticles
        );

        if (connected && webSocket != null) {
            webSocket.send("ping");
            System.out.println("BENZINGA NEWS WS PING SENT");
        }
    }

    private boolean isPlainTextControlMessage(
            String text
    ) {
        if (text == null) {
            return false;
        }

        String normalized =
                text.trim().toLowerCase(Locale.ROOT);

        return normalized.equals("pong") ||
                normalized.equals("ping") ||
                normalized.equals("connected") ||
                normalized.equals("authenticated");
    }

    private boolean isJsonControlMessage(
            JsonNode root
    ) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return false;
        }

        String text =
                root.toString().toLowerCase(Locale.ROOT);

        return text.contains("\"type\":\"pong\"") ||
                text.contains("\"event\":\"pong\"") ||
                text.contains("\"message\":\"pong\"") ||
                text.contains("\"action\":\"pong\"") ||
                text.contains("\"type\":\"ping\"") ||
                text.contains("\"event\":\"ping\"") ||
                text.contains("\"message\":\"ping\"") ||
                text.contains("\"action\":\"ping\"");
    }

    private boolean looksLikeJson(
            String text
    ) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String trimmed =
                text.trim();

        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private long parseTimestampMillis(
            String timestamp
    ) {
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
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

    private String firstNonBlank(
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

    private String maskToken(
            String url
    ) {
        if (url == null) {
            return "";
        }

        return url.replaceAll("token=[^&]+", "token=***");
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

    private String env(
            String key,
            String defaultValue
    ) {
        String value =
                System.getenv(key);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value.trim();
    }

    private boolean boolEnv(
            String key,
            boolean defaultValue
    ) {
        String value =
                System.getenv(key);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return "true".equalsIgnoreCase(value.trim());
    }

    private int intEnv(
            String key,
            int defaultValue
    ) {
        try {
            return Integer.parseInt(env(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
