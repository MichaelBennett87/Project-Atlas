package com.bot.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Locale;

public class FmpFloatDataProvider implements FloatDataProvider {

    private static final String BASE_URL =
            "https://financialmodelingprep.com/api/v4/shares_float";

    private final String apiKey;
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public FmpFloatDataProvider() {
        this.apiKey = System.getenv("FMP_API_KEY");
        this.client = new OkHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public Long getSharesFloat(String ticker) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        if (ticker == null || ticker.isBlank()) {
            return null;
        }

        String normalized =
                ticker.toUpperCase(Locale.ROOT);

        String url =
                BASE_URL +
                        "?symbol=" +
                        normalized +
                        "&apikey=" +
                        apiKey;

        try {
            Request request =
                    new Request.Builder()
                            .url(url)
                            .get()
                            .build();

            JsonNode json =
                    executeJson(request);

            if (!json.isArray() || json.isEmpty()) {
                return null;
            }

            JsonNode first =
                    json.get(0);

            long sharesFloat =
                    first.path("floatShares").asLong(0);

            if (sharesFloat <= 0) {
                sharesFloat =
                        first.path("freeFloat").asLong(0);
            }

            if (sharesFloat <= 0) {
                return null;
            }

            return sharesFloat;

        } catch (Exception e) {
            System.err.println(
                    "FMP float lookup failed for " +
                            normalized +
                            ": " +
                            e.getMessage()
            );

            return null;
        }
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
}