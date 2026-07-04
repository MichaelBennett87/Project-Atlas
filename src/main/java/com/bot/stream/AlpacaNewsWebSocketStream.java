package com.bot.stream;

import com.bot.model.NewsEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class AlpacaNewsWebSocketStream {

    private enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTH_SENT,
        AUTHENTICATED,
        SUBSCRIBED,
        CLOSED
    }

    private static final Object GLOBAL_STREAM_LOCK_MONITOR = new Object();
    private static final AtomicBoolean JVM_STREAM_ACTIVE = new AtomicBoolean(false);

    private static final String NEWS_STREAM_URL =
            "wss://stream.data.alpaca.markets/v1beta1/news";

    private static final long HEARTBEAT_INTERVAL_MS =
            60_000L;

    private final String apiKey;
    private final String secretKey;
    private final Consumer<NewsEvent> newsHandler;
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final Set<String> seenNewsIds = new HashSet<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicLong socketGeneration = new AtomicLong(0L);

    private final boolean authenticateWithMessage;

    private WebSocket webSocket;
    private volatile boolean running = false;
    private volatile long lastMessageAt = 0L;
    private volatile long lastNewsAt = 0L;
    private volatile int reconnectAttempts = 0;
    private volatile int consecutiveStaleHeartbeats = 0;
    private volatile long nextReconnectAllowedAt = 0L;
    private volatile boolean connected = false;
    private volatile boolean authenticated = false;
    private volatile boolean subscribed = false;
    private volatile boolean authMessageSent = false;
    private volatile boolean subscribeMessageSent = false;
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private volatile boolean disabledForSession = false;
    private volatile boolean ownsGlobalPermit = false;
    private FileChannel globalLockChannel;
    private FileLock globalFileLock;

    public AlpacaNewsWebSocketStream(Consumer<NewsEvent> newsHandler) {
        this(
                System.getenv("ALPACA_API_KEY"),
                System.getenv("ALPACA_SECRET_KEY"),
                newsHandler
        );
    }

    public AlpacaNewsWebSocketStream(
            String apiKey,
            String secretKey,
            Consumer<NewsEvent> newsHandler
    ) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.newsHandler = newsHandler;
        this.client = new OkHttpClient.Builder()
                .pingInterval(envLong("ALPACA_NEWS_WS_PING_SECONDS", 25L), TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.mapper = new ObjectMapper();
        this.authenticateWithMessage =
                !"HEADERS".equalsIgnoreCase(System.getenv().getOrDefault("ALPACA_NEWS_WS_AUTH_MODE", "MESSAGE"));

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing ALPACA_API_KEY environment variable.");
        }

        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Missing ALPACA_SECRET_KEY environment variable.");
        }
    }

    public synchronized void start() {
        if (disabledForSession) {
            System.err.println("Alpaca news WebSocket start skipped: disabled for this session after non-recoverable stream error.");
            return;
        }
        if (!acquireGlobalStreamPermit()) {
            running = false;
            disabledForSession = true;
            System.err.println(
                    "Alpaca news WebSocket start skipped: another JVM or stream already owns the Alpaca news connection. " +
                            "This prevents Alpaca 406 connection-limit loops; REST polling remains active as backup."
            );
            return;
        }
        running = true;
        if (!connecting.compareAndSet(false, true)) {
            System.out.println("Alpaca news WebSocket start skipped: connection attempt already active.");
            return;
        }

        long generation = socketGeneration.incrementAndGet();
        connectionState = ConnectionState.CONNECTING;
        lastMessageAt = System.currentTimeMillis();

        Request.Builder requestBuilder =
                new Request.Builder()
                        .url(NEWS_STREAM_URL);

        if (!authenticateWithMessage) {
            requestBuilder
                    .addHeader("APCA-API-KEY-ID", apiKey)
                    .addHeader("APCA-API-SECRET-KEY", secretKey);
        }

        Request request = requestBuilder.build();

        WebSocket createdSocket =
                client.newWebSocket(
                        request,
                        new WebSocketListener() {

                            @Override
                            public void onOpen(WebSocket webSocket, Response response) {
                                if (!isCurrentSocket(generation, webSocket)) {
                                    webSocket.close(1000, "stale socket generation");
                                    return;
                                }
                                connected = true;
                                authenticated = !authenticateWithMessage;
                                subscribed = false;
                                authMessageSent = false;
                                subscribeMessageSent = false;
                                connectionState = authenticateWithMessage ? ConnectionState.CONNECTED : ConnectionState.AUTHENTICATED;
                                connecting.set(false);
                                reconnectScheduled.set(false);
                                reconnectAttempts = 0;
                                lastMessageAt = System.currentTimeMillis();
                                System.out.println("Alpaca news WebSocket connected.");
                                System.out.println("Waiting for new pushed news articles...");

                                if (authenticateWithMessage) {
                                    authenticate(webSocket);
                                } else {
                                    subscribeToAllNews(webSocket);
                                }
                                startHeartbeat();
                            }

                            @Override
                            public void onMessage(WebSocket webSocket, String text) {
                                if (!isCurrentSocket(generation, webSocket)) {
                                    return;
                                }
                                lastMessageAt = System.currentTimeMillis();
                                handleMessage(text);
                            }

                            @Override
                            public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
                                if (!isCurrentSocket(generation, webSocket)) {
                                    return;
                                }
                                connected = false;
                                authenticated = false;
                                subscribed = false;
                                authMessageSent = false;
                                subscribeMessageSent = false;
                                connectionState = ConnectionState.DISCONNECTED;
                                connecting.set(false);
                                String detail = throwable == null || throwable.getMessage() == null
                                        ? "connection closed without detail"
                                        : throwable.getMessage();
                                System.err.println(
                                        "Alpaca news WebSocket failure: " + detail
                                );

                                if (running) {
                                    scheduleReconnect(response == null ? 0 : response.code(), detail);
                                }
                            }

                            @Override
                            public void onClosed(WebSocket webSocket, int code, String reason) {
                                if (!isCurrentSocket(generation, webSocket)) {
                                    return;
                                }
                                connected = false;
                                authenticated = false;
                                subscribed = false;
                                authMessageSent = false;
                                subscribeMessageSent = false;
                                connectionState = ConnectionState.DISCONNECTED;
                                connecting.set(false);
                                System.out.println(
                                        "Alpaca news WebSocket closed: " +
                                                code +
                                                " " +
                                                reason
                                );

                                if (running && code != 1000) {
                                    scheduleReconnect(code, reason);
                                }
                            }
                        }
                );

        webSocket = createdSocket;
        System.out.println("Alpaca news WebSocket started.");
    }

    public synchronized void stop() {
        running = false;
        connected = false;
        authenticated = false;
        subscribed = false;
        authMessageSent = false;
        subscribeMessageSent = false;
        connectionState = ConnectionState.CLOSED;
        connecting.set(false);
        reconnectScheduled.set(false);
        socketGeneration.incrementAndGet();

        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try {
                socket.close(
                        1000,
                        "Stopped by bot"
                );
            } catch (Exception ignored) {
            }
            try {
                socket.cancel();
            } catch (Exception ignored) {
            }
        }
        try {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        } catch (Exception ignored) {
        } finally {
            releaseGlobalStreamPermit();
        }
    }

    private synchronized boolean acquireGlobalStreamPermit() {
        if (ownsGlobalPermit) {
            return true;
        }

        synchronized (GLOBAL_STREAM_LOCK_MONITOR) {
            if (!JVM_STREAM_ACTIVE.compareAndSet(false, true)) {
                return false;
            }

            Path lockPath = alpacaNewsLockPath();
            try {
                Path parent = lockPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                globalLockChannel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                );
                globalFileLock = globalLockChannel.tryLock();
                if (globalFileLock == null) {
                    closeGlobalLockResources();
                    JVM_STREAM_ACTIVE.set(false);
                    return false;
                }
                globalLockChannel.truncate(0);
                globalLockChannel.position(0);
                String owner = "pid=" + currentPid() + " startedAt=" + Instant.now() + System.lineSeparator();
                globalLockChannel.write(StandardCharsets.UTF_8.encode(owner));
                globalLockChannel.force(true);
                ownsGlobalPermit = true;
                System.out.println("ALPACA NEWS WS GLOBAL OWNER ACQUIRED: " + lockPath);
                return true;
            } catch (Exception e) {
                closeGlobalLockResources();
                JVM_STREAM_ACTIVE.set(false);
                System.err.println(
                        "ALPACA NEWS WS GLOBAL OWNER CHECK FAILED: " + e.getMessage() +
                                "; continuing with JVM-local single-stream guard only."
                );
                if (!JVM_STREAM_ACTIVE.compareAndSet(false, true)) {
                    return false;
                }
                ownsGlobalPermit = true;
                return true;
            }
        }
    }

    private synchronized void releaseGlobalStreamPermit() {
        if (!ownsGlobalPermit) {
            return;
        }
        closeGlobalLockResources();
        ownsGlobalPermit = false;
        JVM_STREAM_ACTIVE.set(false);
    }

    private void closeGlobalLockResources() {
        try {
            if (globalFileLock != null && globalFileLock.isValid()) {
                globalFileLock.release();
            }
        } catch (Exception ignored) {
        } finally {
            globalFileLock = null;
        }
        try {
            if (globalLockChannel != null) {
                globalLockChannel.close();
            }
        } catch (Exception ignored) {
        } finally {
            globalLockChannel = null;
        }
    }

    private Path alpacaNewsLockPath() {
        String configured = System.getenv("ALPACA_NEWS_WS_LOCK_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of("logs", "alpaca_news_ws.lock");
    }

    private String currentPid() {
        try {
            return ManagementFactory.getRuntimeMXBean().getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public void handleRawMessageForTest(String text) {
        lastMessageAt = System.currentTimeMillis();
        handleMessage(text);
    }

    private synchronized void authenticate(WebSocket webSocket) {
        if (webSocket == null || !connected) {
            return;
        }
        if (!authenticateWithMessage) {
            authenticated = true;
            connectionState = ConnectionState.AUTHENTICATED;
            subscribeToAllNews(webSocket);
            return;
        }
        if (authMessageSent || authenticated || connectionState.ordinal() >= ConnectionState.AUTH_SENT.ordinal()) {
            System.out.println("Alpaca news WebSocket auth skipped: state=" + connectionState + " authenticated=" + authenticated);
            return;
        }

        String authMessage =
                "{\"action\":\"auth\",\"key\":\"" + escapeJson(apiKey) + "\",\"secret\":\"" + escapeJson(secretKey) + "\"}";

        if (webSocket.send(authMessage)) {
            authMessageSent = true;
            connectionState = ConnectionState.AUTH_SENT;
            System.out.println("Alpaca news WebSocket auth message sent.");
        } else {
            System.err.println("Failed to send Alpaca news auth message; reconnecting.");
            forceReconnect("auth send failed");
        }
    }

    private synchronized void subscribeToAllNews(WebSocket webSocket) {
        if (webSocket == null || !connected) {
            return;
        }
        if (!authenticated) {
            System.out.println("Alpaca news subscription delayed: not authenticated yet. state=" + connectionState);
            return;
        }
        if (subscribeMessageSent || subscribed || connectionState == ConnectionState.SUBSCRIBED) {
            System.out.println("Alpaca news subscription skipped: already sent/subscribed. state=" + connectionState);
            return;
        }

        String subscribeMessage =
                "{\"action\":\"subscribe\",\"news\":[\"*\"]}";

        if (webSocket.send(subscribeMessage)) {
            subscribeMessageSent = true;
            subscribed = true;
            connectionState = ConnectionState.SUBSCRIBED;
            System.out.println("Subscribed to all Alpaca news.");
        } else {
            System.err.println("Failed to send Alpaca news subscription message; reconnecting.");
            forceReconnect("subscription send failed");
        }
    }

    private void startHeartbeat() {
        if (!heartbeatStarted.compareAndSet(false, true)) {
            return;
        }
        Thread heartbeatThread =
                new Thread(() -> {
                    while (running) {
                        try {
                            Thread.sleep(HEARTBEAT_INTERVAL_MS);

                            long now =
                                    System.currentTimeMillis();

                            long secondsSinceMessage =
                                    (now - lastMessageAt) / 1000L;

                            long secondsSinceNews =
                                    lastNewsAt <= 0
                                            ? -1L
                                            : (now - lastNewsAt) / 1000L;

                            System.out.println(
                                    "NEWS WS HEARTBEAT: connected=" + connected +
                                            " secondsSinceLastMessage=" +
                                            secondsSinceMessage +
                                            " secondsSinceLastNews=" +
                                            (secondsSinceNews < 0 ? "none" : secondsSinceNews) +
                                            " reconnectAttempts=" + reconnectAttempts
                            );

                            long staleSeconds = envLong("ALPACA_NEWS_WS_STALE_SECONDS", 600L);
                            int requiredStaleHeartbeats = (int) envLong("ALPACA_NEWS_WS_STALE_MISSES", 3L);
                            boolean streamQuietButHealthy = connected && authenticated && subscribed && connectionState == ConnectionState.SUBSCRIBED;
                            if (connected && secondsSinceMessage > staleSeconds) {
                                consecutiveStaleHeartbeats++;
                                System.err.println("Alpaca news WebSocket quiet/stale check: secondsSinceLastMessage=" +
                                        secondsSinceMessage + " staleSeconds=" + staleSeconds +
                                        " staleMisses=" + consecutiveStaleHeartbeats + "/" + requiredStaleHeartbeats +
                                        " subscribed=" + streamQuietButHealthy);
                                if (consecutiveStaleHeartbeats >= requiredStaleHeartbeats) {
                                    System.err.println("Alpaca news WebSocket appears genuinely stale after repeated misses. Reconnecting with state preservation.");
                                    consecutiveStaleHeartbeats = 0;
                                    forceReconnect("stale heartbeat");
                                }
                            } else {
                                consecutiveStaleHeartbeats = 0;
                            }

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            running = false;
                        }
                    }
                });

        heartbeatThread.setName("alpaca-news-websocket-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void handleMessage(String text) {
        consecutiveStaleHeartbeats = 0;
        try {
            JsonNode json =
                    mapper.readTree(text);

            if (json.isArray()) {
                for (JsonNode node : json) {
                    handleNode(node);
                }
            } else {
                handleNode(json);
            }

        } catch (Exception e) {
            System.err.println(
                    "Failed to parse Alpaca news WebSocket message: " +
                            e.getMessage()
            );
        }
    }

    private void handleNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }

        String type =
                node.path("T").asText();

        if ("success".equalsIgnoreCase(type)) {
            System.out.println("NEWS WS MESSAGE: " + node);
            String msg = node.path("msg").asText("");
            if ("authenticated".equalsIgnoreCase(msg)) {
                authenticated = true;
                authMessageSent = true;
                if (connectionState != ConnectionState.SUBSCRIBED) {
                    connectionState = ConnectionState.AUTHENTICATED;
                }
                WebSocket socket = webSocket;
                if (socket != null && connected && !subscribed && !subscribeMessageSent) {
                    subscribeToAllNews(socket);
                }
            }
            return;
        }

        if ("subscription".equalsIgnoreCase(type)) {
            subscribed = true;
            subscribeMessageSent = true;
            connectionState = ConnectionState.SUBSCRIBED;
            System.out.println("NEWS WS MESSAGE: " + node);
            return;
        }

        if ("error".equalsIgnoreCase(type)) {
            handleStreamError(node);
            return;
        }

        if (!"n".equalsIgnoreCase(type)) {
            if (!node.isMissingNode()) {
                System.out.println("NEWS WS CONTROL MESSAGE IGNORED: " + node);
            }
            return;
        }

        lastNewsAt =
                System.currentTimeMillis();

        String articleId =
                node.path("id").asText();

        String headline =
                node.path("headline").asText();

        String summary =
                node.path("summary").asText();

        String content =
                node.path("content").asText();

        String createdAt =
                firstNonBlank(
                        node.path("created_at").asText(),
                        node.path("updated_at").asText(),
                        node.path("published_at").asText(),
                        node.path("createdAt").asText(),
                        node.path("updatedAt").asText()
                );

        JsonNode symbols =
                node.path("symbols");

        if (!symbols.isArray() || symbols.isEmpty()) {
            return;
        }

        for (JsonNode symbolNode : symbols) {
            String ticker =
                    symbolNode.asText();

            if (!isAllowedStockSymbol(ticker)) {
                System.out.println(
                        "NEWS WS SYMBOL SKIPPED: " +
                                ticker +
                                " reason=NOT_SUPPORTED_STOCK_SYMBOL"
                );
                continue;
            }

            String uniqueId =
                    articleId + ":" + ticker;

            synchronized (seenNewsIds) {
                if (!seenNewsIds.add(uniqueId)) {
                    continue;
                }
                if (seenNewsIds.size() > envInt("ALPACA_NEWS_SEEN_ID_MAX", 25_000)) {
                    seenNewsIds.clear();
                }
            }

            long providerTimestamp =
                    parseTimestamp(createdAt);

            NewsEvent news =
                    new NewsEvent(
                            uniqueId,
                            ticker,
                            headline,
                            content == null || content.isBlank() ? summary : content,
                            providerTimestamp
                    );

            news.setSource("ALPACA_NEWS_WS");
            news.setProviderTimestamp(providerTimestamp);
            news.setBotFirstSeenAt(System.currentTimeMillis());

            System.out.println(
                    "ALPACA LIVE NEWS RECEIVED: " +
                            ticker +
                            " providerAgeMs=" +
                            Math.max(0L, System.currentTimeMillis() - providerTimestamp) +
                            " | " +
                            headline
            );

            newsHandler.accept(news);
        }
    }

    private boolean isAllowedStockSymbol(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }

        String normalized =
                ticker.trim().toUpperCase();

        if (normalized.endsWith("USD")) {
            return false;
        }

        if (normalized.contains("/") || normalized.contains(":") || normalized.contains(".")) {
            return false;
        }

        if (normalized.length() < 1 || normalized.length() > 5) {
            return false;
        }

        for (int i = 0; i < normalized.length(); i++) {
            if (!Character.isLetter(normalized.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    private void handleStreamError(JsonNode node) {
        int code = node.path("code").asInt(0);
        String message = node.path("msg").asText(node.toString());
        System.err.println("ALPACA NEWS WS ERROR: code=" + code + " msg=" + message);

        String lowerMessage = message == null ? "" : message.toLowerCase();

        if (code == 403 && lowerMessage.contains("already authenticated")) {
            authenticated = true;
            authMessageSent = true;
            if (connectionState != ConnectionState.SUBSCRIBED) {
                connectionState = ConnectionState.AUTHENTICATED;
            }
            System.err.println("ALPACA NEWS WS already-authenticated notice ignored; preserving active stream. state=" + connectionState);
            WebSocket socket = webSocket;
            if (socket != null && connected && !subscribed && !subscribeMessageSent) {
                subscribeToAllNews(socket);
            }
            return;
        }

        if (code == 401 || code == 402 || code == 406 ||
                (code == 403 && !lowerMessage.contains("already authenticated")) ||
                (lowerMessage.contains("auth") && !lowerMessage.contains("already authenticated"))) {
            disabledForSession = true;
            running = false;
            System.err.println("ALPACA NEWS WS disabled for this session after non-recoverable error. REST news polling remains active as backup.");
            stop();
            return;
        }

        forceReconnect("stream error " + code + ": " + message);
    }

    private void forceReconnect(String reason) {
        WebSocket socketToClose = webSocket;
        if (socketToClose != null) {
            try {
                socketToClose.close(1001, reason == null ? "forced reconnect" : reason);
            } catch (Exception ignored) {
            }
        }
        connected = false;
        authenticated = false;
        subscribed = false;
        authMessageSent = false;
        subscribeMessageSent = false;
        connectionState = ConnectionState.DISCONNECTED;
        connecting.set(false);
        scheduleReconnect(0, reason);
    }

    private void scheduleReconnect(int responseCode, String reason) {
        if (!running || disabledForSession) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            System.out.println("Alpaca news WebSocket reconnect skipped: reconnect already scheduled. reason=" + reason);
            return;
        }
        long now = System.currentTimeMillis();
        long delay = reconnectDelayMillis(responseCode);
        if (now < nextReconnectAllowedAt) {
            delay = Math.max(delay, nextReconnectAllowedAt - now);
        }
        reconnectAttempts++;
        nextReconnectAllowedAt = now + delay;
        final long reconnectDelay = delay;
        Thread reconnectThread = new Thread(() -> {
            try {
                System.out.println("Reconnecting Alpaca news WebSocket in " + (reconnectDelay / 1_000L) + " seconds... attempt=" + reconnectAttempts + " reason=" + reason);
                Thread.sleep(reconnectDelay);
                if (running && !disabledForSession) {
                    start();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } finally {
                reconnectScheduled.set(false);
            }
        });
        reconnectThread.setName("alpaca-news-websocket-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private boolean isCurrentSocket(long generation, WebSocket socket) {
        // Do not compare against the webSocket field here. OkHttp can invoke onOpen/onFailure
        // before client.newWebSocket(...) has returned and before the field assignment below
        // completes. The previous implementation treated that valid callback as stale and
        // closed the socket immediately, causing an endless connect/close/reconnect loop in
        // IntelliJ. The generation check is sufficient because every intentional new socket
        // increments socketGeneration before the request is created.
        return running && generation == socketGeneration.get() && socket != null;
    }

    private long reconnectDelayMillis(int responseCode) {
        long base = responseCode == 429 ? 60_000L : envLong("ALPACA_NEWS_WS_RECONNECT_BASE_MS", 5_000L);
        long max = responseCode == 429 ? 300_000L : envLong("ALPACA_NEWS_WS_RECONNECT_MAX_MS", 60_000L);
        int exp = Math.min(6, Math.max(0, reconnectAttempts - 1));
        long delay = Math.min(max, base * (1L << exp));
        long jitter = Math.abs((long) (Math.random() * Math.min(5_000L, delay / 3 + 1)));
        return Math.max(1_000L, delay + jitter);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private long parseTimestamp(String value) {
        try {
            if (value == null || value.isBlank()) {
                return 0L;
            }

            return Instant.parse(value).toEpochMilli();

        } catch (Exception e) {
            return 0L;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return "";
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
}
