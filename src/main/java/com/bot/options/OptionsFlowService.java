package com.bot.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;

public class OptionsFlowService {

    private static final String DATA_BASE_URL =
            "https://data.alpaca.markets";

    private static final double LARGE_CALL_PREMIUM = 75_000.0;
    private static final double LARGE_PUT_PREMIUM = 75_000.0;
    private static final int MAX_DAYS_TO_EXPIRATION = 45;

    private final String apiKey;
    private final String secretKey;
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public OptionsFlowService() {
        this.apiKey = System.getenv("ALPACA_API_KEY");
        this.secretKey = System.getenv("ALPACA_SECRET_KEY");
        this.client = new OkHttpClient();
        this.mapper = new ObjectMapper();
    }

    public OptionsFlowDecision check(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return OptionsFlowDecision.neutral("Missing ticker");
        }

        if (apiKey == null || apiKey.isBlank() ||
                secretKey == null || secretKey.isBlank()) {
            return OptionsFlowDecision.neutral("Missing Alpaca API credentials");
        }

        try {
            JsonNode root = fetchOptionChain(ticker);
            JsonNode snapshots = root.path("snapshots");

            if (!snapshots.isObject()) {
                return OptionsFlowDecision.neutral("No options snapshots found");
            }

            double largestCallPremium = 0.0;
            double largestPutPremium = 0.0;

            String largestCallContract = null;
            String largestPutContract = null;

            Iterator<Map.Entry<String, JsonNode>> fields =
                    snapshots.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();

                String contractSymbol = entry.getKey();
                JsonNode snapshot = entry.getValue();

                if (!isNearTerm(contractSymbol)) {
                    continue;
                }

                JsonNode latestTrade = snapshot.path("latestTrade");

                if (latestTrade.isMissingNode() || latestTrade.isNull()) {
                    continue;
                }

                double tradePrice = latestTrade.path("p").asDouble(0.0);
                int tradeSize = latestTrade.path("s").asInt(0);

                if (tradePrice <= 0 || tradeSize <= 0) {
                    continue;
                }

                double premium =
                        tradePrice * tradeSize * 100.0;

                if (isCall(contractSymbol)) {
                    if (premium > largestCallPremium) {
                        largestCallPremium = premium;
                        largestCallContract = contractSymbol;
                    }
                } else if (isPut(contractSymbol)) {
                    if (premium > largestPutPremium) {
                        largestPutPremium = premium;
                        largestPutContract = contractSymbol;
                    }
                }
            }

            System.out.println(
                    "OPTIONS FLOW: " +
                            ticker +
                            " largestCall=" +
                            largestCallPremium +
                            " contract=" +
                            largestCallContract +
                            " largestPut=" +
                            largestPutPremium +
                            " contract=" +
                            largestPutContract
            );

            if (largestPutPremium >= LARGE_PUT_PREMIUM &&
                    largestPutPremium > largestCallPremium * 1.25) {
                return OptionsFlowDecision.bearish(
                        "Large put flow warning: " +
                                largestPutContract +
                                " premium=" +
                                largestPutPremium
                );
            }

            if (largestCallPremium >= LARGE_CALL_PREMIUM &&
                    largestCallPremium > largestPutPremium) {
                return OptionsFlowDecision.bullish(
                        "Large call flow detected: " +
                                largestCallContract +
                                " premium=" +
                                largestCallPremium
                );
            }

            return OptionsFlowDecision.neutral("No large options confirmation");

        } catch (Exception e) {
            return OptionsFlowDecision.neutral(
                    "Options flow unavailable: " + e.getMessage()
            );
        }
    }

    private JsonNode fetchOptionChain(String ticker) throws IOException {
        HttpUrl url =
                HttpUrl.parse(
                                DATA_BASE_URL +
                                        "/v1beta1/options/snapshots/" +
                                        ticker
                        )
                        .newBuilder()
                        .addQueryParameter("feed", "opra")
                        .build();

        Request request =
                new Request.Builder()
                        .url(url)
                        .addHeader("APCA-API-KEY-ID", apiKey)
                        .addHeader("APCA-API-SECRET-KEY", secretKey)
                        .addHeader("accept", "application/json")
                        .get()
                        .build();

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

    private boolean isCall(String contractSymbol) {
        return contractSymbol != null &&
                contractSymbol.length() > 15 &&
                contractSymbol.contains("C");
    }

    private boolean isPut(String contractSymbol) {
        return contractSymbol != null &&
                contractSymbol.length() > 15 &&
                contractSymbol.contains("P");
    }

    private boolean isNearTerm(String contractSymbol) {
        try {
            if (contractSymbol == null || contractSymbol.length() < 15) {
                return false;
            }

            String datePart =
                    contractSymbol.replaceAll("[^0-9]", "")
                            .substring(0, 6);

            LocalDate expiration =
                    LocalDate.parse(
                            datePart,
                            DateTimeFormatter.ofPattern("yyMMdd")
                    );

            long days =
                    java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.now(),
                            expiration
                    );

            return days >= 0 && days <= MAX_DAYS_TO_EXPIRATION;

        } catch (Exception e) {
            return true;
        }
    }

    public static class OptionsFlowDecision {

        public final boolean bullish;
        public final boolean bearish;
        public final String reason;

        private OptionsFlowDecision(
                boolean bullish,
                boolean bearish,
                String reason
        ) {
            this.bullish = bullish;
            this.bearish = bearish;
            this.reason = reason;
        }

        public static OptionsFlowDecision bullish(String reason) {
            return new OptionsFlowDecision(true, false, reason);
        }

        public static OptionsFlowDecision bearish(String reason) {
            return new OptionsFlowDecision(false, true, reason);
        }

        public static OptionsFlowDecision neutral(String reason) {
            return new OptionsFlowDecision(false, false, reason);
        }
    }
}