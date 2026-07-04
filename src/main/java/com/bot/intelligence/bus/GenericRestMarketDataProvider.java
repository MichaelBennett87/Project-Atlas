package com.bot.intelligence.bus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenericRestMarketDataProvider implements MarketDataProvider {
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\\"(?:ticker|symbol)\\\"\\s*:\\s*\\\"([A-Za-z.\\-]{1,12})\\\"");
    private static final Pattern TITLE_PATTERN = Pattern.compile("\\\"(?:headline|title|summary)\\\"\\s*:\\s*\\\"([^\\\"]{1,240})\\\"");

    private final String name;
    private final String url;
    private final String token;
    private final long pollMs;
    private final MarketIntelligenceSignalType type;
    private final HttpClient client;
    private volatile boolean running = false;

    public GenericRestMarketDataProvider(String name, String url, String token, long pollMs, MarketIntelligenceSignalType type) {
        this.name = normalize(name);
        this.url = url;
        this.token = token;
        this.pollMs = Math.max(5_000L, pollMs);
        this.type = type == null ? MarketIntelligenceSignalType.UNKNOWN : type;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() { return name; }

    @Override
    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    @Override
    public void start(Consumer<MarketIntelligenceSignal> signalConsumer) {
        if (!enabled()) {
            System.out.println("EXTERNAL DATA PROVIDER DISABLED: " + name + " reason=NO_URL_CONFIGURED");
            return;
        }
        running = true;
        Thread thread = new Thread(() -> loop(signalConsumer));
        thread.setName("market-intelligence-provider-" + name.toLowerCase());
        thread.setDaemon(true);
        thread.start();
        System.out.println("EXTERNAL DATA PROVIDER STARTED: " + name + " pollMs=" + pollMs + " urlConfigured=true");
    }

    @Override
    public void stop() {
        running = false;
    }

    private void loop(Consumer<MarketIntelligenceSignal> signalConsumer) {
        while (running) {
            try {
                poll(signalConsumer);
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception e) {
                MarketIntelligenceBus.getInstance().recordProviderStatus(name, "ERROR:" + e.getMessage());
                try {
                    Thread.sleep(Math.max(pollMs, 30_000L));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    private void poll(Consumer<MarketIntelligenceSignal> signalConsumer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .header("accept", "application/json");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            MarketIntelligenceBus.getInstance().recordProviderStatus(name, "HTTP_" + response.statusCode());
            return;
        }
        String body = response.body() == null ? "" : response.body();
        int emitted = emitSimpleSignals(body, signalConsumer);
        MarketIntelligenceBus.getInstance().recordProviderStatus(name, "OK emitted=" + emitted);
    }

    private int emitSimpleSignals(String body, Consumer<MarketIntelligenceSignal> signalConsumer) {
        if (body == null || body.isBlank() || signalConsumer == null) return 0;
        Matcher symbolMatcher = SYMBOL_PATTERN.matcher(body);
        Matcher titleMatcher = TITLE_PATTERN.matcher(body);
        int emitted = 0;
        while (symbolMatcher.find() && emitted < 100) {
            String symbol = symbolMatcher.group(1);
            String title = titleMatcher.find() ? titleMatcher.group(1) : name + " external signal";
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("provider", name);
            metadata.put("rawMode", "GENERIC_REST_JSON");
            signalConsumer.accept(new MarketIntelligenceSignal(
                    name,
                    type,
                    symbol,
                    title,
                    "",
                    System.currentTimeMillis(),
                    0.50,
                    0.35,
                    metadata
            ));
            emitted++;
        }
        return emitted;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase().replace(' ', '_');
    }
}
