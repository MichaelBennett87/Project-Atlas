package com.bot;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.NewsEvent;
import com.bot.strategy.CatalystClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HistoricalNewsBackfillAnalyzer {

    private static final String BASE_URL =
            "https://data.alpaca.markets/v1beta1/news";

    private static final int TRADING_DAYS_TO_ANALYZE =
            10;

    private static final int PAGE_LIMIT =
            50;

    private static final int MAX_UNKNOWN_HEADLINES_TO_PRINT =
            500;

    private final String apiKey;
    private final String secretKey;
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final CatalystClassifier classifier;

    public static void main(String[] args) throws Exception {
        String apiKey =
                System.getenv("ALPACA_API_KEY");

        String secretKey =
                System.getenv("ALPACA_SECRET_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing ALPACA_API_KEY environment variable.");
        }

        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Missing ALPACA_SECRET_KEY environment variable.");
        }

        HistoricalNewsBackfillAnalyzer analyzer =
                new HistoricalNewsBackfillAnalyzer(
                        apiKey,
                        secretKey
                );

        analyzer.run();
    }

    public HistoricalNewsBackfillAnalyzer(
            String apiKey,
            String secretKey
    ) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.client = new OkHttpClient();
        this.mapper = new ObjectMapper();
        this.classifier = new CatalystClassifier();
    }

    public void run() throws Exception {
        LocalDate endDate =
                LocalDate.now(ZoneOffset.UTC);

        LocalDate startDate =
                oldestDateForLastTradingDays(
                        TRADING_DAYS_TO_ANALYZE
                );

        System.out.println();
        System.out.println("=== HISTORICAL NEWS BACKFILL ANALYZER ===");
        System.out.println("Start date: " + startDate);
        System.out.println("End date: " + endDate);
        System.out.println("Trading days requested: " + TRADING_DAYS_TO_ANALYZE);

        List<JsonNode> articles =
                fetchNewsWindow(
                        startDate,
                        endDate
                );

        Map<CatalystType, Integer> catalystCounts =
                new LinkedHashMap<>();

        Set<String> unknownHeadlines =
                new LinkedHashSet<>();

        int stockSymbolClassifications =
                0;

        int unknownClassificationCount =
                0;

        for (JsonNode article : articles) {
            String id =
                    article.path("id").asText();

            String headline =
                    article.path("headline").asText();

            String content =
                    article.path("content").asText();

            long timestamp =
                    parseCreatedAtMillis(
                            firstNonBlank(
                                    article.path("created_at").asText(),
                                    article.path("updated_at").asText()
                            )
                    );

            JsonNode symbols =
                    article.path("symbols");

            if (!symbols.isArray() || symbols.isEmpty()) {
                continue;
            }

            for (JsonNode symbolNode : symbols) {
                String ticker =
                        symbolNode.asText();

                if (!isStockTicker(ticker)) {
                    continue;
                }

                NewsEvent news =
                        new NewsEvent(
                                id + ":" + ticker,
                                ticker,
                                headline,
                                content,
                                timestamp
                        );

                CatalystResult result =
                        classifier.classify(news);

                catalystCounts.put(
                        result.type,
                        catalystCounts.getOrDefault(result.type, 0) + 1
                );

                stockSymbolClassifications++;

                if (result.type == CatalystType.UNKNOWN) {
                    unknownClassificationCount++;

                    unknownHeadlines.add(
                            ticker +
                                    " | " +
                                    headline
                    );
                }
            }
        }

        System.out.println();
        System.out.println("=== 10 TRADING DAY HISTORICAL NEWS ANALYSIS ===");
        System.out.println("Articles fetched: " + articles.size());
        System.out.println("Stock-symbol classifications: " + stockSymbolClassifications);
        System.out.println("UNKNOWN classifications: " + unknownClassificationCount);
        System.out.println("Unique UNKNOWN headlines: " + unknownHeadlines.size());

        double unknownRate =
                stockSymbolClassifications == 0
                        ? 0.0
                        : (double) unknownClassificationCount / stockSymbolClassifications;

        System.out.println("UNKNOWN rate: " + unknownRate);

        System.out.println();
        System.out.println("=== CATALYST COUNTS ===");

        for (Map.Entry<CatalystType, Integer> entry : catalystCounts.entrySet()) {
            System.out.println(
                    entry.getKey() +
                            ": " +
                            entry.getValue()
            );
        }

        System.out.println();
        System.out.println("=== UNKNOWN HEADLINES TO ADD TO CATALYST CLASSIFIER ===");

        int printed =
                0;

        for (String headline : unknownHeadlines) {
            if (printed >= MAX_UNKNOWN_HEADLINES_TO_PRINT) {
                break;
            }

            System.out.println(headline);
            printed++;
        }

        if (unknownHeadlines.size() > MAX_UNKNOWN_HEADLINES_TO_PRINT) {
            System.out.println();
            System.out.println(
                    "NOTE: Printed first " +
                            MAX_UNKNOWN_HEADLINES_TO_PRINT +
                            " unique UNKNOWN headlines out of " +
                            unknownHeadlines.size() +
                            ". Increase MAX_UNKNOWN_HEADLINES_TO_PRINT if needed."
            );
        }

        System.out.println();
        System.out.println("Copy the UNKNOWN HEADLINES section back into ChatGPT.");
    }

    private List<JsonNode> fetchNewsWindow(
            LocalDate startDate,
            LocalDate endDate
    ) throws Exception {
        List<JsonNode> articles =
                new ArrayList<>();

        String nextPageToken =
                null;

        while (true) {
            HttpUrl.Builder urlBuilder =
                    HttpUrl.parse(BASE_URL)
                            .newBuilder()
                            .addQueryParameter("start", startDate.toString())
                            .addQueryParameter("end", endDate.toString())
                            .addQueryParameter("sort", "asc")
                            .addQueryParameter("limit", String.valueOf(PAGE_LIMIT))
                            .addQueryParameter("include_content", "true")
                            .addQueryParameter("exclude_contentless", "false");

            if (hasUsablePageToken(nextPageToken)) {
                urlBuilder.addQueryParameter(
                        "page_token",
                        nextPageToken
                );
            }

            String url =
                    urlBuilder.build().toString();

            Request request =
                    new Request.Builder()
                            .url(url)
                            .addHeader("APCA-API-KEY-ID", apiKey)
                            .addHeader("APCA-API-SECRET-KEY", secretKey)
                            .addHeader("accept", "application/json")
                            .build();

            try (Response response = client.newCall(request).execute()) {
                String body =
                        response.body() == null
                                ? ""
                                : response.body().string();

                if (!response.isSuccessful()) {
                    System.err.println();
                    System.err.println("=== ALPACA NEWS REQUEST FAILED ===");
                    System.err.println("HTTP code: " + response.code());
                    System.err.println("Message: " + response.message());
                    System.err.println("URL: " + url);
                    System.err.println("Body: " + body);

                    throw new IllegalStateException(
                            "Alpaca news request failed: " +
                                    response.code() +
                                    " " +
                                    response.message()
                    );
                }

                JsonNode root =
                        mapper.readTree(body);

                JsonNode news =
                        root.path("news");

                if (news.isArray()) {
                    for (JsonNode item : news) {
                        articles.add(item);
                    }
                }

                nextPageToken =
                        readPageToken(root);

                System.out.println(
                        "Fetched page. Total articles so far: " +
                                articles.size() +
                                " nextPageToken=" +
                                (hasUsablePageToken(nextPageToken)
                                        ? "present"
                                        : "none")
                );

                if (!hasUsablePageToken(nextPageToken)) {
                    System.out.println(
                            "Reached final page. Total articles fetched: " +
                                    articles.size()
                    );

                    break;
                }
            }
        }

        return articles;
    }

    private String readPageToken(
            JsonNode root
    ) {
        if (root == null || !root.has("next_page_token")) {
            return null;
        }

        JsonNode tokenNode =
                root.get("next_page_token");

        if (tokenNode == null || tokenNode.isNull()) {
            return null;
        }

        String token =
                tokenNode.asText();

        if (!hasUsablePageToken(token)) {
            return null;
        }

        return token;
    }

    private boolean hasUsablePageToken(
            String token
    ) {
        return token != null &&
                !token.isBlank() &&
                !"null".equalsIgnoreCase(token.trim());
    }

    private LocalDate oldestDateForLastTradingDays(
            int count
    ) {
        int tradingDaysFound =
                0;

        LocalDate cursor =
                LocalDate.now(ZoneOffset.UTC)
                        .minusDays(1);

        LocalDate oldest =
                cursor;

        while (tradingDaysFound < count) {
            DayOfWeek dayOfWeek =
                    cursor.getDayOfWeek();

            if (dayOfWeek != DayOfWeek.SATURDAY &&
                    dayOfWeek != DayOfWeek.SUNDAY) {
                tradingDaysFound++;
                oldest = cursor;
            }

            cursor =
                    cursor.minusDays(1);
        }

        return oldest;
    }

    private long parseCreatedAtMillis(
            String createdAt
    ) {
        try {
            return java.time.Instant.parse(createdAt).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private boolean isStockTicker(
            String ticker
    ) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }

        String normalized =
                ticker.trim()
                        .toUpperCase();

        if (normalized.endsWith("USD")) {
            return false;
        }

        if (normalized.contains("/") ||
                normalized.contains(":") ||
                normalized.contains(".")) {
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

    private String firstNonBlank(
            String first,
            String second
    ) {
        if (first != null && !first.isBlank()) {
            return first;
        }

        if (second != null && !second.isBlank()) {
            return second;
        }

        return "";
    }
}