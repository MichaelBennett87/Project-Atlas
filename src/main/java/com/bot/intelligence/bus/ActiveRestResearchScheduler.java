package com.bot.intelligence.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.bot.intelligence.MarketFeatureBus;
import com.bot.intelligence.MarketKnowledgeDatabase;
import com.bot.model.Bar;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Active request/response research scheduler for REST data providers.
 *
 * Polygon and Alpha Vantage do not behave like the Benzinga/Alpaca news WebSockets.
 * They must be actively asked specific questions. This class now exposes the full
 * flow in the console and journal:
 *
 *   Research Planner -> Priority Request Queue -> Provider HTTP Executor -> Cache -> Market Intelligence Bus
 *
 * Live trading mode is intentionally quota-aware and targeted. The after-hours
 * historical orchestrator remains responsible for heavy multi-day downloads.
 */
public final class ActiveRestResearchScheduler {
    private static final String POLYGON_BASE = "https://api.polygon.io";
    private static final String ALPHA_BASE = "https://www.alphavantage.co/query";
    private static final ZoneId MARKET_ZONE = ZoneId.of(envStatic("MARKET_TIME_ZONE", "America/New_York"));

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final MarketKnowledgeDatabase knowledgeDatabase = MarketKnowledgeDatabase.getInstance();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String polygonKey = env("POLYGON_API_KEY", "");
    private final String alphaKey = env("ALPHA_VANTAGE_API_KEY", "");
    private final Path journalPath = Path.of(env("ACTIVE_REST_RESEARCH_JOURNAL", "logs/active_rest_research_journal.csv"));
    private final Path cacheDir = Path.of(env("ACTIVE_REST_RESEARCH_CACHE_DIR", "logs/active_rest_research_cache"));

    private final Map<String, Long> lastRequestMs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAlphaOverviewMs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAlphaNewsMs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPolygonBarsMs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPolygonDetailsMs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPolygonNewsMs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPolygonTradesMs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPolygonQuotesMs = new ConcurrentHashMap<>();

    private final Set<String> invalidSymbols = ConcurrentHashMap.newKeySet();
    private final Set<String> unavailableCapabilities = ConcurrentHashMap.newKeySet();
    private final List<Long> polygonCallTimes = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<Long> alphaCallTimes = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile long polygonBackoffUntilMs = 0L;
    private volatile long alphaBackoffUntilMs = 0L;
    private volatile long alphaDailyResetDay = -1L;
    private volatile int alphaDailyCalls = 0;

    private volatile boolean running = false;
    private volatile Thread worker;

    public boolean enabled() {
        return envBoolean("ACTIVE_REST_RESEARCH_ENABLED", true) && (!polygonKey.isBlank() || !alphaKey.isBlank());
    }

    public synchronized void start(Consumer<MarketIntelligenceSignal> signalConsumer) {
        if (running) return;
        if (!enabled()) {
            MarketIntelligenceBus.getInstance().recordProviderStatus("ACTIVE_REST_RESEARCH", "DISABLED_OR_NO_API_KEYS");
            System.out.println("ACTIVE REST RESEARCH SCHEDULER DISABLED: set POLYGON_API_KEY and/or ALPHA_VANTAGE_API_KEY to enable active REST research requests.");
            return;
        }
        running = true;
        knowledgeDatabase.start();
        Consumer<MarketIntelligenceSignal> sink = signalConsumer == null ? MarketIntelligenceBus.getInstance()::publishSignal : signalConsumer;
        worker = new Thread(() -> loop(sink), "active-rest-research-scheduler");
        worker.setDaemon(true);
        worker.start();
        System.out.println("ACTIVE REST RESEARCH SCHEDULER STARTED: pollMs=" + pollMs() +
                " maxSymbols=" + maxSymbols() +
                " polygonEnabled=" + !polygonKey.isBlank() +
                " alphaVantageEnabled=" + !alphaKey.isBlank() +
                " cache=" + cacheDir +
                " journal=" + journalPath +
                " mode=ACTIVE_REQUEST_RESPONSE planner=true queue=true explicitHttpLogging=" + envBoolean("ACTIVE_REST_RESEARCH_CONSOLE_LOG", true) +
                " polygonPremiumMode=" + polygonPremiumMode() +
                " budgets=polygonPerMinute:" + polygonPerMinuteBudget() + ",polygonMaxCallsPerCycle:" + maxPolygonCallsPerCycle() + ",alphaPerDay:" + alphaDailyBudget() +
                " capabilityManager=true budgetManager=true symbolValidation=true fallbackPlanner=true" +
                " tradeQuoteEndpoints=" + (polygonTradesQuotesEnabled() ? "ENABLED" : "DISABLED_SET_POLYGON_ENABLE_TRADE_QUOTE_ENDPOINTS_TRUE") +
                " curiousResearch=true questionGenerator=true informationGain=true historicalPatternMiner=true analogueEngine=true knowledgeCache=true");
    }

    public synchronized void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private void loop(Consumer<MarketIntelligenceSignal> signalConsumer) {
        boolean first = true;
        while (running) {
            long started = System.currentTimeMillis();
            CycleStats stats = new CycleStats();
            try {
                List<String> symbols = selectSymbols();
                List<ResearchRequest> queue = buildResearchPlan(symbols);
                stats.symbols = symbols.size();
                stats.queued = queue.size();
                log("RESEARCH PLANNER: symbolsSelected=" + symbols.size() +
                        " queuedRequests=" + queue.size() +
                        " polygonRequests=" + queue.stream().filter(r -> r.provider == Provider.POLYGON).count() +
                        " alphaRequests=" + queue.stream().filter(r -> r.provider == Provider.ALPHA_VANTAGE).count() +
                        " top=" + preview(symbols, 10));

                List<ExecutedResearch> completed = executeQueue(queue, signalConsumer);
                for (ExecutedResearch executed : completed) {
                    ProviderResult r = executed.result;
                    stats.calls += r.calls;
                    stats.emitted += r.emitted;
                    stats.errors += r.errors;
                    if (executed.request.provider == Provider.POLYGON) stats.polygonCalls += r.calls;
                    if (executed.request.provider == Provider.ALPHA_VANTAGE) stats.alphaCalls += r.calls;
                }

                stats.elapsedMs = System.currentTimeMillis() - started;
                String status = "OK symbols=" + stats.symbols + " queued=" + stats.queued +
                        " calls=" + stats.calls + " polygonCalls=" + stats.polygonCalls +
                        " alphaCalls=" + stats.alphaCalls + " emitted=" + stats.emitted +
                        " errors=" + stats.errors + " elapsedMs=" + stats.elapsedMs;
                MarketIntelligenceBus.getInstance().recordProviderStatus("ACTIVE_REST_RESEARCH", status);
                journal("CYCLE", "ALL", "ALL", true, stats.symbols, stats.polygonCalls, stats.alphaCalls, stats.emitted, stats.errors, stats.elapsedMs, status);
                log("RESEARCH CYCLE COMPLETE: " + status);
            } catch (Exception e) {
                stats.errors++;
                stats.elapsedMs = System.currentTimeMillis() - started;
                String status = "ERROR:" + safe(e.getMessage());
                MarketIntelligenceBus.getInstance().recordProviderStatus("ACTIVE_REST_RESEARCH", status);
                journal("CYCLE", "ALL", "ALL", false, stats.symbols, stats.polygonCalls, stats.alphaCalls, stats.emitted, stats.errors, stats.elapsedMs, status);
                log("RESEARCH CYCLE ERROR: " + status);
            }
            long sleepMs = first
                    ? Math.max(5_000L, envLong("ACTIVE_REST_RESEARCH_FIRST_SLEEP_MS", pollMs()))
                    : AdaptiveProviderScheduler.nextSleepMs("ACTIVE_REST_RESEARCH", pollMs(), stats.emitted);
            first = false;
            sleep(sleepMs);
        }
    }

    private List<ResearchRequest> buildResearchPlan(List<String> symbols) {
        List<ResearchRequest> queue = new ArrayList<>();
        int maxPolygonCalls = Math.max(0, Math.min(maxPolygonCallsPerCycle(), remainingPolygonMinuteBudget()));
        int maxAlphaCalls = Math.max(0, Math.min(envInt("ACTIVE_REST_RESEARCH_MAX_ALPHA_CALLS", 1), remainingAlphaDailyBudget()));
        int polygonCalls = 0;
        int alphaCalls = 0;
        long now = System.currentTimeMillis();
        boolean polygonInBackoff = now < polygonBackoffUntilMs;
        boolean alphaInBackoff = now < alphaBackoffUntilMs;

        for (int i = 0; i < symbols.size(); i++) {
            String s = symbols.get(i);
            if (!isResearchableSymbol(s)) continue;
            MarketKnowledgeDatabase.Record local = knowledgeDatabase.snapshot(s);
            boolean hasFreshLocal = local != null && local.price > 0.0 && now - local.lastUpdatedMs <= envLong("ACTIVE_REST_RESEARCH_LOCAL_FRESH_MS", polygonPremiumMode() ? 8_000L : 60_000L);
            double basePriority = 1.0 - Math.min(0.95, i / Math.max(1.0, symbols.size()));

            // Snapshot is useful, but many Polygon tiers do not include it. If it 403s once,
            // the capability manager disables it and the planner falls back to aggregates/reference.
            if (!polygonInBackoff && !polygonKey.isBlank() && polygonCalls < maxPolygonCalls && capabilityAvailable(Provider.POLYGON, RequestType.SNAPSHOT)) {
                if (!hasFreshLocal && requestDue(Provider.POLYGON, RequestType.SNAPSHOT, s, envLong("ACTIVE_REST_RESEARCH_POLYGON_SNAPSHOT_REFRESH_MS", polygonPremiumMode() ? 15_000L : 300_000L), now)) {
                    queue.add(new ResearchRequest(Provider.POLYGON, RequestType.SNAPSHOT, s, 0.98 * basePriority));
                    polygonCalls++;
                }
            }
            if (!polygonInBackoff && !polygonKey.isBlank() && polygonCalls < maxPolygonCalls && capabilityAvailable(Provider.POLYGON, RequestType.BARS_1_MIN) && polygonBarsDue(s)) {
                queue.add(new ResearchRequest(Provider.POLYGON, RequestType.BARS_1_MIN, s, 0.90 * basePriority));
                polygonCalls++;
            }
            boolean hotSymbol = i < envInt("POLYGON_ORDER_FLOW_HOT_SYMBOLS", polygonPremiumMode() ? 120 : 15);
            if (hotSymbol && !polygonInBackoff && !polygonKey.isBlank() && polygonCalls < maxPolygonCalls && capabilityAvailable(Provider.POLYGON, RequestType.TRADES) && polygonTradesDue(s)) {
                queue.add(new ResearchRequest(Provider.POLYGON, RequestType.TRADES, s, 0.86 * basePriority));
                polygonCalls++;
            }
            if (hotSymbol && !polygonInBackoff && !polygonKey.isBlank() && polygonCalls < maxPolygonCalls && capabilityAvailable(Provider.POLYGON, RequestType.QUOTES) && polygonQuotesDue(s)) {
                queue.add(new ResearchRequest(Provider.POLYGON, RequestType.QUOTES, s, 0.84 * basePriority));
                polygonCalls++;
            }
            if (!polygonInBackoff && !polygonKey.isBlank() && polygonCalls < maxPolygonCalls && capabilityAvailable(Provider.POLYGON, RequestType.POLYGON_NEWS) && polygonNewsDue(s)) {
                queue.add(new ResearchRequest(Provider.POLYGON, RequestType.POLYGON_NEWS, s, 0.74 * basePriority));
                polygonCalls++;
            }
            if (!polygonInBackoff && !polygonKey.isBlank() && polygonCalls < maxPolygonCalls && capabilityAvailable(Provider.POLYGON, RequestType.REFERENCE_DETAILS) && polygonDetailsDue(s)) {
                queue.add(new ResearchRequest(Provider.POLYGON, RequestType.REFERENCE_DETAILS, s, 0.46 * basePriority));
                polygonCalls++;
            }
            if (!alphaInBackoff && !alphaKey.isBlank() && alphaCalls < maxAlphaCalls && capabilityAvailable(Provider.ALPHA_VANTAGE, RequestType.QUOTE)) {
                if (requestDue(Provider.ALPHA_VANTAGE, RequestType.QUOTE, s, envLong("ACTIVE_REST_RESEARCH_ALPHA_QUOTE_REFRESH_MS", 3_600_000L), now)) {
                    queue.add(new ResearchRequest(Provider.ALPHA_VANTAGE, RequestType.QUOTE, s, 0.54 * basePriority));
                    alphaCalls++;
                }
            }
            if (!alphaInBackoff && !alphaKey.isBlank() && alphaCalls < maxAlphaCalls && capabilityAvailable(Provider.ALPHA_VANTAGE, RequestType.OVERVIEW) && alphaOverviewDue(s)) {
                queue.add(new ResearchRequest(Provider.ALPHA_VANTAGE, RequestType.OVERVIEW, s, 0.42 * basePriority));
                alphaCalls++;
            }
            if (!alphaInBackoff && !alphaKey.isBlank() && alphaCalls < maxAlphaCalls && capabilityAvailable(Provider.ALPHA_VANTAGE, RequestType.NEWS_SENTIMENT) && alphaNewsDue(s)) {
                queue.add(new ResearchRequest(Provider.ALPHA_VANTAGE, RequestType.NEWS_SENTIMENT, s, 0.38 * basePriority));
                alphaCalls++;
            }
            if (polygonCalls >= maxPolygonCalls && alphaCalls >= maxAlphaCalls) break;
        }
        queue.sort(Comparator.comparingDouble((ResearchRequest r) -> r.priority).reversed());
        log("RESEARCH BUDGET: polygonRemainingThisMinute=" + remainingPolygonMinuteBudget() +
                " polygonQueued=" + polygonCalls +
                " alphaRemainingToday=" + remainingAlphaDailyBudget() +
                " alphaQueued=" + alphaCalls +
                " disabledCapabilities=" + unavailableCapabilities +
                " invalidSymbols=" + invalidSymbols.size());
        if (polygonPremiumMode()) {
            log("POLYGON WARM MARKET MODEL: hotSymbols=" + Math.min(symbols.size(), envInt("POLYGON_HOT_SYMBOLS", 100)) +
                    " warmSymbols=" + symbols.size() +
                    " queuedRequests=" + queue.size() +
                    " localReadModel=MarketKnowledgeDatabase->FeatureBus->WorldModel");
        }
        return queue;
    }

    private List<ExecutedResearch> executeQueue(List<ResearchRequest> queue, Consumer<MarketIntelligenceSignal> signalConsumer) {
        List<ExecutedResearch> completed = new ArrayList<>();
        if (queue == null || queue.isEmpty()) return completed;
        boolean async = envBoolean("POLYGON_PREMIUM_ASYNC_RESEARCH", polygonPremiumMode());
        int concurrency = Math.max(1, Math.min(envInt("POLYGON_RESEARCH_CONCURRENCY", polygonPremiumMode() ? 32 : 1), 96));
        if (!async || concurrency <= 1) {
            for (ResearchRequest request : queue) {
                if (!running) break;
                ProviderResult r = execute(request, signalConsumer);
                completed.add(new ExecutedResearch(request, r));
                if (envBoolean("ACTIVE_REST_RESEARCH_THROTTLE_BETWEEN_CALLS", !polygonPremiumMode())) {
                    sleep(Math.max(25L, envLong("ACTIVE_REST_RESEARCH_CALL_SPACING_MS", polygonPremiumMode() ? 50L : 12_000L)));
                }
            }
            return completed;
        }
        log("RESEARCH EXECUTION POOL: async=true concurrency=" + concurrency + " queued=" + queue.size() + " polygonPremiumMode=" + polygonPremiumMode());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread t = new Thread(runnable, "polygon-premium-research-worker");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<ExecutedResearch>> futures = new ArrayList<>();
            for (ResearchRequest request : queue) {
                if (!running) break;
                futures.add(pool.submit(() -> new ExecutedResearch(request, execute(request, signalConsumer))));
            }
            long timeoutMs = Math.max(5_000L, envLong("POLYGON_RESEARCH_CYCLE_TIMEOUT_MS", 45_000L));
            long deadline = System.currentTimeMillis() + timeoutMs;
            for (Future<ExecutedResearch> future : futures) {
                long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                try {
                    completed.add(future.get(remaining, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    future.cancel(true);
                    ProviderResult r = new ProviderResult();
                    r.errors++;
                    completed.add(new ExecutedResearch(new ResearchRequest(Provider.POLYGON, RequestType.SNAPSHOT, "UNKNOWN", 0.0), r));
                    log("RESEARCH EXECUTION TIMEOUT_OR_ERROR: " + safe(e.getMessage()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return completed;
    }

    private ProviderResult execute(ResearchRequest request, Consumer<MarketIntelligenceSignal> signalConsumer) {
        if (!isResearchableSymbol(request.symbol)) {
            invalidSymbols.add(request.symbol);
            log("RESEARCH REQUEST SKIPPED: invalidSymbol=" + request.symbol + " provider=" + request.provider + " type=" + request.type);
            return ProviderResult.empty();
        }
        if (!capabilityAvailable(request.provider, request.type)) {
            log("RESEARCH REQUEST SKIPPED: capabilityDisabled provider=" + request.provider + " type=" + request.type + " symbol=" + request.symbol);
            return ProviderResult.empty();
        }
        if (!reserveBudget(request.provider)) {
            log("RESEARCH REQUEST DEFERRED: budgetExhausted provider=" + request.provider + " type=" + request.type + " symbol=" + request.symbol);
            return ProviderResult.empty();
        }
        String key = request.provider + ":" + request.type + ":" + request.symbol;
        lastRequestMs.put(key, System.currentTimeMillis());
        if (request.provider == Provider.POLYGON) {
            switch (request.type) {
                case SNAPSHOT: return polygonSnapshot(request.symbol, signalConsumer);
                case BARS_1_MIN: return polygonRecentBars(request.symbol, signalConsumer);
                case TRADES: return polygonRecentTrades(request.symbol, signalConsumer);
                case QUOTES: return polygonRecentQuotes(request.symbol, signalConsumer);
                case REFERENCE_DETAILS: return polygonReferenceDetails(request.symbol, signalConsumer);
                case POLYGON_NEWS: return polygonNews(request.symbol, signalConsumer);
                default: return ProviderResult.empty();
            }
        }
        if (request.provider == Provider.ALPHA_VANTAGE) {
            switch (request.type) {
                case QUOTE: return alphaQuote(request.symbol, signalConsumer);
                case OVERVIEW: return alphaOverview(request.symbol, signalConsumer);
                case NEWS_SENTIMENT: return alphaNews(request.symbol, signalConsumer);
                default: return ProviderResult.empty();
            }
        }
        return ProviderResult.empty();
    }

    private ProviderResult polygonSnapshot(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            String endpoint = "/v2/snapshot/locale/us/markets/stocks/tickers/" + encPath(symbol);
            String url = POLYGON_BASE + endpoint + "?apiKey=" + enc(polygonKey);
            log("POLYGON REQUEST: endpoint=" + endpoint + " symbol=" + symbol + " type=SNAPSHOT");
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("polygon", symbol, "snapshot", root);
            JsonNode t = root.path("ticker");
            if (!t.isObject()) t = root.path("results");
            double price = number(t.path("lastTrade"), "p", number(t.path("min"), "c", number(t.path("day"), "c", 0.0)));
            double volume = max(
                    number(t.path("day"), "v", 0.0),
                    number(t.path("min"), "av", 0.0),
                    number(t.path("prevDay"), "v", 0.0),
                    number(t, "volume", 0.0),
                    number(t, "sessionVolume", 0.0));
            double changePct = number(t, "todaysChangePerc", number(t, "changePercent", 0.0));
            double high = number(t.path("day"), "h", 0.0);
            double low = number(t.path("day"), "l", 0.0);
            if (price <= 0.0 && volume <= 0.0) {
                log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=empty_snapshot cacheUpdated=true");
                journal("POLYGON", "SNAPSHOT", symbol, false, 1, 1, 0, 0, 0, response.elapsedMs, "empty snapshot");
                return result;
            }
            double priority = clamp(Math.min(1.0, Math.abs(changePct) / 15.0) * 0.45 + Math.min(1.0, Math.log10(Math.max(10.0, volume)) / 8.0) * 0.40 + intradayRangeScore(price, high, low) * 0.15);
            knowledgeDatabase.recordSnapshot(symbol, price, volume, changePct, high, low, "POLYGON_SNAPSHOT");
            HistoricalPatternMiner.getInstance().observe(knowledgeDatabase.snapshot(symbol));
            Map<String, String> m = new LinkedHashMap<>();
            m.put("provider", "POLYGON");
            m.put("rawMode", "ACTIVE_POLYGON_SNAPSHOT_BY_TICKER");
            m.put("requestMode", "ACTIVE_REST_REQUEST");
            m.put("endpoint", endpoint);
            m.put("price", fmt(price));
            m.put("volume", fmt(volume));
            m.put("changePct", fmt(changePct));
            m.put("dayHigh", fmt(high));
            m.put("dayLow", fmt(low));
            signalConsumer.accept(new MarketIntelligenceSignal("POLYGON", MarketIntelligenceSignalType.MARKET_DATA, symbol,
                    "Active Polygon snapshot: " + symbol + " change=" + fmt(changePct) + "% volume=" + Math.round(volume) + " price=" + fmt(price),
                    "", System.currentTimeMillis(), 0.72, priority, m));
            result.emitted++;
            log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=1 cacheUpdated=true price=" + fmt(price) + " volume=" + Math.round(volume));
            journal("POLYGON", "SNAPSHOT", symbol, true, 1, 1, 0, 1, 0, response.elapsedMs, "requested snapshot");
        } catch (Exception e) {
            result.errors++;
            MarketIntelligenceBus.getInstance().recordProviderStatus("POLYGON_RESEARCH", "ERROR_SNAPSHOT:" + safe(e.getMessage()));
            observeProviderError(Provider.POLYGON, RequestType.SNAPSHOT, symbol, e.getMessage());
            log("POLYGON RESPONSE: symbol=" + symbol + " type=SNAPSHOT success=false error=" + safe(e.getMessage()));
            journal("POLYGON", "SNAPSHOT", symbol, false, 1, 1, 0, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }

    private ProviderResult polygonRecentBars(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            LocalDate today = LocalDate.now(MARKET_ZONE);
            LocalDate from = today.minusDays(Math.max(1, envInt("ACTIVE_REST_RESEARCH_POLYGON_BAR_LOOKBACK_DAYS", 3)));
            String endpoint = "/v2/aggs/ticker/" + encPath(symbol) + "/range/1/minute/" + from + "/" + today;
            String url = POLYGON_BASE + endpoint + "?adjusted=true&sort=desc&limit=" + envInt("ACTIVE_REST_RESEARCH_POLYGON_BAR_LIMIT", polygonPremiumMode() ? 390 : 90) + "&apiKey=" + enc(polygonKey);
            log("POLYGON REQUEST: endpoint=" + endpoint + " symbol=" + symbol + " type=BARS_1_MIN");
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("polygon", symbol, "bars_1min", root);
            lastPolygonBarsMs.put(symbol, System.currentTimeMillis());
            JsonNode arr = root.path("results");
            if (!arr.isArray() || arr.size() < 2) {
                log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=no_bars cacheUpdated=true");
                journal("POLYGON", "BARS", symbol, false, 1, 1, 0, 0, 0, response.elapsedMs, "no bars");
                return result;
            }
            int count = Math.min(arr.size(), envInt("ACTIVE_REST_RESEARCH_POLYGON_BAR_LIMIT", polygonPremiumMode() ? 390 : 90));
            double latestClose = arr.get(0).path("c").asDouble(0.0);
            double oldestClose = arr.get(count - 1).path("c").asDouble(0.0);
            double high = 0.0;
            double low = Double.MAX_VALUE;
            double totalVol = 0.0;
            for (int i = 0; i < count; i++) {
                JsonNode b = arr.get(i);
                high = Math.max(high, b.path("h").asDouble(0.0));
                double l = b.path("l").asDouble(0.0);
                if (l > 0) low = Math.min(low, l);
                totalVol += b.path("v").asDouble(0.0);
            }
            publishPolygonBarsToFeatureBus(symbol, arr, count);
            if (low == Double.MAX_VALUE) low = 0.0;
            double returnPct = oldestClose > 0.0 ? ((latestClose - oldestClose) / oldestClose) * 100.0 : 0.0;
            double rangePct = latestClose > 0.0 && high > low ? ((high - low) / latestClose) * 100.0 : 0.0;
            double priority = clamp(Math.abs(returnPct) / 20.0 * 0.35 + rangePct / 20.0 * 0.35 + Math.min(1.0, Math.log10(Math.max(10.0, totalVol)) / 8.0) * 0.30);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("provider", "POLYGON");
            m.put("rawMode", "ACTIVE_POLYGON_MINUTE_AGGS");
            m.put("requestMode", "ACTIVE_REST_REQUEST");
            m.put("endpoint", endpoint);
            m.put("barCount", String.valueOf(count));
            m.put("returnPct", fmt(returnPct));
            m.put("rangePct", fmt(rangePct));
            m.put("volume", fmt(totalVol));
            m.put("latestClose", fmt(latestClose));
            knowledgeDatabase.recordBars(symbol, count, latestClose, returnPct, rangePct, totalVol, "POLYGON_BARS_1_MIN");
            HistoricalPatternMiner.getInstance().observe(knowledgeDatabase.snapshot(symbol));
            if (envBoolean("MARKET_ANALOGUE_ENGINE_ON_BAR_UPDATE", true)) {
                MarketAnalogueEngine.getInstance().nearestSymbols(symbol, envInt("MARKET_ANALOGUE_TOP_K", 8));
            }
            signalConsumer.accept(new MarketIntelligenceSignal("POLYGON", MarketIntelligenceSignalType.TECHNICAL_INDICATOR, symbol,
                    "Active Polygon bars: " + symbol + " bars=" + count + " return=" + fmt(returnPct) + "% range=" + fmt(rangePct) + "%",
                    "", System.currentTimeMillis(), 0.68, priority, m));
            result.emitted++;
            log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=1 cacheUpdated=true bars=" + count + " returnPct=" + fmt(returnPct));
            journal("POLYGON", "BARS", symbol, true, 1, 1, 0, 1, 0, response.elapsedMs, "requested aggregate bars");
        } catch (Exception e) {
            result.errors++;
            MarketIntelligenceBus.getInstance().recordProviderStatus("POLYGON_RESEARCH", "ERROR_BARS:" + safe(e.getMessage()));
            observeProviderError(Provider.POLYGON, RequestType.BARS_1_MIN, symbol, e.getMessage());
            log("POLYGON RESPONSE: symbol=" + symbol + " type=BARS_1_MIN success=false error=" + safe(e.getMessage()));
            journal("POLYGON", "BARS", symbol, false, 1, 1, 0, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }


    private ProviderResult polygonRecentTrades(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            String endpoint = "/v3/trades/" + encPath(symbol);
            String url = POLYGON_BASE + endpoint + "?order=desc&limit=" + envInt("POLYGON_TRADES_LIMIT", polygonPremiumMode() ? 250 : 50) + "&apiKey=" + enc(polygonKey);
            log("POLYGON REQUEST: endpoint=" + endpoint + " symbol=" + symbol + " type=TRADES");
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("polygon", symbol, "trades", root);
            lastPolygonTradesMs.put(symbol, System.currentTimeMillis());
            JsonNode arr = root.path("results");
            if (!arr.isArray() || arr.size() == 0) {
                log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=no_trades cacheUpdated=true");
                journal("POLYGON", "TRADES", symbol, false, 1, 1, 0, 0, 0, response.elapsedMs, "no trades");
                return result;
            }
            int count = arr.size();
            double totalSize = 0.0;
            double notional = 0.0;
            double upTicks = 0.0;
            double prev = 0.0;
            long minTs = Long.MAX_VALUE, maxTs = 0L;
            for (JsonNode t : arr) {
                double price = t.path("price").asDouble(t.path("p").asDouble(0.0));
                double size = t.path("size").asDouble(t.path("s").asDouble(0.0));
                long ts = t.path("sip_timestamp").asLong(t.path("participant_timestamp").asLong(0L));
                if (ts > 10_000_000_000_000L) ts = ts / 1_000_000L; // nanos -> millis proxy
                if (ts > 0) { minTs = Math.min(minTs, ts); maxTs = Math.max(maxTs, ts); }
                if (price > 0 && size > 0) { totalSize += size; notional += price * size; }
                if (prev > 0 && price > prev) upTicks++;
                if (price > 0) prev = price;
            }
            double avgSize = count > 0 ? totalSize / count : 0.0;
            double avgPrice = totalSize > 0 ? notional / totalSize : 0.0;
            double seconds = (maxTs > minTs && minTs != Long.MAX_VALUE) ? Math.max(1.0, (maxTs - minTs) / 1000.0) : 60.0;
            double tapeSpeed = clamp((count / seconds) / 30.0);
            double buyPressure = count > 1 ? clamp(upTicks / Math.max(1.0, count - 1.0)) : 0.5;
            knowledgeDatabase.recordOrderFlow(symbol, count, totalSize, avgSize, tapeSpeed, buyPressure, 0, 0.0, 0.0, "POLYGON_TRADES");
            Map<String,String> m = new LinkedHashMap<>();
            m.put("provider", "POLYGON");
            m.put("rawMode", "ACTIVE_POLYGON_TRADES");
            m.put("requestMode", "ACTIVE_REST_REQUEST");
            m.put("endpoint", endpoint);
            m.put("tradeCount", String.valueOf(count));
            m.put("tradeVolume", fmt(totalSize));
            m.put("avgTradeSize", fmt(avgSize));
            m.put("avgTradePrice", fmt(avgPrice));
            m.put("tapeSpeed", fmt(tapeSpeed));
            m.put("buyPressure", fmt(buyPressure));
            double priority = clamp(tapeSpeed * 0.45 + Math.min(1.0, Math.log10(Math.max(10.0, totalSize)) / 7.0) * 0.35 + Math.abs(buyPressure - 0.5) * 0.40);
            signalConsumer.accept(new MarketIntelligenceSignal("POLYGON", MarketIntelligenceSignalType.ORDER_FLOW, symbol,
                    "Active Polygon trades: " + symbol + " trades=" + count + " tape=" + fmt(tapeSpeed) + " pressure=" + fmt(buyPressure),
                    "", System.currentTimeMillis(), 0.70, priority, m));
            result.emitted++;
            log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=1 cacheUpdated=true trades=" + count + " tapeSpeed=" + fmt(tapeSpeed));
            journal("POLYGON", "TRADES", symbol, true, 1, 1, 0, 1, 0, response.elapsedMs, "requested trades");
        } catch (Exception e) {
            result.errors++;
            observeProviderError(Provider.POLYGON, RequestType.TRADES, symbol, e.getMessage());
            log("POLYGON RESPONSE: symbol=" + symbol + " type=TRADES success=false error=" + safe(e.getMessage()));
            journal("POLYGON", "TRADES", symbol, false, 1, 1, 0, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }

    private ProviderResult polygonRecentQuotes(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            String endpoint = "/v3/quotes/" + encPath(symbol);
            String url = POLYGON_BASE + endpoint + "?order=desc&limit=" + envInt("POLYGON_QUOTES_LIMIT", polygonPremiumMode() ? 250 : 50) + "&apiKey=" + enc(polygonKey);
            log("POLYGON REQUEST: endpoint=" + endpoint + " symbol=" + symbol + " type=QUOTES");
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("polygon", symbol, "quotes", root);
            lastPolygonQuotesMs.put(symbol, System.currentTimeMillis());
            JsonNode arr = root.path("results");
            if (!arr.isArray() || arr.size() == 0) {
                log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=no_quotes cacheUpdated=true");
                journal("POLYGON", "QUOTES", symbol, false, 1, 1, 0, 0, 0, response.elapsedMs, "no quotes");
                return result;
            }
            int count = arr.size();
            double spreadSum = 0.0, bpsSum = 0.0;
            int valid = 0;
            for (JsonNode q : arr) {
                double bid = q.path("bid_price").asDouble(q.path("bp").asDouble(0.0));
                double ask = q.path("ask_price").asDouble(q.path("ap").asDouble(0.0));
                if (bid > 0 && ask >= bid) {
                    double spread = ask - bid;
                    double mid = (ask + bid) / 2.0;
                    spreadSum += spread;
                    if (mid > 0) bpsSum += (spread / mid) * 10_000.0;
                    valid++;
                }
            }
            double avgSpread = valid > 0 ? spreadSum / valid : 0.0;
            double spreadBps = valid > 0 ? bpsSum / valid : 0.0;
            knowledgeDatabase.recordOrderFlow(symbol, 0, 0.0, 0.0, 0.0, 0.5, count, avgSpread, spreadBps, "POLYGON_QUOTES");
            Map<String,String> m = new LinkedHashMap<>();
            m.put("provider", "POLYGON");
            m.put("rawMode", "ACTIVE_POLYGON_QUOTES");
            m.put("requestMode", "ACTIVE_REST_REQUEST");
            m.put("endpoint", endpoint);
            m.put("quoteCount", String.valueOf(count));
            m.put("avgSpread", fmt(avgSpread));
            m.put("spreadBps", fmt(spreadBps));
            double priority = clamp(Math.max(0.0, 1.0 - spreadBps / 120.0) * 0.60 + Math.min(1.0, count / 250.0) * 0.40);
            signalConsumer.accept(new MarketIntelligenceSignal("POLYGON", MarketIntelligenceSignalType.ORDER_FLOW, symbol,
                    "Active Polygon quotes: " + symbol + " quotes=" + count + " spreadBps=" + fmt(spreadBps),
                    "", System.currentTimeMillis(), 0.70, priority, m));
            result.emitted++;
            log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=1 cacheUpdated=true quotes=" + count + " spreadBps=" + fmt(spreadBps));
            journal("POLYGON", "QUOTES", symbol, true, 1, 1, 0, 1, 0, response.elapsedMs, "requested quotes");
        } catch (Exception e) {
            result.errors++;
            observeProviderError(Provider.POLYGON, RequestType.QUOTES, symbol, e.getMessage());
            log("POLYGON RESPONSE: symbol=" + symbol + " type=QUOTES success=false error=" + safe(e.getMessage()));
            journal("POLYGON", "QUOTES", symbol, false, 1, 1, 0, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }


    private void publishPolygonBarsToFeatureBus(String symbol, JsonNode arr, int count) {
        if (!envBoolean("POLYGON_RESEARCH_PUBLISH_BARS_TO_FEATURE_BUS", true)) return;
        try {
            int emitted = 0;
            // Polygon returns sort=desc here, but the technical store works best when older bars arrive first.
            for (int i = count - 1; i >= 0; i--) {
                JsonNode b = arr.get(i);
                double close = b.path("c").asDouble(0.0);
                if (close <= 0.0) continue;
                Bar bar = new Bar();
                bar.ticker = symbol;
                bar.timestamp = b.path("t").asLong(System.currentTimeMillis());
                bar.open = b.path("o").asDouble(close);
                bar.high = b.path("h").asDouble(close);
                bar.low = b.path("l").asDouble(close);
                bar.close = close;
                bar.volume = Math.max(0L, Math.round(b.path("v").asDouble(0.0)));
                MarketFeatureBus.getInstance().publishBar("POLYGON_ACTIVE_RESEARCH", symbol, bar);
                emitted++;
            }
            if (emitted > 0 && envBoolean("POLYGON_RESEARCH_FEATURE_BUS_LOG", true)) {
                log("POLYGON FEATURE BUS ENRICHED: symbol=" + symbol + " barsPublished=" + emitted + " source=activeResearch");
            }
        } catch (Exception e) {
            log("POLYGON FEATURE BUS ENRICH FAILED: symbol=" + symbol + " error=" + safe(e.getMessage()));
        }
    }


    private ProviderResult polygonNews(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            int limit = Math.max(1, Math.min(50, envInt("ACTIVE_REST_RESEARCH_POLYGON_NEWS_LIMIT", polygonPremiumMode() ? 10 : 3)));
            String endpoint = "/v2/reference/news";
            String url = POLYGON_BASE + endpoint + "?ticker=" + enc(symbol) + "&limit=" + limit + "&order=desc&sort=published_utc&apiKey=" + enc(polygonKey);
            log("POLYGON REQUEST: endpoint=" + endpoint + " symbol=" + symbol + " type=POLYGON_NEWS limit=" + limit);
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("polygon", symbol, "news", root);
            lastPolygonNewsMs.put(symbol, System.currentTimeMillis());
            JsonNode arr = root.path("results");
            if (!arr.isArray() || arr.size() == 0) {
                log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=no_news cacheUpdated=true");
                journal("POLYGON", "NEWS", symbol, true, 1, 1, 0, 0, 0, response.elapsedMs, "no news");
                return result;
            }
            int emittedForSymbol = 0;
            int maxSignals = Math.max(1, Math.min(limit, envInt("ACTIVE_REST_RESEARCH_POLYGON_MAX_NEWS_SIGNALS_PER_SYMBOL", polygonPremiumMode() ? 5 : 2)));
            for (JsonNode item : arr) {
                if (emittedForSymbol >= maxSignals) break;
                String title = item.path("title").asText("");
                String description = item.path("description").asText("");
                long ts = parsePolygonTime(item.path("published_utc").asText(""));
                double priority = headlinePriority(title + " " + description);
                Map<String, String> m = new LinkedHashMap<>();
                m.put("provider", "POLYGON");
                m.put("rawMode", "ACTIVE_POLYGON_NEWS");
                m.put("requestMode", "ACTIVE_REST_REQUEST");
                m.put("endpoint", endpoint);
                m.put("publisher", item.path("publisher").path("name").asText(""));
                String articleUrl = item.path("article_url").asText("");
                String publishedUtc = item.path("published_utc").asText("");
                m.put("articleUrl", articleUrl);
                m.put("publishedUtc", publishedUtc);
                knowledgeDatabase.recordNews(symbol, title, publishedUtc, priority, articleUrl, "POLYGON_NEWS");
                signalConsumer.accept(new MarketIntelligenceSignal("POLYGON", MarketIntelligenceSignalType.NEWS, symbol,
                        title.isBlank() ? "Active Polygon news" : title,
                        description, ts, 0.67, priority, m));
                result.emitted++;
                emittedForSymbol++;
            }
            log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=" + emittedForSymbol + " cacheUpdated=true newsItems=" + arr.size());
            journal("POLYGON", "NEWS", symbol, true, 1, 1, 0, emittedForSymbol, 0, response.elapsedMs, "requested ticker news");
        } catch (Exception e) {
            result.errors++;
            observeProviderError(Provider.POLYGON, RequestType.POLYGON_NEWS, symbol, e.getMessage());
            log("POLYGON RESPONSE: symbol=" + symbol + " type=POLYGON_NEWS success=false error=" + safe(e.getMessage()));
            journal("POLYGON", "NEWS", symbol, false, 1, 1, 0, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }

    private ProviderResult polygonReferenceDetails(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            String endpoint = "/v3/reference/tickers/" + encPath(symbol);
            String url = POLYGON_BASE + endpoint + "?apiKey=" + enc(polygonKey);
            log("POLYGON REQUEST: endpoint=" + endpoint + " symbol=" + symbol + " type=REFERENCE_DETAILS");
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("polygon", symbol, "reference_details", root);
            lastPolygonDetailsMs.put(symbol, System.currentTimeMillis());
            JsonNode r = root.path("results");
            if (!r.isObject()) {
                log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=no_reference cacheUpdated=true");
                return result;
            }
            String name = r.path("name").asText("");
            String market = r.path("market").asText("");
            String type = r.path("type").asText("");
            double marketCap = r.path("market_cap").asDouble(0.0);
            double shares = r.path("share_class_shares_outstanding").asDouble(0.0);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("provider", "POLYGON");
            m.put("rawMode", "ACTIVE_POLYGON_REFERENCE_DETAILS");
            m.put("requestMode", "ACTIVE_REST_REQUEST");
            m.put("endpoint", endpoint);
            m.put("name", name);
            m.put("market", market);
            m.put("securityType", type);
            m.put("marketCap", fmt(marketCap));
            m.put("sharesOutstanding", fmt(shares));
            knowledgeDatabase.recordReference(symbol, name, market, type, marketCap, shares, "POLYGON_REFERENCE_DETAILS");
            double priority = clamp((marketCap > 0 ? 0.35 : 0.0) + (!type.isBlank() ? 0.20 : 0.0) + (!market.isBlank() ? 0.15 : 0.0) + 0.25);
            signalConsumer.accept(new MarketIntelligenceSignal("POLYGON", MarketIntelligenceSignalType.FUNDAMENTAL, symbol,
                    "Active Polygon reference: " + symbol + " type=" + type + " market=" + market + " marketCap=" + Math.round(marketCap),
                    name, System.currentTimeMillis(), 0.60, priority, m));
            result.emitted++;
            log("POLYGON RESPONSE: endpoint=" + endpoint + " symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=1 cacheUpdated=true type=" + type);
            journal("POLYGON", "REFERENCE_DETAILS", symbol, true, 1, 1, 0, 1, 0, response.elapsedMs, "requested reference details");
        } catch (Exception e) {
            result.errors++;
            observeProviderError(Provider.POLYGON, RequestType.REFERENCE_DETAILS, symbol, e.getMessage());
            log("POLYGON RESPONSE: symbol=" + symbol + " type=REFERENCE_DETAILS success=false error=" + safe(e.getMessage()));
            journal("POLYGON", "REFERENCE_DETAILS", symbol, false, 1, 1, 0, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }

    private ProviderResult alphaQuote(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            String url = ALPHA_BASE + "?function=GLOBAL_QUOTE&symbol=" + enc(symbol) + "&apikey=" + enc(alphaKey);
            log("ALPHA VANTAGE REQUEST: function=GLOBAL_QUOTE symbol=" + symbol);
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("alpha_vantage", symbol, "quote", root);
            if (alphaInformational(root)) {
                observeAlphaInfo(RequestType.QUOTE, symbol, alphaMessage(root));
                log("ALPHA VANTAGE RESPONSE: function=GLOBAL_QUOTE symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 note=" + alphaMessage(root));
                journal("ALPHA_VANTAGE", "QUOTE", symbol, false, 1, 0, 1, 0, 0, response.elapsedMs, alphaMessage(root));
                return result;
            }
            JsonNode q = root.path("Global Quote");
            double price = parseDouble(q.path("05. price").asText("0"));
            double changePct = parsePercent(q.path("10. change percent").asText("0"));
            double volume = parseDouble(q.path("06. volume").asText("0"));
            if (price <= 0.0) {
                log("ALPHA VANTAGE RESPONSE: function=GLOBAL_QUOTE symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=empty_quote cacheUpdated=true");
                return result;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("provider", "ALPHA_VANTAGE");
            m.put("rawMode", "ACTIVE_ALPHA_GLOBAL_QUOTE");
            m.put("requestMode", "ACTIVE_REST_REQUEST");
            m.put("function", "GLOBAL_QUOTE");
            m.put("price", fmt(price));
            m.put("changePct", fmt(changePct));
            m.put("volume", fmt(volume));
            double priority = clamp(Math.abs(changePct) / 12.0 * 0.55 + Math.min(1.0, Math.log10(Math.max(10.0, volume)) / 8.0) * 0.45);
            knowledgeDatabase.recordSnapshot(symbol, price, volume, changePct, 0.0, 0.0, "ALPHA_VANTAGE_QUOTE");
            signalConsumer.accept(new MarketIntelligenceSignal("ALPHA_VANTAGE", MarketIntelligenceSignalType.MARKET_DATA, symbol,
                    "Active Alpha Vantage quote: " + symbol + " change=" + fmt(changePct) + "% volume=" + Math.round(volume) + " price=" + fmt(price),
                    "", System.currentTimeMillis(), 0.62, priority, m));
            result.emitted++;
            log("ALPHA VANTAGE RESPONSE: function=GLOBAL_QUOTE symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=1 cacheUpdated=true price=" + fmt(price));
            journal("ALPHA_VANTAGE", "QUOTE", symbol, true, 1, 0, 1, 1, 0, response.elapsedMs, "requested global quote");
        } catch (Exception e) {
            result.errors++;
            MarketIntelligenceBus.getInstance().recordProviderStatus("ALPHA_VANTAGE_RESEARCH", "ERROR_QUOTE:" + safe(e.getMessage()));
            log("ALPHA VANTAGE RESPONSE: function=GLOBAL_QUOTE symbol=" + symbol + " success=false error=" + safe(e.getMessage()));
            journal("ALPHA_VANTAGE", "QUOTE", symbol, false, 1, 0, 1, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }

    private ProviderResult alphaOverview(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            String url = ALPHA_BASE + "?function=OVERVIEW&symbol=" + enc(symbol) + "&apikey=" + enc(alphaKey);
            log("ALPHA VANTAGE REQUEST: function=OVERVIEW symbol=" + symbol);
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("alpha_vantage", symbol, "overview", root);
            lastAlphaOverviewMs.put(symbol, System.currentTimeMillis());
            if (alphaInformational(root)) {
                observeAlphaInfo(RequestType.OVERVIEW, symbol, alphaMessage(root));
                log("ALPHA VANTAGE RESPONSE: function=OVERVIEW symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 note=" + alphaMessage(root));
                return result;
            }
            String companyName = root.path("Name").asText("");
            String sector = root.path("Sector").asText("");
            String industry = root.path("Industry").asText("");
            double marketCap = parseDouble(root.path("MarketCapitalization").asText("0"));
            double beta = parseDouble(root.path("Beta").asText("0"));
            if (companyName.isBlank() && sector.isBlank() && marketCap <= 0.0) {
                log("ALPHA VANTAGE RESPONSE: function=OVERVIEW symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=empty_overview cacheUpdated=true");
                return result;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("provider", "ALPHA_VANTAGE");
            m.put("rawMode", "ACTIVE_ALPHA_OVERVIEW");
            m.put("requestMode", "ACTIVE_REST_REQUEST");
            m.put("function", "OVERVIEW");
            m.put("companyName", companyName);
            m.put("sector", sector);
            m.put("industry", industry);
            m.put("marketCap", fmt(marketCap));
            m.put("beta", fmt(beta));
            double priority = clamp((marketCap > 0 ? 0.25 : 0.0) + (!sector.isBlank() ? 0.20 : 0.0) + Math.min(1.0, beta / 3.0) * 0.25 + 0.25);
            knowledgeDatabase.recordReference(symbol, companyName, sector, industry, marketCap, 0.0, "ALPHA_VANTAGE_OVERVIEW");
            signalConsumer.accept(new MarketIntelligenceSignal("ALPHA_VANTAGE", MarketIntelligenceSignalType.FUNDAMENTAL, symbol,
                    "Active Alpha Vantage overview: " + symbol + " sector=" + (sector.isBlank() ? "UNKNOWN" : sector) + " marketCap=" + Math.round(marketCap) + " beta=" + fmt(beta),
                    root.path("Description").asText(""), System.currentTimeMillis(), 0.60, priority, m));
            result.emitted++;
            log("ALPHA VANTAGE RESPONSE: function=OVERVIEW symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=1 cacheUpdated=true sector=" + sector);
            journal("ALPHA_VANTAGE", "OVERVIEW", symbol, true, 1, 0, 1, 1, 0, response.elapsedMs, "requested company overview");
        } catch (Exception e) {
            result.errors++;
            observeProviderError(Provider.ALPHA_VANTAGE, RequestType.OVERVIEW, symbol, e.getMessage());
            log("ALPHA VANTAGE RESPONSE: function=OVERVIEW symbol=" + symbol + " success=false error=" + safe(e.getMessage()));
            journal("ALPHA_VANTAGE", "OVERVIEW", symbol, false, 1, 0, 1, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }

    private ProviderResult alphaNews(String symbol, Consumer<MarketIntelligenceSignal> signalConsumer) {
        long started = System.currentTimeMillis();
        ProviderResult result = new ProviderResult();
        result.calls++;
        try {
            int limit = Math.max(1, Math.min(50, envInt("ACTIVE_REST_RESEARCH_ALPHA_NEWS_LIMIT", 10)));
            String url = ALPHA_BASE + "?function=NEWS_SENTIMENT&tickers=" + enc(symbol) + "&sort=LATEST&limit=" + limit + "&apikey=" + enc(alphaKey);
            log("ALPHA VANTAGE REQUEST: function=NEWS_SENTIMENT symbol=" + symbol + " limit=" + limit);
            HttpJson response = getJson(url);
            JsonNode root = response.body;
            cache("alpha_vantage", symbol, "news", root);
            lastAlphaNewsMs.put(symbol, System.currentTimeMillis());
            if (alphaInformational(root)) {
                observeAlphaInfo(RequestType.NEWS_SENTIMENT, symbol, alphaMessage(root));
                log("ALPHA VANTAGE RESPONSE: function=NEWS_SENTIMENT symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 note=" + alphaMessage(root));
                return result;
            }
            JsonNode feed = root.path("feed");
            if (!feed.isArray()) {
                log("ALPHA VANTAGE RESPONSE: function=NEWS_SENTIMENT symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=0 reason=no_feed cacheUpdated=true");
                return result;
            }
            int emittedForSymbol = 0;
            int maxSignals = envInt("ACTIVE_REST_RESEARCH_ALPHA_MAX_NEWS_SIGNALS_PER_SYMBOL", 3);
            for (JsonNode item : feed) {
                if (emittedForSymbol >= maxSignals) break;
                String title = item.path("title").asText("");
                String summary = item.path("summary").asText("");
                double sentiment = parseDouble(item.path("overall_sentiment_score").asText("0"));
                double priority = clamp(0.35 + Math.abs(sentiment) * 0.35 + headlinePriority(title + " " + summary) * 0.30);
                Map<String, String> m = new LinkedHashMap<>();
                m.put("provider", "ALPHA_VANTAGE");
                m.put("rawMode", "ACTIVE_ALPHA_NEWS_SENTIMENT");
                m.put("requestMode", "ACTIVE_REST_REQUEST");
                m.put("function", "NEWS_SENTIMENT");
                m.put("overallSentimentScore", fmt(sentiment));
                m.put("overallSentimentLabel", item.path("overall_sentiment_label").asText(""));
                m.put("source", item.path("source").asText(""));
                String articleUrl = item.path("url").asText("");
                String publishedUtc = item.path("time_published").asText("");
                m.put("url", articleUrl);
                knowledgeDatabase.recordNews(symbol, title, publishedUtc, priority, articleUrl, "ALPHA_VANTAGE_NEWS");
                signalConsumer.accept(new MarketIntelligenceSignal("ALPHA_VANTAGE", MarketIntelligenceSignalType.NEWS, symbol,
                        title.isBlank() ? "Active Alpha Vantage news sentiment" : title,
                        summary, parseAlphaTime(item.path("time_published").asText("")), 0.64, priority, m));
                result.emitted++;
                emittedForSymbol++;
            }
            log("ALPHA VANTAGE RESPONSE: function=NEWS_SENTIMENT symbol=" + symbol + " status=" + response.status + " latencyMs=" + response.elapsedMs + " emitted=" + emittedForSymbol + " cacheUpdated=true feedItems=" + feed.size());
            journal("ALPHA_VANTAGE", "NEWS", symbol, true, 1, 0, 1, emittedForSymbol, 0, response.elapsedMs, "requested news sentiment");
        } catch (Exception e) {
            result.errors++;
            observeProviderError(Provider.ALPHA_VANTAGE, RequestType.NEWS_SENTIMENT, symbol, e.getMessage());
            log("ALPHA VANTAGE RESPONSE: function=NEWS_SENTIMENT symbol=" + symbol + " success=false error=" + safe(e.getMessage()));
            journal("ALPHA_VANTAGE", "NEWS", symbol, false, 1, 0, 1, 0, 1, System.currentTimeMillis() - started, safe(e.getMessage()));
        }
        return result;
    }

    private boolean capabilityAvailable(Provider provider, RequestType type) {
        if (provider == Provider.POLYGON && System.currentTimeMillis() < polygonBackoffUntilMs) return false;
        if (provider == Provider.ALPHA_VANTAGE && System.currentTimeMillis() < alphaBackoffUntilMs) return false;
        // Polygon snapshots/bars/news are widely useful for this project. Trade and quote tick data
        // are entitlement-specific even on paid plans, so keep them off unless explicitly enabled.
        // This avoids wasting hundreds of premium research slots on predictable 403 responses while
        // still allowing instant activation if the account is upgraded to a plan that includes them.
        if (provider == Provider.POLYGON && (type == RequestType.TRADES || type == RequestType.QUOTES) && !polygonTradesQuotesEnabled()) {
            return false;
        }
        return !unavailableCapabilities.contains(provider + ":" + type);
    }

    private void disableCapability(Provider provider, RequestType type, String reason) {
        String key = provider + ":" + type;
        if (unavailableCapabilities.add(key)) {
            log("PROVIDER CAPABILITY DISABLED: provider=" + provider + " type=" + type + " reason=" + safe(reason));
            MarketIntelligenceBus.getInstance().recordProviderStatus(provider + "_CAPABILITY_" + type, "DISABLED:" + safe(reason));
        }
    }

    private void observeProviderError(Provider provider, RequestType type, String symbol, String message) {
        String m = message == null ? "" : message;
        if (m.contains("HTTP 403") || m.contains("NOT_AUTHORIZED") || m.toLowerCase(Locale.ROOT).contains("not entitled")) {
            disableCapability(provider, type, "not authorized by current plan");
        }
        if (m.contains("HTTP 404") || m.toLowerCase(Locale.ROOT).contains("ticker not found")) {
            invalidSymbols.add(symbol);
            log("SYMBOL VALIDATION FAILED: symbol=" + symbol + " provider=" + provider + " type=" + type + " reason=ticker_not_found");
        }
        if (m.contains("HTTP 429") || m.toLowerCase(Locale.ROOT).contains("maximum requests per minute") || m.toLowerCase(Locale.ROOT).contains("rate limit")) {
            long backoff = provider == Provider.POLYGON
                    ? envLong("ACTIVE_REST_RESEARCH_POLYGON_RATE_LIMIT_BACKOFF_MS", 90_000L)
                    : envLong("ACTIVE_REST_RESEARCH_ALPHA_RATE_LIMIT_BACKOFF_MS", 15L * 60L * 1000L);
            if (provider == Provider.POLYGON) polygonBackoffUntilMs = Math.max(polygonBackoffUntilMs, System.currentTimeMillis() + backoff);
            if (provider == Provider.ALPHA_VANTAGE) alphaBackoffUntilMs = Math.max(alphaBackoffUntilMs, System.currentTimeMillis() + backoff);
            log("PROVIDER RATE LIMIT BACKOFF: provider=" + provider + " backoffMs=" + backoff + " reason=" + safe(message));
        }
    }

    private void observeAlphaInfo(RequestType type, String symbol, String message) {
        String m = message == null ? "" : message;
        if (m.contains("25 requests per day") || m.toLowerCase(Locale.ROOT).contains("daily rate limit")) {
            int remaining = Math.max(0, remainingAlphaDailyBudget());
            if (remaining <= 0 || alphaDailyCalls >= Math.max(1, alphaDailyBudget())) {
                alphaBackoffUntilMs = Math.max(alphaBackoffUntilMs, nextMarketMidnightMs());
            }
            log("ALPHA VANTAGE DAILY LIMIT OBSERVED: symbol=" + symbol + " type=" + type + " dailyCalls=" + alphaDailyCalls + " remaining=" + remaining + " message=" + safe(message));
        }
        if (m.toLowerCase(Locale.ROOT).contains("invalid api call") || m.toLowerCase(Locale.ROOT).contains("error message")) {
            invalidSymbols.add(symbol);
        }
    }

    private boolean reserveBudget(Provider provider) {
        long now = System.currentTimeMillis();
        if (provider == Provider.POLYGON) {
            prune(polygonCallTimes, now - 60_000L);
            if (polygonCallTimes.size() >= polygonPerMinuteBudget()) return false;
            polygonCallTimes.add(now);
            return true;
        }
        if (provider == Provider.ALPHA_VANTAGE) {
            resetAlphaDailyIfNeeded();
            if (alphaDailyCalls >= alphaDailyBudget()) {
                alphaBackoffUntilMs = Math.max(alphaBackoffUntilMs, nextMarketMidnightMs());
                return false;
            }
            alphaDailyCalls++;
            alphaCallTimes.add(now);
            return true;
        }
        return true;
    }

    private int remainingPolygonMinuteBudget() {
        prune(polygonCallTimes, System.currentTimeMillis() - 60_000L);
        return Math.max(0, polygonPerMinuteBudget() - polygonCallTimes.size());
    }

    private int remainingAlphaDailyBudget() {
        resetAlphaDailyIfNeeded();
        return Math.max(0, alphaDailyBudget() - alphaDailyCalls);
    }

    private static void prune(List<Long> list, long cutoff) {
        synchronized (list) { list.removeIf(v -> v == null || v < cutoff); }
    }

    private static int polygonPerMinuteBudget() { return Math.max(1, envInt("ACTIVE_REST_RESEARCH_POLYGON_CALLS_PER_MINUTE", polygonPremiumMode() ? 300 : 5)); }
    private static int maxPolygonCallsPerCycle() { return Math.max(0, envInt("ACTIVE_REST_RESEARCH_MAX_POLYGON_CALLS", polygonPremiumMode() ? 220 : 5)); }
    private static boolean polygonPremiumMode() { return envBoolean("POLYGON_PREMIUM_MODE", true); }
    private static int alphaDailyBudget() { return Math.max(0, envInt("ACTIVE_REST_RESEARCH_ALPHA_CALLS_PER_DAY", 20)); }

    private void resetAlphaDailyIfNeeded() {
        long day = LocalDate.now(MARKET_ZONE).toEpochDay();
        if (alphaDailyResetDay != day) {
            alphaDailyResetDay = day;
            alphaDailyCalls = 0;
            if (System.currentTimeMillis() >= alphaBackoffUntilMs) alphaBackoffUntilMs = 0L;
        }
    }

    private static long nextMarketMidnightMs() {
        return LocalDate.now(MARKET_ZONE).plusDays(1).atStartOfDay(MARKET_ZONE).toInstant().toEpochMilli();
    }

    private boolean isResearchableSymbol(String symbol) {
        String t = normalizeTicker(symbol);
        if (t.isBlank()) return false;
        if (invalidSymbols.contains(t)) return false;
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("ticker") || lower.equals("symbol") || lower.equals("null") || lower.equals("none") || lower.equals("unknown")) return false;
        if (t.endsWith("USD") || t.contains("/")) return false;
        return true;
    }

    private List<String> selectSymbols() {
        Set<String> out = new LinkedHashSet<>();
        if (envBoolean("ACTIVE_REST_RESEARCH_USE_QUESTION_GENERATOR", true)) {
            for (ResearchQuestion q : ResearchQuestionGenerator.getInstance().generateQuestions(maxSymbols())) {
                String t = normalizeTicker(q.symbol);
                if (!t.isBlank()) out.add(t);
                if (out.size() >= maxSymbols()) break;
            }
        }
        if (envBoolean("ACTIVE_REST_RESEARCH_USE_AGENT_SWARM", true)) {
            for (String s : ResearchAgentSwarm.getInstance().proposeSymbols(maxSymbols())) {
                String t = normalizeTicker(s);
                if (!t.isBlank()) out.add(t);
                if (out.size() >= maxSymbols()) break;
            }
        }
        addCsv(out, env("ACTIVE_REST_RESEARCH_SYMBOLS", ""));
        readTickerColumn(out, Path.of(env("ACTIVE_REST_RESEARCH_PRIMARY_SOURCE", "logs/unified_candidate_scores.csv")), maxSymbols());
        readTickerColumn(out, Path.of("logs/market_features.csv"), maxSymbols());
        readTickerColumn(out, Path.of("logs/live_feature_store.csv"), maxSymbols());
        readTickerColumn(out, Path.of("logs/opportunity_memory.csv"), maxSymbols());
        readTickerColumn(out, Path.of("logs/stock_memory.csv"), maxSymbols());
        if (out.isEmpty()) addCsv(out, env("ACTIVE_REST_RESEARCH_DEFAULT_SYMBOLS", "SPY,QQQ,IWM,AAPL,NVDA,TSLA,META,AMD,MSFT,AMZN"));
        List<String> list = new ArrayList<>();
        for (String s : out) {
            if (list.size() >= maxSymbols()) break;
            String t = normalizeTicker(s);
            if (!t.isBlank()) list.add(t);
        }
        return list;
    }

    private static void readTickerColumn(Set<String> out, Path path, int max) {
        if (out.size() >= max || path == null || !Files.exists(path)) return;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            int idx = findTickerIndex(header);
            String line;
            while ((line = r.readLine()) != null && out.size() < max) {
                List<String> cols = parseCsv(line);
                if (idx >= 0 && idx < cols.size()) {
                    String t = normalizeTicker(cols.get(idx));
                    if (!t.isBlank()) out.add(t);
                    else {
                        for (String cell : cols) {
                            String alt = normalizeTicker(cell);
                            if (!alt.isBlank()) { out.add(alt); break; }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static int findTickerIndex(String header) {
        if (header == null) return 0;
        List<String> h = parseCsv(header);
        for (int i = 0; i < h.size(); i++) {
            String n = h.get(i).trim().toLowerCase(Locale.ROOT);
            if (n.equals("ticker") || n.equals("symbol")) return i;
        }
        return 0;
    }

    private boolean polygonTradesDue(String symbol) {
        return requestDue(Provider.POLYGON, RequestType.TRADES, symbol, envLong("POLYGON_TRADES_REFRESH_MS", polygonPremiumMode() ? 20_000L : 300_000L), System.currentTimeMillis());
    }

    private boolean polygonQuotesDue(String symbol) {
        return requestDue(Provider.POLYGON, RequestType.QUOTES, symbol, envLong("POLYGON_QUOTES_REFRESH_MS", polygonPremiumMode() ? 20_000L : 300_000L), System.currentTimeMillis());
    }

    private boolean requestDue(Provider provider, RequestType type, String symbol, long refreshMs, long now) {
        long last = lastRequestMs.getOrDefault(provider + ":" + type + ":" + symbol, 0L);
        return now - last >= Math.max(10_000L, refreshMs);
    }

    private boolean polygonBarsDue(String symbol) {
        long refresh = Math.max(30_000L, envLong("ACTIVE_REST_RESEARCH_POLYGON_BARS_REFRESH_MS", polygonPremiumMode() ? 45_000L : 180_000L));
        return System.currentTimeMillis() - lastPolygonBarsMs.getOrDefault(symbol, 0L) >= refresh;
    }

    private boolean polygonNewsDue(String symbol) {
        long refresh = Math.max(30_000L, envLong("ACTIVE_REST_RESEARCH_POLYGON_NEWS_REFRESH_MS", polygonPremiumMode() ? 120_000L : 1_800_000L));
        return System.currentTimeMillis() - lastPolygonNewsMs.getOrDefault(symbol, 0L) >= refresh;
    }

    private boolean polygonDetailsDue(String symbol) {
        long refresh = Math.max(300_000L, envLong("ACTIVE_REST_RESEARCH_POLYGON_DETAILS_REFRESH_MS", 6L * 60L * 60L * 1000L));
        return System.currentTimeMillis() - lastPolygonDetailsMs.getOrDefault(symbol, 0L) >= refresh;
    }

    private boolean alphaOverviewDue(String symbol) {
        long refresh = Math.max(300_000L, envLong("ACTIVE_REST_RESEARCH_ALPHA_OVERVIEW_REFRESH_MS", 6L * 60L * 60L * 1000L));
        return System.currentTimeMillis() - lastAlphaOverviewMs.getOrDefault(symbol, 0L) >= refresh;
    }

    private boolean alphaNewsDue(String symbol) {
        long refresh = Math.max(300_000L, envLong("ACTIVE_REST_RESEARCH_ALPHA_NEWS_REFRESH_MS", 30L * 60L * 1000L));
        return System.currentTimeMillis() - lastAlphaNewsMs.getOrDefault(symbol, 0L) >= refresh;
    }

    private HttpJson getJson(String url) throws Exception {
        long started = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(25)).GET().header("accept", "application/json").build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - started;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : response.body();
            throw new IllegalStateException("HTTP " + response.statusCode() + " body=" + (body.length() > 240 ? body.substring(0, 240) : body));
        }
        return new HttpJson(response.statusCode(), elapsed, mapper.readTree(response.body() == null ? "{}" : response.body()));
    }

    private void cache(String provider, String symbol, String requestType, JsonNode body) {
        if (!envBoolean("ACTIVE_REST_RESEARCH_CACHE_ENABLED", true)) return;
        try {
            Path dir = cacheDir.resolve(provider).resolve(requestType);
            Files.createDirectories(dir);
            String ts = Instant.now().toString().replace(":", "-");
            Files.writeString(dir.resolve(symbol + "_" + ts + ".json"), body == null ? "{}" : body.toPrettyString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {}
    }

    private void journal(String provider, String requestType, String symbol, boolean success, int symbols, int polygonCalls, int alphaCalls, int emitted, int errors, long elapsedMs, String message) {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            boolean newFile = !Files.exists(journalPath) || Files.size(journalPath) == 0;
            try (BufferedWriter w = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (newFile) {
                    w.write("timestamp,provider,requestType,symbol,success,symbols,polygonCalls,alphaCalls,emitted,errors,elapsedMs,message");
                    w.newLine();
                }
                w.write(String.join(",",
                        csv(Instant.now().toString()), csv(provider), csv(requestType), csv(symbol), String.valueOf(success),
                        String.valueOf(symbols), String.valueOf(polygonCalls), String.valueOf(alphaCalls), String.valueOf(emitted), String.valueOf(errors), String.valueOf(elapsedMs), csv(message)
                ));
                w.newLine();
            }
        } catch (Exception e) {
            System.out.println("ACTIVE REST RESEARCH JOURNAL FAILED: " + e.getMessage());
        }
    }

    private void log(String message) {
        if (envBoolean("ACTIVE_REST_RESEARCH_CONSOLE_LOG", true)) {
            System.out.println(message);
        }
    }

    private static boolean alphaInformational(JsonNode root) {
        if (root == null) return false;
        return !root.path("Note").asText("").isBlank() || !root.path("Information").asText("").isBlank() || !root.path("Error Message").asText("").isBlank();
    }

    private static String alphaMessage(JsonNode root) {
        if (root == null) return "";
        String msg = root.path("Note").asText("");
        if (msg.isBlank()) msg = root.path("Information").asText("");
        if (msg.isBlank()) msg = root.path("Error Message").asText("");
        return safe(msg);
    }

    private static double headlinePriority(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
        double p = 0.15;
        if (s.contains("fda") || s.contains("approval") || s.contains("clearance")) p += 0.30;
        if (s.contains("guidance") || s.contains("raises") || s.contains("beats")) p += 0.20;
        if (s.contains("contract") || s.contains("award") || s.contains("merger") || s.contains("acquire") || s.contains("spin-off")) p += 0.20;
        if (s.contains("offering") || s.contains("dilution") || s.contains("bankruptcy") || s.contains("halts")) p += 0.25;
        return clamp(p);
    }

    private static long parsePolygonTime(String value) {
        try { return value == null || value.isBlank() ? System.currentTimeMillis() : Instant.parse(value.trim()).toEpochMilli(); }
        catch (Exception ignored) { return System.currentTimeMillis(); }
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

    private static double intradayRangeScore(double price, double high, double low) {
        if (price <= 0.0 || high <= 0.0 || low <= 0.0 || high < low) return 0.0;
        return clamp(((high - low) / price) / 0.15);
    }

    private static double number(JsonNode node, String field, double fallback) {
        if (node == null || !node.has(field)) return fallback;
        try { return node.path(field).asDouble(fallback); } catch (Exception e) { return fallback; }
    }

    private static double parsePercent(String value) { return parseDouble(value == null ? "0" : value.replace("%", "")); }
    private static double parseDouble(String value) { try { return value == null || value.isBlank() || value.equals("-") || value.equalsIgnoreCase("None") ? 0.0 : Double.parseDouble(value.replace(",", "").trim()); } catch (Exception e) { return 0.0; } }
    private static String normalizeTicker(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "");
        if (t.equals("TICKER") || t.equals("SYMBOL") || t.equals("NULL") || t.equals("NONE") || t.equals("UNKNOWN") || t.equals("LONG") || t.equals("SHORT") || t.equals("BUY") || t.equals("SELL")) return "";
        if (t.length() > 8 || t.contains("/") || t.endsWith("USD")) return "";
        if (t.startsWith("202") || t.matches(".*\\d{4}-\\d{2}-\\d{2}.*") || t.contains("T12") || t.endsWith("Z")) return "";
        return t.matches("[A-Z][A-Z0-9.\\-]{0,7}") ? t : "";
    }
    private static void addCsv(Set<String> out, String csv) { if (csv == null) return; for (String part : csv.split(",")) { String t = normalizeTicker(part); if (!t.isBlank()) out.add(t); } }
    private static List<String> parseCsv(String line) { List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false; if (line == null) return out; for (int i = 0; i < line.length(); i++) { char ch = line.charAt(i); if (q) { if (ch == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false; } else cur.append(ch); } else { if (ch == ',') { out.add(cur.toString()); cur.setLength(0); } else if (ch == '"') q = true; else cur.append(ch); } } out.add(cur.toString()); return out; }
    private static String env(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static String envStatic(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static long envLong(String k, long f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Long.parseLong(v.trim()); } catch (Exception e) { return f; } }
    private static int envInt(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static boolean envBoolean(String k, boolean f) { String v = System.getenv(k); if (v == null || v.isBlank()) return f; String s = v.trim().toLowerCase(Locale.ROOT); return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on"); }
    private static String enc(String v) { return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8); }
    private static String encPath(String v) { return enc(v).replace("+", "%20"); }
    private static String safe(String v) { return v == null ? "" : v.replace('\n', ' ').replace('\r', ' '); }
    private static String csv(String v) { String s = safe(v); return '"' + s.replace("\"", "\"\"") + '"'; }
    private static String fmt(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) v = 0.0; return String.format(Locale.ROOT, "%.5f", v); }
    private static double clamp(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0; return Math.max(0.0, Math.min(1.0, v)); }
    private static double max(double... values) {
        double out = 0.0;
        if (values == null) return out;
        for (double v : values) if (Double.isFinite(v) && v > out) out = v;
        return out;
    }
    private static boolean polygonTradesQuotesEnabled() { return envBoolean("POLYGON_ENABLE_TRADE_QUOTE_ENDPOINTS", false); }
    private static long pollMs() { return Math.max(5_000L, envLong("ACTIVE_REST_RESEARCH_POLL_MS", polygonPremiumMode() ? 10_000L : 45_000L)); }
    private static int maxSymbols() { return Math.max(1, Math.min(600, envInt("ACTIVE_REST_RESEARCH_MAX_SYMBOLS", polygonPremiumMode() ? 600 : 25))); }
    private static void sleep(long ms) { try { Thread.sleep(Math.max(1_000L, ms)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static String preview(List<String> symbols, int max) { if (symbols == null || symbols.isEmpty()) return "[]"; return symbols.subList(0, Math.min(max, symbols.size())).toString(); }

    private static final class ExecutedResearch {
        final ResearchRequest request;
        final ProviderResult result;
        ExecutedResearch(ResearchRequest request, ProviderResult result) {
            this.request = request;
            this.result = result == null ? ProviderResult.empty() : result;
        }
    }

    private enum Provider { POLYGON, ALPHA_VANTAGE }
    private enum RequestType { SNAPSHOT, BARS_1_MIN, TRADES, QUOTES, REFERENCE_DETAILS, POLYGON_NEWS, QUOTE, OVERVIEW, NEWS_SENTIMENT }

    private static final class ResearchRequest {
        final Provider provider;
        final RequestType type;
        final String symbol;
        final double priority;
        ResearchRequest(Provider provider, RequestType type, String symbol, double priority) {
            this.provider = provider;
            this.type = type;
            this.symbol = symbol;
            this.priority = priority;
        }
    }

    private static final class ProviderResult {
        int calls, emitted, errors;
        static ProviderResult empty() { return new ProviderResult(); }
    }

    private static final class CycleStats {
        int symbols, queued, calls, polygonCalls, alphaCalls, emitted, errors;
        long elapsedMs;
    }

    private static final class HttpJson {
        final int status;
        final long elapsedMs;
        final JsonNode body;
        HttpJson(int status, long elapsedMs, JsonNode body) {
            this.status = status;
            this.elapsedMs = elapsedMs;
            this.body = body;
        }
    }
}
