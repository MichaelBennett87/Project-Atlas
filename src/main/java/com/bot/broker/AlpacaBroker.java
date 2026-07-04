package com.bot.broker;

import com.bot.model.Bar;
import com.bot.model.MarketQuality;
import com.bot.model.NewsEvent;
import com.bot.model.OrderStatus;
import com.bot.model.Position;
import com.bot.risk.MarketHoursService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import com.bot.stream.AlpacaSymbolFilter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Map;

public class AlpacaBroker {

    private static final String PAPER_BASE_URL = "https://paper-api.alpaca.markets";
    private static final String DATA_BASE_URL = "https://data.alpaca.markets";

    private static final String ORDER_CLIENT_TAG =
            System.getenv().getOrDefault(
                    "ORDER_CLIENT_TAG",
                    "NEWSMOM"
            );

    private static final boolean EXTENDED_HOURS_NEWS_BUYS_ENABLED =
            "true".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "NEWS_BOT_EXTENDED_HOURS_BUY_ENABLED",
                            "true"
                    )
            );

    private static final long MARKET_QUALITY_CACHE_TTL_MS =
            envLong("MARKET_QUALITY_CACHE_TTL_MS", 2_500L);

    private static final long MARKET_DATA_MIN_REQUEST_INTERVAL_MS =
            envLong("ALPACA_MARKET_DATA_MIN_REQUEST_INTERVAL_MS", 125L);

    private static final long ACCOUNT_CACHE_TTL_MS =
            envLong("ALPACA_ACCOUNT_CACHE_TTL_MS", 120_000L);

    private static final long ACCOUNT_STALE_FALLBACK_MS =
            envLong("ALPACA_ACCOUNT_STALE_FALLBACK_MS", 30 * 60_000L);

    private static final long LATEST_BAR_CACHE_TTL_MS =
            envLong("ALPACA_LATEST_BAR_CACHE_TTL_MS", 15_000L);

    private static final long LATEST_PRICE_CACHE_TTL_MS =
            envLong("ALPACA_LATEST_PRICE_CACHE_TTL_MS", 5_000L);

    private static final ConcurrentHashMap<String, CachedMarketQuality> MARKET_QUALITY_CACHE =
            new ConcurrentHashMap<>();

    private static final Object MARKET_DATA_RATE_LIMIT_LOCK = new Object();

    private static final AtomicLong NEXT_MARKET_DATA_REQUEST_AT =
            new AtomicLong(0L);

    private static final long MARKET_DATA_RATE_LIMIT_BACKOFF_MS =
            envLong("ALPACA_MARKET_DATA_429_BACKOFF_MS", 30_000L);

    private static final double EXTENDED_BUY_LIMIT_BUFFER = 1.003;
    private static final double EXTENDED_SELL_LIMIT_BUFFER = 0.997;
    private static final double EXTENDED_EXIT_SELL_LIMIT_BUFFER_ATTEMPT_1 = 0.985;
    private static final double EXTENDED_EXIT_SELL_LIMIT_BUFFER_ATTEMPT_2 = 0.975;
    private static final double EXTENDED_EXIT_SELL_LIMIT_BUFFER_ATTEMPT_3 = 0.965;

    private volatile CachedAccountSnapshot cachedAccountSnapshot;
    private final ConcurrentHashMap<String, CachedBarSnapshot> latestBarCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedPriceSnapshot> latestPriceCache = new ConcurrentHashMap<>();

    /*
     * Live news entries were getting blocked because the old market-quality
     * gate required either 250k shares or $25M of same-day dollar volume.
     * That is too strict early in the session and it made clean, liquid quotes
     * look "not tradable" even when the spread was only a few basis points.
     * Keep the broken-quote / extreme-spread protections, but allow small
     * paper-test entries when there is a real bid/ask and reasonable turnover.
     */
    private static final long MIN_MARKET_QUALITY_DAY_VOLUME = 10_000L;
    private static final double MIN_MARKET_QUALITY_DOLLAR_VOLUME = 250_000.0;
    private static final double MAX_MARKET_QUALITY_SPREAD_PERCENT =
            envDouble("MARKET_QUALITY_MAX_EXTENDED_SPREAD_PERCENT", 0.50);

    private static final double MAX_REGULAR_MARKET_QUALITY_SPREAD_PERCENT =
            envDouble("MARKET_QUALITY_MAX_REGULAR_SPREAD_PERCENT", 1.00);

    private static final double EXTENDED_BUY_MAX_PREMIUM_TO_LAST_TRADE =
            envDouble("EXTENDED_BUY_MAX_PREMIUM_TO_LAST_TRADE", 0.02);

    private static final double EXTENDED_SELL_MAX_DISCOUNT_TO_LAST_TRADE =
            envDouble("EXTENDED_SELL_MAX_DISCOUNT_TO_LAST_TRADE", 0.02);

    private final String apiKey;
    private final String secretKey;
    private final boolean dryRun;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MarketHoursService marketHours = new MarketHoursService();

    public AlpacaBroker() {
        this(false);
    }

    public AlpacaBroker(boolean dryRun) {
        this.dryRun = dryRun;

        this.apiKey = System.getenv("ALPACA_API_KEY");
        this.secretKey = System.getenv("ALPACA_SECRET_KEY");

        if (!dryRun) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Missing ALPACA_API_KEY environment variable.");
            }

            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException("Missing ALPACA_SECRET_KEY environment variable.");
            }

            System.out.println("API key loaded: " + mask(apiKey));
            System.out.println("Secret loaded: " + mask(secretKey));
        } else {
            System.out.println("AlpacaBroker running in DRY RUN mode.");
        }
    }


    public List<String> getTradableStockSymbols() {
        if (dryRun) {
            List<String> symbols = new ArrayList<>();
            symbols.add("AAPL");
            symbols.add("MSFT");
            symbols.add("NVDA");
            symbols.add("TSLA");
            symbols.add("AMD");
            symbols.add("SPY");
            symbols.add("QQQ");
            return symbols;
        }

        try {
            HttpUrl url = HttpUrl.parse(PAPER_BASE_URL + "/v2/assets")
                    .newBuilder()
                    .addQueryParameter("status", "active")
                    .addQueryParameter("asset_class", "us_equity")
                    .build();

            JsonNode json = executeJson(authorizedRequest(url.toString()).get().build());
            List<String> symbols = new ArrayList<>();

            if (!json.isArray()) {
                return symbols;
            }

            for (JsonNode node : json) {
                String symbol = textOrEmpty(node.path("symbol")).trim().toUpperCase(Locale.ROOT);
                String exchange = textOrEmpty(node.path("exchange")).trim().toUpperCase(Locale.ROOT);
                String name = textOrEmpty(node.path("name")).trim();
                boolean tradable = node.path("tradable").asBoolean(false);
                boolean marginable = node.path("marginable").asBoolean(false);
                boolean shortable = node.path("shortable").asBoolean(false);

                if (!AlpacaSymbolFilter.isEligibleAlpacaAsset(symbol, name, exchange, tradable, marginable, shortable)) {
                    continue;
                }

                symbols.add(symbol);
            }

            System.out.println("ALPACA TRADABLE UNIVERSE LOADED: symbols=" + symbols.size());
            return symbols;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Alpaca tradable stock symbols.", e);
        }
    }

    public Map<String, Bar> getLatestBars(List<String> tickers) {
        Map<String, Bar> result = new LinkedHashMap<>();
        if (tickers == null || tickers.isEmpty()) {
            return result;
        }

        if (dryRun) {
            for (String ticker : tickers) {
                Bar bar = getLatestBar(ticker);
                if (bar != null) {
                    result.put(ticker, bar);
                }
            }
            return result;
        }

        List<String> toFetch = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String ticker : tickers) {
            String symbol = normalizeTicker(ticker);
            if (symbol.isBlank() || symbol.contains(":" ) || symbol.contains("/") || symbol.endsWith("USD")) {
                continue;
            }

            CachedBarSnapshot cached = latestBarCache.get(symbol);
            if (cached != null && now - cached.cachedAt <= LATEST_BAR_CACHE_TTL_MS) {
                result.put(symbol, cached.bar);
                continue;
            }
            toFetch.add(symbol);
        }

        if (toFetch.isEmpty()) {
            return result;
        }

        try {
            HttpUrl url = HttpUrl.parse(DATA_BASE_URL + "/v2/stocks/bars/latest")
                    .newBuilder()
                    .addQueryParameter("symbols", String.join(",", toFetch))
                    .addQueryParameter("feed", "iex")
                    .build();

            JsonNode json = executeJson(authorizedRequest(url.toString()).get().build());
            JsonNode barsNode = json.path("bars");

            if (!barsNode.isObject()) {
                return result;
            }

            for (String ticker : toFetch) {
                JsonNode barNode = barsNode.path(ticker);
                if (barNode.isMissingNode() || barNode.isNull()) {
                    continue;
                }

                double close = barNode.path("c").asDouble();
                if (close <= 0) {
                    continue;
                }

                Bar bar = new Bar();
                bar.ticker = ticker;
                bar.timestamp = parseTimestamp(barNode.path("t").asText());
                bar.open = barNode.path("o").asDouble();
                bar.high = barNode.path("h").asDouble();
                bar.low = barNode.path("l").asDouble();
                bar.close = close;
                bar.volume = barNode.path("v").asLong();
                result.put(ticker, bar);
                cacheLatestBarAndPrice(ticker, bar);
            }

            return result;
        } catch (Exception e) {
            // A failed batch should not blind the scanner when cached data is still usable.
            for (String ticker : toFetch) {
                CachedBarSnapshot cached = latestBarCache.get(ticker);
                if (cached != null && now - cached.cachedAt <= LATEST_BAR_CACHE_TTL_MS * 4L) {
                    result.putIfAbsent(ticker, cached.bar);
                }
            }
            if (!result.isEmpty()) {
                System.out.println("ALPACA BATCH BAR ERROR: using stale cached bars count=" + result.size() + " error=" + rootCauseMessage(e));
                return result;
            }
            throw new RuntimeException("Failed to get latest batch bars for " + toFetch.size() + " symbols.", e);
        }
    }

    public double getPrice(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        if (dryRun) return 100.00;
        if (normalizedTicker.isBlank()) {
            throw new RuntimeException("Failed to get price for blank ticker");
        }

        long now = System.currentTimeMillis();
        CachedPriceSnapshot cachedPrice = latestPriceCache.get(normalizedTicker);
        if (cachedPrice != null && now - cachedPrice.cachedAt <= LATEST_PRICE_CACHE_TTL_MS) {
            return cachedPrice.price;
        }

        CachedBarSnapshot cachedBar = latestBarCache.get(normalizedTicker);
        if (cachedBar != null && now - cachedBar.cachedAt <= LATEST_BAR_CACHE_TTL_MS && cachedBar.bar.close > 0) {
            return cachedBar.bar.close;
        }

        try {
            Request request = authorizedRequest(
                    stockDataUrl("/v2/stocks/" + normalizedTicker + "/trades/latest")
            ).get().build();

            JsonNode json = executeJson(request);

            double price = json.path("trade").path("p").asDouble();

            if (price > 0) {
                cacheLatestPrice(normalizedTicker, price);
                return price;
            }

            Bar latestBar = getLatestBar(normalizedTicker);

            if (latestBar != null && latestBar.close > 0) {
                return latestBar.close;
            }

            double midpoint = getQuoteMidpointPrice(normalizedTicker);
            cacheLatestPrice(normalizedTicker, midpoint);
            return midpoint;

        } catch (Exception e) {
            CachedPriceSnapshot stalePrice = latestPriceCache.get(normalizedTicker);
            if (stalePrice != null && now - stalePrice.cachedAt <= LATEST_PRICE_CACHE_TTL_MS * 12L) {
                return stalePrice.price;
            }
            throw new RuntimeException("Failed to get price for " + normalizedTicker, e);
        }
    }

    public Bar getLatestBar(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedBarSnapshot cached = latestBarCache.get(normalizedTicker);
        if (cached != null && now - cached.cachedAt <= LATEST_BAR_CACHE_TTL_MS) {
            return cached.bar;
        }

        if (dryRun) {
            Bar bar = new Bar();
            bar.ticker = ticker;
            bar.timestamp = System.currentTimeMillis();
            bar.open = 100.00;
            bar.high = 100.25;
            bar.low = 99.95;
            bar.close = 100.10;
            bar.volume = 10_000;
            return bar;
        }

        try {
            Request request = authorizedRequest(
                    stockDataUrl("/v2/stocks/" + normalizedTicker + "/bars/latest")
            ).get().build();

            JsonNode json = executeJson(request);
            JsonNode barNode = json.path("bar");

            if (barNode.isMissingNode() || barNode.isNull()) {
                return null;
            }

            double close = barNode.path("c").asDouble();

            if (close <= 0) {
                return null;
            }

            Bar bar = new Bar();
            bar.ticker = normalizedTicker;
            bar.timestamp = parseTimestamp(barNode.path("t").asText());
            bar.open = barNode.path("o").asDouble();
            bar.high = barNode.path("h").asDouble();
            bar.low = barNode.path("l").asDouble();
            bar.close = close;
            bar.volume = barNode.path("v").asLong();

            cacheLatestBarAndPrice(normalizedTicker, bar);
            return bar;

        } catch (Exception e) {
            System.err.println("Failed to get latest bar for " + normalizedTicker + ": " + e.getMessage());
            CachedBarSnapshot stale = latestBarCache.get(normalizedTicker);
            if (stale != null && System.currentTimeMillis() - stale.cachedAt <= LATEST_BAR_CACHE_TTL_MS * 4L) {
                return stale.bar;
            }
            return null;
        }
    }

    public void cancelOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }

        if (dryRun) {
            System.out.println("DRY RUN CANCEL ORDER: " + orderId);
            return;
        }

        try {
            Request request = authorizedRequest(
                    PAPER_BASE_URL + "/v2/orders/" + orderId
            ).delete().build();

            try (Response response = client.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();

                if (!response.isSuccessful() && response.code() != 404) {
                    throw new IOException(
                            "HTTP " + response.code() + " " + response.message() + " body=" + body
                    );
                }
            }

            System.out.println("ORDER CANCELLED: " + orderId);

        } catch (Exception e) {
            throw new RuntimeException("Failed to cancel order " + orderId, e);
        }
    }

    public String buyMarket(String ticker, int qty) {
        if (dryRun) {
            String id = "DRY-BUY-" + System.currentTimeMillis();
            System.out.println("DRY RUN BUY: " + ticker + " qty=" + qty + " id=" + id);
            return id;
        }

        return submitAdaptiveOrder(ticker, qty, "buy");
    }

    public String sellMarket(String ticker, int qty) {
        if (dryRun) {
            String id = "DRY-SELL-" + System.currentTimeMillis();
            System.out.println("DRY RUN SELL: " + ticker + " qty=" + qty + " id=" + id);
            return id;
        }

        return submitAdaptiveOrder(ticker, qty, "sell");
    }

    public boolean isExtendedOnlySessionNow() {
        return marketHours.isExtendedOnlyNow();
    }

    public String sellExitOrder(String ticker, int qty, int attemptNumber) {
        if (dryRun) {
            String id = "DRY-EXIT-SELL-" + System.currentTimeMillis();
            System.out.println(
                    "DRY RUN EXIT SELL: " +
                            ticker +
                            " qty=" +
                            qty +
                            " attempt=" +
                            attemptNumber +
                            " id=" +
                            id
            );
            return id;
        }

        if (!marketHours.isExtendedMarketOpenNow()) {
            throw new IllegalStateException(
                    "Cannot submit exit sell because market is closed. session=" +
                            marketHours.currentSessionName()
            );
        }

        if (marketHours.isRegularMarketOpenNow()) {
            return submitRegularMarketOrder(ticker, qty, "sell");
        }

        return submitExtendedHoursAggressiveExitSellLimitOrder(
                ticker,
                qty,
                attemptNumber
        );
    }


    public String buyExitOrder(String ticker, int qty, int attemptNumber) {
        if (dryRun) {
            String id = "DRY-EXIT-BUY-" + System.currentTimeMillis();
            System.out.println(
                    "DRY RUN EXIT BUY/COVER: " +
                            ticker +
                            " qty=" +
                            qty +
                            " attempt=" +
                            attemptNumber +
                            " id=" +
                            id
            );
            return id;
        }

        if (!marketHours.isExtendedMarketOpenNow()) {
            throw new IllegalStateException(
                    "Cannot submit exit buy/cover because market is closed. session=" +
                            marketHours.currentSessionName()
            );
        }

        if (marketHours.isRegularMarketOpenNow()) {
            return submitRegularMarketOrder(ticker, qty, "buy");
        }

        return submitExtendedHoursAggressiveExitBuyLimitOrder(
                ticker,
                qty,
                attemptNumber
        );
    }

    public String closePositionMarket(String ticker, int qty) {
        String normalizedTicker = normalizeTicker(ticker);
        if (dryRun) {
            String id = "DRY-CLOSE-" + normalizedTicker + "-" + System.currentTimeMillis();
            System.out.println("DRY RUN CLOSE POSITION: " + normalizedTicker + " qty=" + qty + " id=" + id);
            return id;
        }

        if (normalizedTicker.isBlank()) {
            throw new IllegalArgumentException("Missing ticker for close-position request.");
        }

        if (qty <= 0) {
            throw new IllegalArgumentException("Close-position quantity must be positive. qty=" + qty);
        }

        if (!marketHours.isExtendedMarketOpenNow()) {
            throw new IllegalStateException(
                    "Cannot close position because market is closed. session=" +
                            marketHours.currentSessionName()
            );
        }

        try {
            HttpUrl.Builder urlBuilder = HttpUrl.parse(PAPER_BASE_URL + "/v2/positions/" + normalizedTicker).newBuilder();
            urlBuilder.addQueryParameter("qty", String.valueOf(qty));

            Request request = authorizedRequest(urlBuilder.build().toString()).delete().build();
            JsonNode response = executeOrderJsonWithRetry(request, normalizedTicker, "close", 3);

            String id = response.path("id").asText();
            if (id == null || id.isBlank()) {
                id = response.path("order_id").asText();
            }
            if (id == null || id.isBlank()) {
                id = "CLOSE-" + normalizedTicker + "-" + System.currentTimeMillis();
            }

            System.out.println(
                    "CLOSE POSITION ORDER SUBMITTED: " +
                            normalizedTicker +
                            " qty=" + qty +
                            " id=" + id +
                            " endpoint=/v2/positions/{symbol}"
            );

            return id;
        } catch (Exception e) {
            String detail = rootCauseMessage(e);
            System.err.println("ALPACA CLOSE POSITION FAILED: ticker=" + normalizedTicker +
                    " qty=" + qty +
                    " detail=" + detail);
            throw new RuntimeException(
                    "Failed to close position for " + normalizedTicker + ": " + detail,
                    e
            );
        }
    }


    public String closePositionFullyMarket(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        if (dryRun) {
            String id = "DRY-CLOSE-ALL-" + normalizedTicker + "-" + System.currentTimeMillis();
            System.out.println("DRY RUN CLOSE FULL POSITION: " + normalizedTicker + " id=" + id);
            return id;
        }

        if (normalizedTicker.isBlank()) {
            throw new IllegalArgumentException("Missing ticker for full close-position request.");
        }

        if (!marketHours.isExtendedMarketOpenNow()) {
            throw new IllegalStateException(
                    "Cannot close full position because market is closed. session=" +
                            marketHours.currentSessionName()
            );
        }

        try {
            Request request = authorizedRequest(
                    PAPER_BASE_URL + "/v2/positions/" + normalizedTicker
            ).delete().build();

            JsonNode response = executeOrderJsonWithRetry(request, normalizedTicker, "close_all", 3);
            String id = response.path("id").asText();
            if (id == null || id.isBlank()) {
                id = response.path("order_id").asText();
            }
            if (id == null || id.isBlank()) {
                id = "CLOSE-ALL-" + normalizedTicker + "-" + System.currentTimeMillis();
            }

            System.out.println("CLOSE FULL POSITION ORDER SUBMITTED: " + normalizedTicker + " id=" + id);
            return id;
        } catch (Exception e) {
            String detail = rootCauseMessage(e);
            System.err.println("ALPACA CLOSE FULL POSITION FAILED: ticker=" + normalizedTicker + " detail=" + detail);
            throw new RuntimeException("Failed to close full position for " + normalizedTicker + ": " + detail, e);
        }
    }


    private String submitExtendedHoursAggressiveExitBuyLimitOrder(
            String ticker,
            int qty,
            int attemptNumber
    ) {
        double referencePrice =
                getExtendedHoursReferencePrice(ticker, "buy");

        double limitPrice =
                referencePrice * (1.0 + Math.min(0.015, 0.003 * Math.max(1, attemptNumber)));

        System.out.println(
                "EXTENDED HOURS EXIT BUY/COVER LIMIT: " +
                        ticker +
                        " qty=" +
                        qty +
                        " attempt=" +
                        attemptNumber +
                        " referencePrice=" +
                        formatPrice(referencePrice) +
                        " aggressiveLimit=" +
                        formatPrice(limitPrice) +
                        " session=" +
                        marketHours.currentSessionName()
        );

        return submitOrder(
                ticker,
                qty,
                "buy",
                "limit",
                "day",
                limitPrice,
                true
        );
    }

    public String sellStopOrder(String ticker, int qty, double stopPrice) {
        if (dryRun) {
            String id = "DRY-STOP-SELL-" + System.currentTimeMillis();

            System.out.println(
                    "DRY RUN STOP SELL: " +
                            ticker +
                            " qty=" +
                            qty +
                            " stop=" +
                            stopPrice +
                            " id=" +
                            id
            );

            return id;
        }

        if (!marketHours.isRegularMarketOpenNow()) {
            System.out.println(
                    "STOP SELL SKIPPED OUTSIDE REGULAR HOURS: " +
                            ticker +
                            " qty=" +
                            qty +
                            " stop=" +
                            stopPrice +
                            " session=" +
                            marketHours.currentSessionName()
            );

            return "";
        }

        try {
            String json = mapper.writeValueAsString(Map.of(
                    "symbol", ticker,
                    "qty", String.valueOf(qty),
                    "side", "sell",
                    "type", "stop",
                    "time_in_force", "gtc",
                    "stop_price", String.format("%.2f", stopPrice)
            ));

            RequestBody body = RequestBody.create(
                    json,
                    MediaType.parse("application/json")
            );

            Request request = authorizedRequest(
                    PAPER_BASE_URL + "/v2/orders"
            ).post(body).build();

            JsonNode response = executeOrderJsonWithRetry(request, ticker, "sell", 3);

            String id = response.path("id").asText();

            System.out.println(
                    "STOP SELL ORDER SUBMITTED: " +
                            ticker +
                            " qty=" +
                            qty +
                            " stop=" +
                            stopPrice +
                            " id=" +
                            id
            );

            return id;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to submit stop sell order for " + ticker,
                    e
            );
        }
    }

    public OrderStatus getOrderStatus(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return new OrderStatus("", "missing", false);
        }

        if (dryRun) {
            return new OrderStatus(orderId, "filled", true);
        }

        try {
            Request request = authorizedRequest(
                    PAPER_BASE_URL + "/v2/orders/" + orderId
            ).get().build();

            JsonNode json = executeJson(request);

            String status = json.path("status").asText();
            boolean filled = "filled".equalsIgnoreCase(status);

            return new OrderStatus(orderId, status, filled);

        } catch (Exception e) {
            throw new RuntimeException("Failed to get order status for " + orderId, e);
        }
    }

    public MarketQuality getMarketQuality(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);

        if (normalizedTicker.isBlank()) {
            return new MarketQuality(
                    "",
                    0,
                    0,
                    0,
                    1.0,
                    false,
                    0.0,
                    0.0,
                    0L,
                    0L,
                    0.0,
                    0.0,
                    "INVALID_TICKER"
            );
        }

        CachedMarketQuality cached = MARKET_QUALITY_CACHE.get(normalizedTicker);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.cachedAt <= MARKET_QUALITY_CACHE_TTL_MS) {
            return cached.marketQuality;
        }

        if (dryRun) {
            return new MarketQuality(
                    ticker,
                    100.00,
                    99.95,
                    100.05,
                    0.001,
                    true,
                    0.95,
                    100.00,
                    5_000_000L,
                    4_000_000L,
                    1.25,
                    500_000_000.00,
                    "DRY_RUN_MARKET_QUALITY"
            );
        }

        try {
            throttleMarketDataRequest();

            Request request = authorizedRequest(
                    stockDataUrl("/v2/stocks/" + normalizedTicker + "/snapshot")
            ).get().build();

            JsonNode json = executeJson(request);

            JsonNode latestQuote = json.path("latestQuote");
            JsonNode latestTrade = json.path("latestTrade");
            JsonNode dailyBar = json.path("dailyBar");
            JsonNode previousDailyBar = json.path("prevDailyBar");

            double ask = latestQuote.path("ap").asDouble();
            double bid = latestQuote.path("bp").asDouble();
            double latestTradePrice = latestTrade.path("p").asDouble();
            double dailyClose = dailyBar.path("c").asDouble();

            long dayVolume = dailyBar.path("v").asLong();
            long previousDayVolume = previousDailyBar.path("v").asLong();

            double price =
                    resolveBestPrice(
                            latestTradePrice,
                            bid,
                            ask,
                            dailyClose
                    );

            if (price <= 0) {
                return new MarketQuality(
                        ticker,
                        0.0,
                        bid,
                        ask,
                        1.0,
                        false,
                        0.0,
                        latestTradePrice,
                        dayVolume,
                        previousDayVolume,
                        0.0,
                        0.0,
                        "NO_VALID_PRICE"
                );
            }

            double spreadPercent =
                    ask > 0 && bid > 0 && price > 0
                            ? Math.max(0.0, (ask - bid) / price)
                            : 1.0;

            double relativeVolume =
                    previousDayVolume > 0
                            ? (double) dayVolume / previousDayVolume
                            : 0.0;

            double dollarVolume =
                    price * dayVolume;

            boolean quoteUsable =
                    bid > 0.0 &&
                            ask > 0.0 &&
                            ask >= bid;

            boolean liquidEnough =
                    dayVolume >= MIN_MARKET_QUALITY_DAY_VOLUME ||
                            dollarVolume >= MIN_MARKET_QUALITY_DOLLAR_VOLUME;

            boolean regularSession =
                    marketHours.isRegularMarketOpenNow();

            double maxAllowedSpread =
                    regularSession
                            ? MAX_REGULAR_MARKET_QUALITY_SPREAD_PERCENT
                            : MAX_MARKET_QUALITY_SPREAD_PERCENT;

            boolean quoteAcceptableForSession =
                    regularSession
                            ? true
                            : (!quoteUsable || spreadPercent <= maxAllowedSpread);

            boolean tradable =
                    price > 1.00 &&
                            liquidEnough &&
                            spreadPercent >= 0 &&
                            quoteAcceptableForSession;

            double qualityScore =
                    calculateMarketQualityScore(
                            quoteUsable ? spreadPercent : maxAllowedSpread,
                            dayVolume,
                            dollarVolume,
                            relativeVolume
                    );

            if (!regularSession && !quoteUsable && latestTradePrice <= 0.0) {
                qualityScore = 0.0;
            }

            String reason =
                    buildMarketQualityReason(
                            tradable,
                            spreadPercent,
                            dayVolume,
                            dollarVolume,
                            relativeVolume,
                            marketHours.currentSessionName(),
                            quoteUsable
                    );

            MarketQuality result = new MarketQuality(
                    normalizedTicker,
                    price,
                    bid,
                    ask,
                    spreadPercent,
                    tradable,
                    qualityScore,
                    latestTradePrice,
                    dayVolume,
                    previousDayVolume,
                    relativeVolume,
                    dollarVolume,
                    reason
            );

            MARKET_QUALITY_CACHE.put(
                    normalizedTicker,
                    new CachedMarketQuality(result, System.currentTimeMillis())
            );

            return result;

        } catch (Exception e) {
            System.err.println("Failed to get market quality for " + normalizedTicker + ": " + e.getMessage());

            CachedMarketQuality stale = MARKET_QUALITY_CACHE.get(normalizedTicker);
            if (stale != null && isRateLimitError(e)) {
                System.out.println(
                        "MARKET QUALITY RATE LIMIT: using stale cached snapshot for " +
                                normalizedTicker +
                                " ageMs=" +
                                (System.currentTimeMillis() - stale.cachedAt)
                );
                return stale.marketQuality;
            }

            return new MarketQuality(
                    normalizedTicker,
                    0,
                    0,
                    0,
                    1.0,
                    false,
                    0.0,
                    0.0,
                    0L,
                    0L,
                    0.0,
                    0.0,
                    "MARKET_QUALITY_ERROR: " + e.getMessage()
            );
        }
    }

    private String submitAdaptiveOrder(String ticker, int qty, String side) {
        if ("buy".equalsIgnoreCase(side) &&
                !marketHours.isRegularMarketOpenNow() &&
                !EXTENDED_HOURS_NEWS_BUYS_ENABLED) {
            throw new IllegalStateException(
                    "News-bot extended-hours buys are disabled. " +
                            "Set NEWS_BOT_EXTENDED_HOURS_BUY_ENABLED=true to allow premarket/after-hours buys. session=" +
                            marketHours.currentSessionName()
            );
        }

        if (!marketHours.isExtendedMarketOpenNow()) {
            throw new IllegalStateException(
                    "Cannot submit order because market is closed. session=" +
                            marketHours.currentSessionName()
            );
        }

        if (marketHours.isRegularMarketOpenNow()) {
            return submitRegularMarketOrder(ticker, qty, side);
        }

        return submitExtendedHoursLimitOrder(ticker, qty, side);
    }

    private String submitRegularMarketOrder(String ticker, int qty, String side) {
        return submitOrder(
                ticker,
                qty,
                side,
                "market",
                "day",
                null,
                false
        );
    }

    private String submitExtendedHoursLimitOrder(String ticker, int qty, String side) {
        double referencePrice =
                getExtendedHoursReferencePrice(ticker, side);

        double limitPrice =
                calculateExtendedLimitPrice(
                        referencePrice,
                        side
                );

        return submitOrder(
                ticker,
                qty,
                side,
                "limit",
                "day",
                limitPrice,
                true
        );
    }

    private String submitExtendedHoursAggressiveExitSellLimitOrder(
            String ticker,
            int qty,
            int attemptNumber
    ) {
        double referencePrice =
                getExtendedHoursExitSellReferencePrice(ticker);

        double limitPrice =
                calculateExtendedExitSellLimitPrice(
                        referencePrice,
                        attemptNumber
                );

        System.out.println(
                "EXTENDED HOURS EXIT SELL LIMIT: " +
                        ticker +
                        " qty=" +
                        qty +
                        " attempt=" +
                        attemptNumber +
                        " referencePrice=" +
                        formatPrice(referencePrice) +
                        " aggressiveLimit=" +
                        formatPrice(limitPrice) +
                        " session=" +
                        marketHours.currentSessionName()
        );

        return submitOrder(
                ticker,
                qty,
                "sell",
                "limit",
                "day",
                limitPrice,
                true
        );
    }

    private String submitOrder(
            String ticker,
            int qty,
            String side,
            String type,
            String timeInForce,
            Double limitPrice,
            boolean extendedHours
    ) {
        try {
            if (ticker == null || ticker.isBlank()) {
                throw new IllegalArgumentException("Missing ticker for order submission.");
            }

            if (qty <= 0) {
                throw new IllegalArgumentException("Order quantity must be positive. qty=" + qty);
            }

            if ("limit".equalsIgnoreCase(type) &&
                    (limitPrice == null || limitPrice <= 0.0 || Double.isNaN(limitPrice))) {
                throw new IllegalArgumentException(
                        "Limit order requires a valid positive limit price. ticker=" +
                                ticker +
                                " limitPrice=" +
                                limitPrice
                );
            }

            Map<String, Object> order =
                    new LinkedHashMap<>();

            order.put("symbol", ticker);
            order.put("qty", String.valueOf(qty));
            order.put("side", side);
            order.put("type", type);
            order.put("time_in_force", timeInForce);
            order.put("client_order_id", buildClientOrderId(ticker, side));

            if (limitPrice != null) {
                order.put("limit_price", formatPrice(limitPrice));
            }

            if (extendedHours) {
                order.put("extended_hours", true);
            }

            String json =
                    mapper.writeValueAsString(order);

            System.out.println("ALPACA ORDER REQUEST BODY: " + json);

            RequestBody body = RequestBody.create(
                    json,
                    MediaType.parse("application/json")
            );

            Request request = authorizedRequest(
                    PAPER_BASE_URL + "/v2/orders"
            ).post(body).build();

            JsonNode response = executeOrderJsonWithRetry(request, ticker, side, 3);

            String id = response.path("id").asText();

            System.out.println(
                    side.toUpperCase() +
                            " ORDER SUBMITTED: " +
                            ticker +
                            " qty=" +
                            qty +
                            " type=" +
                            type +
                            " tif=" +
                            timeInForce +
                            " extendedHours=" +
                            extendedHours +
                            " limitPrice=" +
                            (limitPrice == null ? "" : formatPrice(limitPrice)) +
                            " id=" +
                            id
            );

            return id;

        } catch (Exception e) {
            String detail = rootCauseMessage(e);
            System.err.println("ALPACA ORDER SUBMISSION FAILED: ticker=" + ticker +
                    " side=" + side +
                    " qty=" + qty +
                    " type=" + type +
                    " tif=" + timeInForce +
                    " detail=" + detail);
            throw new RuntimeException(
                    "Failed to submit " +
                            side +
                            " order for " +
                            ticker +
                            ": " +
                            detail,
                    e
            );
        }
    }


    private JsonNode executeOrderJsonWithRetry(Request request, String ticker, String side, int maxAttempts) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt > 1) {
                    System.out.println(
                            "ALPACA ORDER RETRY: ticker=" +
                                    ticker +
                                    " side=" +
                                    side +
                                    " attempt=" +
                                    attempt +
                                    "/" +
                                    maxAttempts
                    );
                }

                return executeJson(request);

            } catch (SocketTimeoutException e) {
                lastException = e;

                System.out.println(
                        "ALPACA ORDER TIMEOUT: ticker=" +
                                ticker +
                                " side=" +
                                side +
                                " attempt=" +
                                attempt +
                                "/" +
                                maxAttempts +
                                " message=" +
                                e.getMessage()
                );

                if (attempt >= maxAttempts) {
                    throw e;
                }

                sleepBeforeRetry(attempt);

            } catch (IOException e) {
                lastException = e;

                String message = e.getMessage() == null ? "" : e.getMessage();
                boolean retryable =
                        message.contains("HTTP 408") ||
                                message.contains("HTTP 429") ||
                                message.contains("HTTP 500") ||
                                message.contains("HTTP 502") ||
                                message.contains("HTTP 503") ||
                                message.contains("HTTP 504");

                if (!retryable || attempt >= maxAttempts) {
                    throw e;
                }

                System.out.println(
                        "ALPACA ORDER RETRYABLE HTTP ERROR: ticker=" +
                                ticker +
                                " side=" +
                                side +
                                " attempt=" +
                                attempt +
                                "/" +
                                maxAttempts +
                                " error=" +
                                message
                );

                sleepBeforeRetry(attempt);
            }
        }

        if (lastException != null) {
            throw lastException;
        }

        throw new IOException("Order submission failed without a response for ticker=" + ticker);
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(3000L, 1000L * attempt));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private double getExtendedHoursReferencePrice(String ticker, String side) {
        double lastTradePrice = 0.0;

        try {
            lastTradePrice = getPrice(ticker);
        } catch (Exception ignored) {
        }

        try {
            Request request = authorizedRequest(
                    stockDataUrl("/v2/stocks/" + ticker + "/quotes/latest")
            ).get().build();

            JsonNode json = executeJson(request);

            double ask = json.path("quote").path("ap").asDouble();
            double bid = json.path("quote").path("bp").asDouble();

            if ("buy".equalsIgnoreCase(side)) {
                if (ask > 0 && lastTradePrice > 0) {
                    double cappedAsk =
                            lastTradePrice * (1.0 + EXTENDED_BUY_MAX_PREMIUM_TO_LAST_TRADE);

                    if (ask > cappedAsk) {
                        System.out.println(
                                "EXTENDED BUY LIMIT CAP APPLIED: ticker=" +
                                        ticker +
                                        " ask=" +
                                        ask +
                                        " lastTrade=" +
                                        lastTradePrice +
                                        " cappedReference=" +
                                        cappedAsk
                        );

                        return cappedAsk;
                    }
                }

                if (ask > 0) {
                    return ask;
                }
            }

            if ("sell".equalsIgnoreCase(side)) {
                if (bid > 0 && lastTradePrice > 0) {
                    double cappedBid =
                            lastTradePrice * (1.0 - EXTENDED_SELL_MAX_DISCOUNT_TO_LAST_TRADE);

                    if (bid < cappedBid) {
                        System.out.println(
                                "EXTENDED SELL LIMIT CAP APPLIED: ticker=" +
                                        ticker +
                                        " bid=" +
                                        bid +
                                        " lastTrade=" +
                                        lastTradePrice +
                                        " cappedReference=" +
                                        cappedBid
                        );

                        return cappedBid;
                    }
                }

                if (bid > 0) {
                    return bid;
                }
            }

            if (ask > 0 && bid > 0) {
                return (ask + bid) / 2.0;
            }

            if (lastTradePrice > 0) {
                return lastTradePrice;
            }

            if (ask > 0) {
                return ask;
            }

            if (bid > 0) {
                return bid;
            }

            return getPrice(ticker);

        } catch (Exception e) {
            return lastTradePrice > 0 ? lastTradePrice : getPrice(ticker);
        }
    }

    private double getExtendedHoursExitSellReferencePrice(String ticker) {
        try {
            Request request = authorizedRequest(
                    stockDataUrl("/v2/stocks/" + ticker + "/quotes/latest")
            ).get().build();

            JsonNode json = executeJson(request);

            double ask = json.path("quote").path("ap").asDouble();
            double bid = json.path("quote").path("bp").asDouble();

            if (bid > 0) {
                return bid;
            }

            if (ask > 0) {
                return ask;
            }

            return getPrice(ticker);

        } catch (Exception e) {
            return getPrice(ticker);
        }
    }

    private double calculateExtendedLimitPrice(
            double referencePrice,
            String side
    ) {
        if (referencePrice <= 0) {
            throw new IllegalStateException("Cannot calculate extended-hours limit price from invalid reference price.");
        }

        if ("buy".equalsIgnoreCase(side)) {
            return roundPrice(referencePrice * EXTENDED_BUY_LIMIT_BUFFER);
        }

        return roundPrice(referencePrice * EXTENDED_SELL_LIMIT_BUFFER);
    }

    private double calculateExtendedExitSellLimitPrice(
            double referencePrice,
            int attemptNumber
    ) {
        if (referencePrice <= 0) {
            throw new IllegalStateException("Cannot calculate extended-hours exit limit price from invalid reference price.");
        }

        double buffer;

        if (attemptNumber <= 1) {
            buffer = EXTENDED_EXIT_SELL_LIMIT_BUFFER_ATTEMPT_1;
        } else if (attemptNumber == 2) {
            buffer = EXTENDED_EXIT_SELL_LIMIT_BUFFER_ATTEMPT_2;
        } else {
            buffer = EXTENDED_EXIT_SELL_LIMIT_BUFFER_ATTEMPT_3;
        }

        double bufferedPrice =
                referencePrice * buffer;

        double minimumStepBelowReference =
                referencePrice >= 1.00 ? 0.01 : 0.0001;

        double steppedPrice =
                referencePrice - (minimumStepBelowReference * Math.max(1, attemptNumber));

        return roundPrice(
                Math.min(
                        bufferedPrice,
                        steppedPrice
                )
        );
    }

    private double roundPrice(double price) {
        if (price >= 1.00) {
            return Math.round(price * 100.0) / 100.0;
        }

        return Math.round(price * 10_000.0) / 10_000.0;
    }

    private String formatPrice(double price) {
        if (price >= 1.00) {
            return String.format("%.2f", price);
        }

        return String.format("%.4f", price);
    }

    private double resolveBestPrice(
            double latestTradePrice,
            double bid,
            double ask,
            double dailyClose
    ) {
        if (latestTradePrice > 0) {
            return latestTradePrice;
        }

        if (bid > 0 && ask > 0) {
            return (bid + ask) / 2.0;
        }

        if (dailyClose > 0) {
            return dailyClose;
        }

        if (ask > 0) {
            return ask;
        }

        if (bid > 0) {
            return bid;
        }

        return 0.0;
    }

    private double calculateMarketQualityScore(
            double spreadPercent,
            long dayVolume,
            double dollarVolume,
            double relativeVolume
    ) {
        double spreadScore;

        if (spreadPercent <= 0.0025) {
            spreadScore = 1.0;
        } else if (spreadPercent <= 0.005) {
            spreadScore = 0.90;
        } else if (spreadPercent <= 0.01) {
            spreadScore = 0.80;
        } else if (spreadPercent <= 0.03) {
            spreadScore = 0.70;
        } else if (spreadPercent <= 0.05) {
            spreadScore = 0.60;
        } else if (spreadPercent <= 0.10) {
            spreadScore = 0.50;
        } else if (spreadPercent <= 0.15) {
            spreadScore = 0.40;
        } else {
            spreadScore = 0.0;
        }

        double volumeScore;

        if (dollarVolume >= 1_000_000_000) {
            volumeScore = 1.0;
        } else if (dollarVolume >= 500_000_000) {
            volumeScore = 0.90;
        } else if (dollarVolume >= 250_000_000) {
            volumeScore = 0.80;
        } else if (dollarVolume >= 100_000_000) {
            volumeScore = 0.70;
        } else if (dollarVolume >= 50_000_000) {
            volumeScore = 0.60;
        } else if (dollarVolume >= 25_000_000) {
            volumeScore = 0.50;
        } else {
            volumeScore = 0.20;
        }

        double relativeVolumeScore;

        if (relativeVolume >= 3.0) {
            relativeVolumeScore = 1.0;
        } else if (relativeVolume >= 2.0) {
            relativeVolumeScore = 0.90;
        } else if (relativeVolume >= 1.5) {
            relativeVolumeScore = 0.80;
        } else if (relativeVolume >= 1.0) {
            relativeVolumeScore = 0.70;
        } else if (relativeVolume >= 0.5) {
            relativeVolumeScore = 0.50;
        } else if (relativeVolume > 0) {
            relativeVolumeScore = 0.30;
        } else {
            relativeVolumeScore = 0.40;
        }

        return (spreadScore * 0.35)
                + (volumeScore * 0.40)
                + (relativeVolumeScore * 0.25);
    }

    private String buildMarketQualityReason(
            boolean tradable,
            double spreadPercent,
            long dayVolume,
            double dollarVolume,
            double relativeVolume,
            String sessionName,
            boolean quoteUsable
    ) {
        String prefix = tradable
                ? "Passed market quality"
                : "Rejected market quality";

        return prefix +
                ": session=" +
                sessionName +
                ", quoteUsable=" +
                quoteUsable +
                ", spread=" +
                spreadPercent +
                ", dayVolume=" +
                dayVolume +
                ", dollarVolume=" +
                dollarVolume +
                ", relativeVolume=" +
                relativeVolume;
    }

    public AccountSnapshot getAccount() {
        if (dryRun) {
            return new AccountSnapshot(100_000.00, 400_000.00, 100_000.00);
        }

        long now = System.currentTimeMillis();
        CachedAccountSnapshot cached = cachedAccountSnapshot;
        if (cached != null && now - cached.cachedAt <= ACCOUNT_CACHE_TTL_MS) {
            return cached.snapshot;
        }

        try {
            Request request = authorizedRequest(
                    PAPER_BASE_URL + "/v2/account"
            ).get().build();

            JsonNode json = executeJson(request);

            AccountSnapshot snapshot = new AccountSnapshot(
                    json.path("equity").asDouble(),
                    json.path("buying_power").asDouble(),
                    json.path("last_equity").asDouble()
            );
            cachedAccountSnapshot = new CachedAccountSnapshot(snapshot, now);
            return snapshot;

        } catch (Exception e) {
            cached = cachedAccountSnapshot;
            if (cached != null && now - cached.cachedAt <= ACCOUNT_STALE_FALLBACK_MS) {
                System.out.println("ALPACA ACCOUNT ERROR: using stale cached account ageMs=" + (now - cached.cachedAt) + " detail=" + rootCauseMessage(e));
                return cached.snapshot;
            }
            throw new RuntimeException("Failed to get Alpaca account.", e);
        }
    }


    public int getOpenPositionQuantity(String ticker) {
        int signedQty = getSignedOpenPositionQuantity(ticker);
        // Long exits must never use abs(shortQty). If Alpaca reports a short as
        // negative, returning abs() here can make a short look like a long and
        // cause a SELL that attempts to increase the short instead of closing it.
        return Math.max(0, signedQty);
    }

    public int getSignedOpenPositionQuantity(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank()) {
            return 0;
        }

        if (dryRun) {
            return Integer.MAX_VALUE;
        }

        try {
            Request request = authorizedRequest(
                    PAPER_BASE_URL + "/v2/positions/" + normalizedTicker
            ).get().build();

            JsonNode json = executeJson(request);
            double rawQty = json.path("qty").asDouble(0.0);
            if (Double.isNaN(rawQty) || Math.abs(rawQty) < 0.000001) {
                return 0;
            }

            return (int) Math.round(rawQty);
        } catch (Exception e) {
            String detail = rootCauseMessage(e);
            if (detail.contains("404") || detail.toLowerCase(Locale.ROOT).contains("position does not exist")) {
                return 0;
            }

            System.out.println(
                    "SIGNED POSITION QTY LOOKUP FAILED: ticker=" +
                            normalizedTicker +
                            " detail=" +
                            detail +
                            " defaultingToZeroForExitSafety"
            );

            return 0;
        }
    }

    public boolean isOpenPositionShort(String ticker) {
        return getSignedOpenPositionQuantity(ticker) < 0;
    }

    public void cancelOpenBuyOrdersForSymbol(String ticker, String reason) {
        cancelOpenOrdersForSymbolBySide(ticker, "buy", reason);
    }

    public void cancelOpenSellOrdersForSymbol(String ticker, String reason) {
        cancelOpenOrdersForSymbolBySide(ticker, "sell", reason);
    }

    private void cancelOpenOrdersForSymbolBySide(String ticker, String targetSide, String reason) {
        String normalizedTicker = normalizeTicker(ticker);
        String normalizedSide = targetSide == null ? "" : targetSide.trim().toLowerCase(Locale.ROOT);
        if (normalizedTicker.isBlank() || normalizedSide.isBlank() || dryRun) {
            return;
        }

        try {
            HttpUrl url = HttpUrl.parse(PAPER_BASE_URL + "/v2/orders")
                    .newBuilder()
                    .addQueryParameter("status", "open")
                    .addQueryParameter("symbols", normalizedTicker)
                    .addQueryParameter("limit", "100")
                    .build();

            Request request = authorizedRequest(url.toString()).get().build();
            JsonNode json = executeJson(request);

            if (!json.isArray()) {
                return;
            }

            int cancelled = 0;
            for (JsonNode order : json) {
                String id = textOrEmpty(order.path("id"));
                String symbol = textOrEmpty(order.path("symbol")).trim().toUpperCase(Locale.ROOT);
                String side = textOrEmpty(order.path("side")).trim().toLowerCase(Locale.ROOT);
                String status = textOrEmpty(order.path("status")).trim().toLowerCase(Locale.ROOT);

                if (!normalizedTicker.equals(symbol) ||
                        !normalizedSide.equals(side) ||
                        id.isBlank() ||
                        isTerminalOrderStatus(status)) {
                    continue;
                }

                try {
                    cancelOrder(id);
                    cancelled++;
                } catch (Exception e) {
                    System.out.println("OPEN " + normalizedSide.toUpperCase(Locale.ROOT) + " CANCEL FAILED: ticker=" +
                            normalizedTicker +
                            " orderId=" +
                            id +
                            " reason=" +
                            reason +
                            " detail=" +
                            rootCauseMessage(e));
                }
            }

            if (cancelled > 0) {
                System.out.println("OPEN " + normalizedSide.toUpperCase(Locale.ROOT) + " ORDERS CANCELLED BEFORE EXIT: ticker=" +
                        normalizedTicker +
                        " cancelled=" +
                        cancelled +
                        " reason=" +
                        reason);
            }
        } catch (Exception e) {
            System.out.println("OPEN " + normalizedSide.toUpperCase(Locale.ROOT) + " ORDER LOOKUP FAILED: ticker=" +
                    normalizedTicker +
                    " reason=" +
                    reason +
                    " detail=" +
                    rootCauseMessage(e));
        }
    }

    private boolean isTerminalOrderStatus(String status) {
        if (status == null) {
            return false;
        }

        return status.equals("filled") ||
                status.equals("canceled") ||
                status.equals("cancelled") ||
                status.equals("expired") ||
                status.equals("rejected");
    }

    public List<Position> getOpenPositions() {
        if (dryRun) return new ArrayList<>();

        try {
            Request request = authorizedRequest(
                    PAPER_BASE_URL + "/v2/positions"
            ).get().build();

            JsonNode json = executeJson(request);

            List<Position> positions = new ArrayList<>();

            if (!json.isArray()) return positions;

            for (JsonNode node : json) {
                String ticker = node.path("symbol").asText();
                double avgEntryPrice = node.path("avg_entry_price").asDouble();
                double signedRawQty = node.path("qty").asDouble(0.0);
                int qty = Math.abs((int) Math.round(signedRawQty));
                boolean shortPosition = signedRawQty < 0.0;

                if (ticker == null || ticker.isBlank() || qty <= 0) continue;

                double currentPrice = getPrice(ticker);
                double peakPrice = Math.max(avgEntryPrice, currentPrice);
                Position position = new Position(
                        ticker,
                        avgEntryPrice,
                        peakPrice,
                        qty,
                        System.currentTimeMillis(),
                        null,
                        shortPosition
                );
                position.troughPrice = Math.min(avgEntryPrice, currentPrice);
                position.syncedFromBroker = true;

                positions.add(position);
            }

            return positions;

        } catch (Exception e) {
            throw new RuntimeException("Failed to get Alpaca open positions.", e);
        }
    }

    public List<NewsEvent> getLatestNews(String ticker, int limit) {
        if (dryRun) return new ArrayList<>();

        try {
            HttpUrl url = HttpUrl.parse(DATA_BASE_URL + "/v1beta1/news")
                    .newBuilder()
                    .addQueryParameter("symbols", ticker)
                    .addQueryParameter("limit", String.valueOf(limit))
                    .build();

            return parseNewsResponse(
                    executeJson(authorizedRequest(url.toString()).get().build()),
                    ticker
            );

        } catch (Exception e) {
            throw new RuntimeException("Failed to get Alpaca news for " + ticker, e);
        }
    }

    public List<NewsEvent> getLatestMarketNews(int limit) {
        if (dryRun) return new ArrayList<>();

        try {
            HttpUrl url = HttpUrl.parse(DATA_BASE_URL + "/v1beta1/news")
                    .newBuilder()
                    .addQueryParameter("limit", String.valueOf(limit))
                    .build();

            JsonNode json = executeJson(
                    authorizedRequest(url.toString()).get().build()
            );

            List<NewsEvent> events = new ArrayList<>();
            JsonNode newsArray = json.path("news");

            if (!newsArray.isArray()) return events;

            for (JsonNode node : newsArray) {
                String articleId = node.path("id").asText();
                String headline = node.path("headline").asText();
                String summary = node.path("summary").asText();
                String createdAt = node.path("created_at").asText();

                JsonNode symbols = node.path("symbols");

                if (!symbols.isArray()) continue;

                for (JsonNode symbolNode : symbols) {
                    String ticker = symbolNode.asText();

                    if (ticker == null || ticker.isBlank()) continue;

                    long providerTimestamp = parseTimestamp(createdAt);
                    NewsEvent event = new NewsEvent(
                            articleId + ":" + ticker,
                            ticker,
                            headline,
                            summary,
                            providerTimestamp
                    );
                    event.setSource("ALPACA_REST_MARKET_NEWS");
                    event.setProviderTimestamp(providerTimestamp);
                    event.setBotFirstSeenAt(System.currentTimeMillis());
                    event.setSourceLagMs(0L);
                    events.add(event);
                }
            }

            return events;

        } catch (Exception e) {
            throw new RuntimeException("Failed to get broad Alpaca market news.", e);
        }
    }

    private List<NewsEvent> parseNewsResponse(JsonNode json, String ticker) {
        List<NewsEvent> events = new ArrayList<>();
        JsonNode newsArray = json.path("news");

        if (!newsArray.isArray()) return events;

        for (JsonNode node : newsArray) {
            String id = node.path("id").asText();
            String headline = node.path("headline").asText();
            String summary = node.path("summary").asText();
            String createdAt = node.path("created_at").asText();

            long providerTimestamp = parseTimestamp(createdAt);
            NewsEvent event = new NewsEvent(
                    id,
                    ticker,
                    headline,
                    summary,
                    providerTimestamp
            );
            event.setSource("ALPACA_REST_TICKER_NEWS");
            event.setProviderTimestamp(providerTimestamp);
            event.setBotFirstSeenAt(System.currentTimeMillis());
            event.setSourceLagMs(0L);
            events.add(event);
        }

        return events;
    }

    public String shortMarket(String ticker, int qty) {
        System.out.println(
                "SHORT MARKET ORDER REQUESTED: " +
                        ticker +
                        " qty=" +
                        qty
        );

        if (dryRun) {
            String id = "DRY-SHORT-" + System.currentTimeMillis();
            System.out.println("DRY RUN SHORT: " + ticker + " qty=" + qty + " id=" + id);
            return id;
        }

        return submitAdaptiveOrder(
                ticker,
                qty,
                "sell"
        );
    }

    public String coverShortMarket(String ticker, int qty) {
        System.out.println(
                "COVER SHORT MARKET ORDER REQUESTED: " +
                        ticker +
                        " qty=" +
                        qty
        );

        if (dryRun) {
            String id = "DRY-COVER-" + System.currentTimeMillis();
            System.out.println("DRY RUN COVER: " + ticker + " qty=" + qty + " id=" + id);
            return id;
        }

        return submitAdaptiveOrder(
                ticker,
                qty,
                "buy"
        );
    }

    private double getQuoteMidpointPrice(String ticker) {
        try {
            Request request = authorizedRequest(
                    stockDataUrl("/v2/stocks/" + ticker + "/quotes/latest")
            ).get().build();

            JsonNode json = executeJson(request);

            double ask = json.path("quote").path("ap").asDouble();
            double bid = json.path("quote").path("bp").asDouble();

            if (ask > 0 && bid > 0) {
                return (ask + bid) / 2.0;
            }

            if (ask > 0) {
                return ask;
            }

            if (bid > 0) {
                return bid;
            }

            throw new RuntimeException("No valid quote midpoint for " + ticker);

        } catch (Exception e) {
            throw new RuntimeException("Failed to get quote midpoint for " + ticker, e);
        }
    }

    private String stockDataUrl(String path) {
        HttpUrl url = HttpUrl.parse(DATA_BASE_URL + path)
                .newBuilder()
                .addQueryParameter("feed", "iex")
                .build();

        return url.toString();
    }

    private long parseTimestamp(String createdAt) {
        try {
            if (createdAt == null || createdAt.isBlank()) {
                return System.currentTimeMillis();
            }

            return Instant.parse(createdAt).toEpochMilli();

        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }


    private static String normalizeTicker(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isRateLimitError(Exception e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("429") || message.contains("too many requests");
    }

    private void throttleMarketDataRequest() {
        if (MARKET_DATA_MIN_REQUEST_INTERVAL_MS <= 0) {
            return;
        }

        synchronized (MARKET_DATA_RATE_LIMIT_LOCK) {
            long now = System.currentTimeMillis();
            long next = NEXT_MARKET_DATA_REQUEST_AT.get();

            if (now < next) {
                try {
                    Thread.sleep(next - now);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            NEXT_MARKET_DATA_REQUEST_AT.set(
                    System.currentTimeMillis() + MARKET_DATA_MIN_REQUEST_INTERVAL_MS
            );
        }
    }

    private String buildClientOrderId(String ticker, String side) {
        String normalizedTicker = normalizeTicker(ticker);
        String normalizedSide = side == null ? "ORDER" : side.trim().toUpperCase(Locale.ROOT);
        String tag = ORDER_CLIENT_TAG == null || ORDER_CLIENT_TAG.isBlank()
                ? "NEWSMOM"
                : ORDER_CLIENT_TAG.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "");

        if (tag.length() > 16) {
            tag = tag.substring(0, 16);
        }

        String suffix = Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
        String candidate = tag + "-" + normalizedSide + "-" + normalizedTicker + "-" + suffix;

        return candidate.length() <= 48 ? candidate : candidate.substring(0, 48);
    }


    private void cacheLatestBarAndPrice(String ticker, Bar bar) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank() || bar == null || bar.close <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        latestBarCache.put(normalizedTicker, new CachedBarSnapshot(bar, now));
        latestPriceCache.put(normalizedTicker, new CachedPriceSnapshot(bar.close, now));
    }

    private void cacheLatestPrice(String ticker, double price) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank() || price <= 0 || !Double.isFinite(price)) {
            return;
        }
        latestPriceCache.put(normalizedTicker, new CachedPriceSnapshot(price, System.currentTimeMillis()));
    }

    private static class CachedAccountSnapshot {
        private final AccountSnapshot snapshot;
        private final long cachedAt;

        private CachedAccountSnapshot(AccountSnapshot snapshot, long cachedAt) {
            this.snapshot = snapshot;
            this.cachedAt = cachedAt;
        }
    }

    private static class CachedBarSnapshot {
        private final Bar bar;
        private final long cachedAt;

        private CachedBarSnapshot(Bar bar, long cachedAt) {
            this.bar = bar;
            this.cachedAt = cachedAt;
        }
    }

    private static class CachedPriceSnapshot {
        private final double price;
        private final long cachedAt;

        private CachedPriceSnapshot(double price, long cachedAt) {
            this.price = price;
            this.cachedAt = cachedAt;
        }
    }

    private static class CachedMarketQuality {
        private final MarketQuality marketQuality;
        private final long cachedAt;

        private CachedMarketQuality(MarketQuality marketQuality, long cachedAt) {
            this.marketQuality = marketQuality;
            this.cachedAt = cachedAt;
        }
    }
    private Request.Builder authorizedRequest(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("APCA-API-KEY-ID", apiKey)
                .addHeader("APCA-API-SECRET-KEY", secretKey)
                .addHeader("accept", "application/json");
    }

    private JsonNode executeJson(Request request) throws IOException {
        waitForMarketDataRateLimitIfNeeded(request);

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();

            if (!response.isSuccessful()) {
                if (isMarketDataRequest(request) && response.code() == 429) {
                    activateMarketDataBackoff("HTTP_429 " + request.url());
                }

                throw new IOException(
                        "HTTP " + response.code() + " " + response.message() + " body=" + body
                );
            }

            return mapper.readTree(body);
        }
    }

    private static boolean isMarketDataRequest(Request request) {
        return request != null &&
                request.url() != null &&
                request.url().host() != null &&
                request.url().host().contains("data.alpaca.markets");
    }

    private static void waitForMarketDataRateLimitIfNeeded(Request request) {
        if (!isMarketDataRequest(request)) {
            return;
        }

        synchronized (MARKET_DATA_RATE_LIMIT_LOCK) {
            long now = System.currentTimeMillis();
            long nextAllowed = NEXT_MARKET_DATA_REQUEST_AT.get();
            if (nextAllowed > now) {
                long sleepMs = Math.min(30_000L, nextAllowed - now);
                System.out.println("ALPACA MARKET DATA BACKOFF ACTIVE: sleepMs=" + sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

            long next = System.currentTimeMillis() + MARKET_DATA_MIN_REQUEST_INTERVAL_MS;
            NEXT_MARKET_DATA_REQUEST_AT.set(Math.max(NEXT_MARKET_DATA_REQUEST_AT.get(), next));
        }
    }

    private static void activateMarketDataBackoff(String reason) {
        long until = System.currentTimeMillis() + MARKET_DATA_RATE_LIMIT_BACKOFF_MS;
        NEXT_MARKET_DATA_REQUEST_AT.updateAndGet(current -> Math.max(current, until));
        System.out.println("ALPACA MARKET DATA 429 BACKOFF SET: backoffMs=" +
                MARKET_DATA_RATE_LIMIT_BACKOFF_MS +
                " reason=" +
                reason);
    }

    public boolean isMarketDataBackoffActive() {
        return NEXT_MARKET_DATA_REQUEST_AT.get() > System.currentTimeMillis() + MARKET_DATA_MIN_REQUEST_INTERVAL_MS;
    }

    public long marketDataBackoffRemainingMs() {
        return Math.max(0L, NEXT_MARKET_DATA_REQUEST_AT.get() - System.currentTimeMillis());
    }

    private String textOrEmpty(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText();
    }

    private String rootCauseMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown_error";
        }

        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private String mask(String value) {
        if (value == null || value.length() < 8) {
            return "MISSING_OR_TOO_SHORT";
        }

        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    public static class AccountSnapshot {
        private final double equity;
        private final double buyingPower;
        private final double lastEquity;

        public AccountSnapshot(double equity, double buyingPower, double lastEquity) {
            this.equity = equity;
            this.buyingPower = buyingPower;
            this.lastEquity = lastEquity;
        }

        public double getEquity() {
            return equity;
        }

        public double getBuyingPower() {
            return buyingPower;
        }

        public double getLastEquity() {
            return lastEquity;
        }
    }
    private static long envLong(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0 ? parsed : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static double envDouble(
            String name,
            double defaultValue
    ) {
        try {
            String value = System.getenv(name);

            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

}