package com.bot.research;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.NewsEvent;
import com.bot.strategy.CatalystClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class HistoricalNewsCatalystResearch {

    private static final String DATA_BASE_URL =
            "https://data.alpaca.markets";

    private static final int PAGE_LIMIT = 50;
    private static final int TARGET_HEADLINES = 1_000;

    private final String apiKey;
    private final String secretKey;
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final CatalystClassifier classifier;

    public HistoricalNewsCatalystResearch() {
        this.apiKey = System.getenv("ALPACA_API_KEY");
        this.secretKey = System.getenv("ALPACA_SECRET_KEY");
        this.client = new OkHttpClient();
        this.mapper = new ObjectMapper();
        this.classifier = new CatalystClassifier();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing ALPACA_API_KEY");
        }

        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Missing ALPACA_SECRET_KEY");
        }
    }

    public void run() throws Exception {
        List<HeadlineSample> samples = fetchLatestHeadlines();

        Map<CatalystType, Integer> catalystCounts = new HashMap<>();
        Map<String, Integer> unknownPhraseCounts = new HashMap<>();
        List<HeadlineSample> unknowns = new ArrayList<>();

        for (HeadlineSample sample : samples) {
            NewsEvent event = new NewsEvent(
                    sample.id,
                    sample.symbol,
                    sample.headline,
                    sample.summary,
                    sample.createdAt
            );

            CatalystResult catalyst = classifier.classify(event);

            catalystCounts.put(
                    catalyst.type,
                    catalystCounts.getOrDefault(catalyst.type, 0) + 1
            );

            if (catalyst.type == CatalystType.UNKNOWN) {
                unknowns.add(sample);

                for (String phrase : importantPhrases(sample.headline)) {
                    unknownPhraseCounts.put(
                            phrase,
                            unknownPhraseCounts.getOrDefault(phrase, 0) + 1
                    );
                }
            }
        }

        System.out.println("========== HISTORICAL NEWS CATALYST RESEARCH ==========");
        System.out.println("Headlines analyzed: " + samples.size());
        System.out.println();

        printCatalystCounts(catalystCounts);
        printUnknownPhrases(unknownPhraseCounts);
        printUnknownExamples(unknowns);

        System.out.println("=======================================================");
    }

    private List<HeadlineSample> fetchLatestHeadlines() throws Exception {
        List<HeadlineSample> results = new ArrayList<>();

        String pageToken = null;

        Instant end = Instant.now();
        Instant start = end.minus(14, ChronoUnit.DAYS);

        while (results.size() < TARGET_HEADLINES) {
            HttpUrl.Builder urlBuilder =
                    HttpUrl.parse(DATA_BASE_URL + "/v1beta1/news")
                            .newBuilder()
                            .addQueryParameter("start", start.toString())
                            .addQueryParameter("end", end.toString())
                            .addQueryParameter("sort", "desc")
                            .addQueryParameter("limit", String.valueOf(PAGE_LIMIT))
                            .addQueryParameter("include_content", "false");

            if (pageToken != null && !pageToken.isBlank()) {
                urlBuilder.addQueryParameter("page_token", pageToken);
            }

            Request request =
                    new Request.Builder()
                            .url(urlBuilder.build())
                            .addHeader("APCA-API-KEY-ID", apiKey)
                            .addHeader("APCA-API-SECRET-KEY", secretKey)
                            .addHeader("accept", "application/json")
                            .get()
                            .build();

            JsonNode json = executeJson(request);

            JsonNode newsArray = json.path("news");

            if (!newsArray.isArray() || newsArray.isEmpty()) {
                break;
            }

            for (JsonNode item : newsArray) {
                if (results.size() >= TARGET_HEADLINES) {
                    break;
                }

                String id = item.path("id").asText("");
                String headline = item.path("headline").asText("");
                String summary = item.path("summary").asText("");
                long createdAt = parseTimestamp(item.path("created_at").asText(""));

                JsonNode symbols = item.path("symbols");

                String symbol = "";

                if (symbols.isArray() && !symbols.isEmpty()) {
                    symbol = symbols.get(0).asText("");
                }

                if (!headline.isBlank()) {
                    results.add(
                            new HeadlineSample(
                                    id,
                                    symbol,
                                    headline,
                                    summary,
                                    createdAt
                            )
                    );
                }
            }

            pageToken = json.path("next_page_token").asText("");

            System.out.println(
                    "Fetched headlines: " +
                            results.size() +
                            " nextPage=" +
                            (!pageToken.isBlank())
            );

            if (pageToken.isBlank()) {
                break;
            }
        }

        return results;
    }

    private JsonNode executeJson(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            String body =
                    response.body() == null
                            ? ""
                            : response.body().string();

            if (!response.isSuccessful()) {
                throw new IOException(
                        "HTTP " +
                                response.code() +
                                " " +
                                response.message() +
                                " body=" +
                                body
                );
            }

            return mapper.readTree(body);
        }
    }

    private long parseTimestamp(String value) {
        try {
            if (value == null || value.isBlank()) {
                return System.currentTimeMillis();
            }

            return Instant.parse(value).toEpochMilli();

        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private List<String> importantPhrases(String headline) {
        String normalized =
                headline.toLowerCase()
                        .replaceAll("[^a-z0-9$%\\. ]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();

        List<String> words =
                Arrays.stream(normalized.split(" "))
                        .filter(w -> w.length() >= 3)
                        .filter(w -> !stopWords().contains(w))
                        .toList();

        List<String> phrases = new ArrayList<>();

        for (int n = 1; n <= 4; n++) {
            for (int i = 0; i <= words.size() - n; i++) {
                String phrase =
                        String.join(" ", words.subList(i, i + n));

                if (looksImportant(phrase)) {
                    phrases.add(phrase);
                }
            }
        }

        return phrases;
    }

    private boolean looksImportant(String phrase) {
        return phrase.contains("announces") ||
                phrase.contains("reports") ||
                phrase.contains("files") ||
                phrase.contains("prices") ||
                phrase.contains("launches") ||
                phrase.contains("receives") ||
                phrase.contains("wins") ||
                phrase.contains("granted") ||
                phrase.contains("approval") ||
                phrase.contains("clearance") ||
                phrase.contains("contract") ||
                phrase.contains("order") ||
                phrase.contains("offering") ||
                phrase.contains("shelf") ||
                phrase.contains("resale") ||
                phrase.contains("downgrade") ||
                phrase.contains("upgrade") ||
                phrase.contains("target") ||
                phrase.contains("guidance") ||
                phrase.contains("trial") ||
                phrase.contains("phase") ||
                phrase.contains("endpoint") ||
                phrase.contains("merger") ||
                phrase.contains("acquisition") ||
                phrase.contains("partnership") ||
                phrase.contains("collaboration") ||
                phrase.contains("dividend") ||
                phrase.contains("split") ||
                phrase.contains("buyback") ||
                phrase.contains("repurchase") ||
                phrase.contains("layoff") ||
                phrase.contains("sec") ||
                phrase.contains("doj") ||
                phrase.contains("recall");
    }

    private Set<String> stopWords() {
        return Set.of(
                "the",
                "and",
                "for",
                "with",
                "from",
                "into",
                "that",
                "this",
                "have",
                "will",
                "are",
                "was",
                "were",
                "has",
                "had",
                "its",
                "over",
                "under",
                "after",
                "before",
                "today",
                "stock",
                "stocks",
                "shares",
                "share",
                "market"
        );
    }

    private void printCatalystCounts(Map<CatalystType, Integer> counts) {
        System.out.println("Catalyst Counts:");

        counts.entrySet()
                .stream()
                .sorted(Map.Entry.<CatalystType, Integer>comparingByValue().reversed())
                .forEach(entry ->
                        System.out.println(
                                entry.getKey() +
                                        ": " +
                                        entry.getValue()
                        )
                );

        System.out.println();
    }

    private void printUnknownPhrases(Map<String, Integer> counts) {
        System.out.println("Top Unknown Phrases:");

        counts.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(75)
                .forEach(entry ->
                        System.out.println(
                                entry.getValue() +
                                        " | " +
                                        entry.getKey()
                        )
                );

        System.out.println();
    }

    private void printUnknownExamples(List<HeadlineSample> unknowns) {
        System.out.println("Unknown Examples:");

        unknowns.stream()
                .limit(75)
                .forEach(sample ->
                        System.out.println(
                                sample.symbol +
                                        " | " +
                                        sample.headline
                        )
                );

        System.out.println();
    }

    private static class HeadlineSample {

        final String id;
        final String symbol;
        final String headline;
        final String summary;
        final long createdAt;

        HeadlineSample(
                String id,
                String symbol,
                String headline,
                String summary,
                long createdAt
        ) {
            this.id = id;
            this.symbol = symbol;
            this.headline = headline;
            this.summary = summary;
            this.createdAt = createdAt;
        }
    }
}